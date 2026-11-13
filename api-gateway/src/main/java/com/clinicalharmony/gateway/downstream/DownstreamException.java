package com.clinicalharmony.gateway.downstream;

import org.springframework.http.HttpStatus;

/**
 * A downstream call failed. {@link Kind} decides the gateway's status:
 * <ul>
 *   <li>{@code UNAVAILABLE} → 503: connection refused / unknown host / connect timeout (service down)</li>
 *   <li>{@code TIMEOUT} → 504: connected, but no answer within the service's read timeout</li>
 *   <li>{@code BAD_GATEWAY} → 502: the service answered 5xx or something unusable, or refused the
 *       gateway's OWN credentials (a downstream 401/403 is a gateway config problem, not the user's)</li>
 *   <li>{@code REJECTED} → the service's own 4xx (400/404/409/413/415/422), passed through with its message</li>
 * </ul>
 */
public class DownstreamException extends RuntimeException {

    public enum Kind { UNAVAILABLE, TIMEOUT, BAD_GATEWAY, REJECTED }

    private final Kind kind;
    private final String service;
    private final Integer downstreamStatus;

    public DownstreamException(Kind kind, String service, Integer downstreamStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.service = service;
        this.downstreamStatus = downstreamStatus;
    }

    public Kind kind() {
        return kind;
    }

    public String service() {
        return service;
    }

    public Integer downstreamStatus() {
        return downstreamStatus;
    }

    public HttpStatus gatewayStatus() {
        return switch (kind) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BAD_GATEWAY -> HttpStatus.BAD_GATEWAY;
            case REJECTED -> {
                HttpStatus status = downstreamStatus == null ? null : HttpStatus.resolve(downstreamStatus);
                yield status == null ? HttpStatus.BAD_REQUEST : status;
            }
        };
    }
}
