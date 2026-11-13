package com.clinicalharmony.gateway.downstream;

import com.clinicalharmony.gateway.config.GatewayProperties;
import com.clinicalharmony.gateway.config.MdcLoggingFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The gateway's only way to call a downstream service. Per call it:
 * <ul>
 *   <li>forwards the request's {@code X-Correlation-Id}</li>
 *   <li>adds the gateway's own Bearer token for {@link Downstream#secured()} services (never the user's)</li>
 *   <li>applies that service's connect/read timeouts ({@code gateway.services.<key>})</li>
 *   <li>retries once: on a 401 from a secured service (after dropping the cached token), and for
 *       GETs only, on a connection failure — POST/PUT are never replayed</li>
 *   <li>turns every failure into a {@link DownstreamException} (→ 502/503/504 or the service's 4xx)</li>
 * </ul>
 * Uses the JDK HttpClient: HttpURLConnection throws HttpRetryException on a 401 to a streamed POST.
 */
@Component
public class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    private final Map<Downstream, RestClient> clients = new EnumMap<>(Downstream.class);
    private final ServiceTokenProvider tokens;

    public DownstreamClient(GatewayProperties properties, RestClient.Builder builder, ServiceTokenProvider tokens) {
        this.tokens = tokens;
        for (Downstream service : Downstream.values()) {
            GatewayProperties.ServiceEndpoint endpoint = properties.services().get(service.key());
            if (endpoint == null) {
                throw new IllegalStateException("Missing config gateway.services." + service.key());
            }
            clients.put(service, build(builder, endpoint.baseUrl(), endpoint.connectTimeout(), endpoint.readTimeout()));
        }
    }

    static RestClient build(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MdcLoggingFilter.current();
                    if (correlationId != null) {
                        request.getHeaders().set(MdcLoggingFilter.CORRELATION_HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public ResponseEntity<JsonNode> get(Downstream service, String uriTemplate, Object... uriVariables) {
        return exchange(service, HttpMethod.GET, uriTemplate, null, null, uriVariables);
    }

    public ResponseEntity<JsonNode> post(Downstream service, String uriTemplate, MediaType contentType, Object body,
                                         Object... uriVariables) {
        return exchange(service, HttpMethod.POST, uriTemplate, contentType, body, uriVariables);
    }

    public ResponseEntity<JsonNode> put(Downstream service, String uriTemplate, Object... uriVariables) {
        return exchange(service, HttpMethod.PUT, uriTemplate, null, null, uriVariables);
    }

    public ResponseEntity<JsonNode> exchange(Downstream service, HttpMethod method, String uriTemplate,
                                             MediaType contentType, Object body, Object... uriVariables) {
        String what = method.name() + " " + uriTemplate;
        long started = System.nanoTime();
        try {
            ResponseEntity<JsonNode> response = withRetries(service, method, () -> call(service, method, uriTemplate,
                    contentType, body, uriVariables));
            log.info("{} {} → {} in {} ms", service.serviceName(), what, response.getStatusCode().value(),
                    (System.nanoTime() - started) / 1_000_000);
            return response;
        } catch (RestClientException e) {
            DownstreamException translated = DownstreamErrors.translate(service.serviceName(), e, what);
            log.warn("{} {} failed after {} ms: {} ({})", service.serviceName(), what,
                    (System.nanoTime() - started) / 1_000_000, translated.kind(), translated.getMessage());
            throw translated;
        }
    }

    private ResponseEntity<JsonNode> call(Downstream service, HttpMethod method, String uriTemplate,
                                          MediaType contentType, Object body, Object[] uriVariables) {
        RestClient.RequestBodySpec spec = clients.get(service).method(method).uri(uriTemplate, uriVariables)
                .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("application/fhir+json"),
                        MediaType.APPLICATION_PROBLEM_JSON);
        if (service.secured()) {
            spec = spec.headers(h -> h.setBearerAuth(tokens.token()));
        }
        if (body != null) {
            spec = spec.contentType(contentType == null ? MediaType.APPLICATION_JSON : contentType).body(body);
        }
        return spec.retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> withRetries(Downstream service, HttpMethod method,
                                                 java.util.function.Supplier<ResponseEntity<JsonNode>> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (service.secured() && e.getStatusCode().value() == 401) {
                log.info("{} rejected the gateway's cached token (401); fetching a new one and retrying once",
                        service.serviceName());
                tokens.invalidate();
                return call.get();
            }
            throw e;
        } catch (RestClientException e) {
            if (method == HttpMethod.GET && DownstreamErrors.translate(service.serviceName(), e, "")
                    .kind() == DownstreamException.Kind.UNAVAILABLE) {
                log.info("{} unreachable on GET; retrying once", service.serviceName());
                return call.get();
            }
            throw e;
        }
    }
}
