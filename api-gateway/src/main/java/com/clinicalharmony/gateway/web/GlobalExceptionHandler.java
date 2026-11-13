package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.downstream.DownstreamException;
import com.clinicalharmony.gateway.read.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every error the gateway returns is an RFC 7807 problem (see {@link Problems}) — its own (400/404/409),
 * Spring MVC's (bad params, 404 no route, 405, 415), the database's, and every downstream failure,
 * whatever shape the service used ({@code {"error","message"}}, OperationOutcome, …).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ProblemDetail> downstream(DownstreamException e, HttpServletRequest request) {
        HttpStatus status = e.gatewayStatus();
        String slug = switch (e.kind()) {
            case UNAVAILABLE -> "downstream-unavailable";
            case TIMEOUT -> "downstream-timeout";
            case BAD_GATEWAY -> "bad-gateway";
            case REJECTED -> "downstream-rejected";
        };
        ProblemDetail problem = Problems.of(status, slug, status.getReasonPhrase(), e.getMessage(), request.getRequestURI());
        problem.setProperty("service", e.service());
        if (e.downstreamStatus() != null) {
            problem.setProperty("downstreamStatus", e.downstreamStatus());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NotFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Problems.of(HttpStatus.NOT_FOUND, "not-found",
                "Not Found", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status()).body(Problems.of(e.status(), e.typeSlug(), e.status().getReasonPhrase(),
                e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> databaseDown(DataAccessException e, HttpServletRequest request) {
        log.warn("Database unavailable: {}", e.getMessage());
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Service Unavailable",
                "The gateway cannot reach its database right now", request.getRequestURI());
        problem.setProperty("service", "postgres");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException e, HttpServletRequest request) {
        log.error("Read model query failed", e);
        return ResponseEntity.internalServerError().body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "database-error",
                "Internal Server Error", "A read-model query failed; see the gateway log for this correlation id",
                request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal Server Error", "Unexpected error; see the gateway log for this correlation id",
                request.getRequestURI()));
    }

    /** Bad query/path parameters: name each one and why, not Spring's bare "Validation failure". */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.add(Map.of(
                    "parameter", name == null ? "?" : name,
                    "message", String.valueOf(error.getDefaultMessage()))));
        });
        String detail = errors.isEmpty() ? "Invalid request parameters" : errors.stream()
                .map(e -> e.get("parameter") + ": " + e.get("message")).collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Bad Request");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "No endpoint " + ex.getHttpMethod() + " /"
                + ex.getResourcePath() + " — see the api-gateway README for the /api/v1 endpoints");
        problem.setTitle("Not Found");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Spring MVC's own problems: same type URN scheme, plus instance + correlationId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
                problem.setType(URI.create(Problems.TYPE_PREFIX + slugFor(statusCode.value())));
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servlet) {
                problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
            Problems.stamp(problem);
        }
        return response;
    }

    private static String slugFor(int status) {
        return switch (status) {
            case 400 -> "invalid-request";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            default -> "http-" + status;
        };
    }
}
