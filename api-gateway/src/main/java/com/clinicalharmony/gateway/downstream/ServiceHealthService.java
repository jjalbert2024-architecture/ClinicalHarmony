package com.clinicalharmony.gateway.downstream;

import com.clinicalharmony.gateway.config.GatewayProperties;
import com.clinicalharmony.gateway.config.MdcLoggingFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code GET /api/v1/services/health}: probes every downstream {@code /actuator/health} in parallel
 * with a short 2 s budget (independent of the services' normal read timeouts), plus the gateway's
 * own database connection. Never fails as a whole: an unreachable service is a row, and the
 * overall status is {@code UP} or {@code DEGRADED} with the names of what's down.
 */
@Service
public class ServiceHealthService {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    public record ServiceStatus(String service, String status, Integer httpStatus, long latencyMs, String detail) {
    }

    public record HealthReport(String status, Instant checkedAt, List<String> degraded, List<ServiceStatus> services) {
    }

    private final Map<Downstream, RestClient> probes = new EnumMap<>(Downstream.class);
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(Downstream.values().length, r -> {
        Thread t = new Thread(r, "health-probe");
        t.setDaemon(true);
        return t;
    });

    public ServiceHealthService(GatewayProperties properties, RestClient.Builder builder, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        for (Downstream service : Downstream.values()) {
            probes.put(service, DownstreamClient.build(builder, properties.services().get(service.key()).baseUrl(),
                    PROBE_TIMEOUT, PROBE_TIMEOUT));
        }
    }

    public HealthReport check() {
        String correlationId = MdcLoggingFilter.current();
        List<CompletableFuture<ServiceStatus>> futures = new ArrayList<>();
        for (Downstream service : Downstream.values()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (correlationId != null) {
                    MDC.put(MdcLoggingFilter.REQUEST_ID_KEY, correlationId);
                }
                try {
                    return probe(service);
                } finally {
                    MDC.remove(MdcLoggingFilter.REQUEST_ID_KEY);
                }
            }, pool));
        }
        List<ServiceStatus> statuses = new ArrayList<>();
        statuses.add(database());
        futures.forEach(f -> statuses.add(f.join()));
        List<String> degraded = statuses.stream().filter(s -> !"UP".equals(s.status())).map(ServiceStatus::service).toList();
        return new HealthReport(degraded.isEmpty() ? "UP" : "DEGRADED", Instant.now(), degraded, statuses);
    }

    ServiceStatus probe(Downstream service) {
        long started = System.nanoTime();
        try {
            JsonNode body = probes.get(service).get().uri("/actuator/health").retrieve().body(JsonNode.class);
            String status = body == null ? "UNKNOWN" : body.path("status").asText("UNKNOWN");
            return new ServiceStatus(service.serviceName(), status, 200, elapsed(started), null);
        } catch (RestClientResponseException e) {
            // Actuator answers 503 with {"status":"DOWN"} when one of its own checks fails.
            return new ServiceStatus(service.serviceName(), "DOWN", e.getStatusCode().value(), elapsed(started),
                    DownstreamErrors.messageOf(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            DownstreamException translated = DownstreamErrors.translate(service.serviceName(), e, "GET /actuator/health");
            return new ServiceStatus(service.serviceName(), translated.kind().name(), null, elapsed(started),
                    translated.getMessage());
        }
    }

    private ServiceStatus database() {
        long started = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new ServiceStatus("postgres (gateway read-only role)", "UP", null, elapsed(started), null);
        } catch (Exception e) {
            return new ServiceStatus("postgres (gateway read-only role)", "UNAVAILABLE", null, elapsed(started),
                    e.getMessage());
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
