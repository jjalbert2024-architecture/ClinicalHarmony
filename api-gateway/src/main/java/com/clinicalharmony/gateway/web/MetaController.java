package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.config.GatewayProperties;
import com.clinicalharmony.gateway.downstream.ServiceHealthService;
import com.clinicalharmony.gateway.read.SummaryQueries;
import com.clinicalharmony.gateway.security.Roles;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The dashboard shell: login config (public), who am I, layer counts, and downstream health. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetaController {

    public record Me(String username, String name, String subject, String client, List<String> roles, boolean canAct,
                     Instant tokenExpiresAt) {
    }

    private final GatewayProperties properties;
    private final SummaryQueries summary;
    private final ServiceHealthService health;

    public MetaController(GatewayProperties properties, SummaryQueries summary, ServiceHealthService health) {
        this.properties = properties;
        this.summary = summary;
        this.health = health;
    }

    /** Public: everything the SPA needs to start an Authorization Code + PKCE login. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        String issuer = properties.ui().issuer();
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("issuer", issuer);
        auth.put("clientId", properties.ui().clientId());
        auth.put("flow", "authorization_code");
        auth.put("pkce", "S256");
        auth.put("authorizationEndpoint", issuer + "/protocol/openid-connect/auth");
        auth.put("tokenEndpoint", issuer + "/protocol/openid-connect/token");
        auth.put("logoutEndpoint", issuer + "/protocol/openid-connect/logout");
        auth.put("scope", "openid");
        auth.put("roles", Map.of("viewer", Roles.VIEWER, "steward", Roles.STEWARD));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "v1");
        body.put("auth", auth);
        return body;
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        List<String> dashboardRoles = roles == null ? List.of()
                : roles.stream().filter(r -> r.equals(Roles.VIEWER) || r.equals(Roles.STEWARD)).sorted().toList();
        return new Me(Reviewer.of(jwt), jwt.getClaimAsString("name"), jwt.getSubject(), jwt.getClaimAsString("azp"),
                dashboardRoles, dashboardRoles.contains(Roles.STEWARD), jwt.getExpiresAt());
    }

    @GetMapping("/summary")
    public SummaryQueries.Summary summary() {
        return summary.summary();
    }

    @GetMapping("/services/health")
    public ServiceHealthService.HealthReport servicesHealth() {
        return health.check();
    }
}
