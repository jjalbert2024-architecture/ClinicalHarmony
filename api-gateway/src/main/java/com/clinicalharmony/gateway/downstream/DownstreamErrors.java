package com.clinicalharmony.gateway.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * Maps RestClient failures onto {@link DownstreamException} kinds and pulls a human message out of
 * whichever error shape the service uses: {@code {"error","message"}}, an RFC 7807 problem,
 * ontology-service's lookup {@code {"valid":false,"reason"}}, a FHIR
 * OperationOutcome, or Spring's default error body.
 */
public final class DownstreamErrors {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DETAIL = 500;

    private DownstreamErrors() {
    }

    public static DownstreamException translate(String service, RestClientException e, String what) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String message = messageOf(response.getResponseBodyAsString());
            if (status == 401 || status == 403) {
                return new DownstreamException(DownstreamException.Kind.BAD_GATEWAY, service, status,
                        service + " refused the gateway's own credentials (" + status + ") trying to " + what + ": " + message, e);
            }
            if (status >= 400 && status < 500) {
                return new DownstreamException(DownstreamException.Kind.REJECTED, service, status, message, e);
            }
            return new DownstreamException(DownstreamException.Kind.BAD_GATEWAY, service, status,
                    service + " failed (" + status + ") trying to " + what + ": " + message, e);
        }
        if (e instanceof ResourceAccessException) {
            if (has(e, HttpConnectTimeoutException.class) || has(e, ConnectException.class)
                    || has(e, UnknownHostException.class) || has(e, NoRouteToHostException.class)
                    || has(e, UnresolvedAddressException.class)) {
                return new DownstreamException(DownstreamException.Kind.UNAVAILABLE, service, null,
                        service + " is unreachable (trying to " + what + ")", e);
            }
            if (has(e, HttpTimeoutException.class)) {
                return new DownstreamException(DownstreamException.Kind.TIMEOUT, service, null,
                        service + " did not answer in time (trying to " + what + ")", e);
            }
        }
        return new DownstreamException(DownstreamException.Kind.BAD_GATEWAY, service, null,
                service + " gave an unusable response trying to " + what + ": " + e.getMessage(), e);
    }

    static boolean has(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Best-effort human message from a downstream error body. */
    public static String messageOf(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node != null && node.isObject()) {
                for (String field : new String[]{"message", "detail", "reason", "error"}) {
                    if (node.hasNonNull(field) && node.get(field).isValueNode()) {
                        return truncate(node.get(field).asText());
                    }
                }
                JsonNode diagnostics = node.at("/issue/0/diagnostics");
                if (diagnostics.isTextual()) {
                    return truncate(diagnostics.asText());
                }
            }
        } catch (Exception ignored) {
            // not JSON — use the raw text
        }
        return truncate(body.strip());
    }

    private static String truncate(String s) {
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "…";
    }
}
