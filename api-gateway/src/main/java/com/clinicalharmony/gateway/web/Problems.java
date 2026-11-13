package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.config.MdcLoggingFilter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * The gateway's one error shape: RFC 7807 {@code application/problem+json} with
 * {@code type, title, status, detail, instance} plus {@code correlationId} on every error, and
 * {@code service} / {@code downstreamStatus} when a downstream call is what failed. Used by the
 * MVC exception handler and by the security entry point alike, so a 401 looks like a 503.
 */
public final class Problems {

    /** Problem types are URNs (there's no public docs site to point a URL at). */
    public static final String TYPE_PREFIX = "urn:clinicalharmony:problem:";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String typeSlug, String title, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + typeSlug));
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        stamp(problem);
        return problem;
    }

    /** Adds the correlation id (and keeps an existing one) — also applied to Spring's own ProblemDetails. */
    public static ProblemDetail stamp(ProblemDetail problem) {
        String correlationId = MdcLoggingFilter.current();
        if (correlationId != null && (problem.getProperties() == null
                || !problem.getProperties().containsKey("correlationId"))) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
