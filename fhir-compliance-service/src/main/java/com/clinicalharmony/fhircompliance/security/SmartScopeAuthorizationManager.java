package com.clinicalharmony.fhircompliance.security;

import com.clinicalharmony.fhircompliance.cms0057.Cms0057SearchParameters;
import com.clinicalharmony.fhircompliance.security.SmartScopes.Interaction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Authorizes {@code /fhir/**} requests against the token's SMART scopes (see {@link SmartScopes}).
 *
 * <p>Anonymous → denied (the entry point turns that into a 401). For a supported CMS-0057 type,
 * {@code GET /fhir/r4/{type}} is a search and {@code GET /fhir/r4/{type}/{id}} a read, each needing
 * its scope. Anything else under {@code /fhir} (unsupported type, extra path segments) only needs a
 * valid token, so the controller can answer with its usual 404 OperationOutcome rather than a 403
 * that would hide whether the type exists.
 */
public class SmartScopeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    static final String FHIR_BASE = "/fhir/r4/";
    private static final String SCOPE_PREFIX = "SCOPE_";

    /** The (type, interaction) a /fhir/r4 request needs a scope for. */
    public record Target(String resourceType, Interaction interaction) {
        public String requiredScope() {
            return SmartScopes.required(resourceType, interaction);
        }
    }

    @Override
    @SuppressWarnings("deprecation") // check() is still the abstract method in Spring Security 6.5
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        Optional<Target> target = target(context.getRequest());
        if (target.isEmpty()) {
            return new AuthorizationDecision(true);
        }
        List<String> scopes = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(SCOPE_PREFIX))
                .map(a -> a.substring(SCOPE_PREFIX.length()))
                .toList();
        return new AuthorizationDecision(
                SmartScopes.permits(scopes, target.get().resourceType(), target.get().interaction()));
    }

    /** Parses {@code /fhir/r4/{type}[/{id}]}; empty when no scope check applies. */
    public static Optional<Target> target(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(FHIR_BASE)) {
            return Optional.empty();
        }
        String rest = path.substring(FHIR_BASE.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] segments = rest.split("/", -1);
        String type = segments[0];
        if (!Cms0057SearchParameters.SUPPORTED_TYPES.contains(type)) {
            return Optional.empty();
        }
        return switch (segments.length) {
            case 1 -> Optional.of(new Target(type, Interaction.SEARCH));
            case 2 -> segments[1].isEmpty() ? Optional.empty() : Optional.of(new Target(type, Interaction.READ));
            default -> Optional.empty();
        };
    }
}
