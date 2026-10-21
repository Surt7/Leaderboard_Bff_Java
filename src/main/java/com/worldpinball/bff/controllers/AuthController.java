package com.worldpinball.bff.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
public class AuthController {

    public record UserInfoDto(String subject, String username, List<String> roles) {}

    @GetMapping("/alive")
    public Mono<ResponseEntity<String>> alive() {
        return Mono.just(ResponseEntity.ok("Healthy"));
    }

    @GetMapping("/api/auth/login")
    public Mono<Void> login(@RequestParam(value = "returnUrl", required = false) String returnUrl,
                            ServerWebExchange exchange) {
        String redirectTarget = "/oauth2/authorization/keycloak";
        if (returnUrl != null && !returnUrl.isBlank()) {
            exchange.getSession().subscribe(session -> session.getAttributes().put("returnUrl", returnUrl));
        }
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(redirectTarget));
        return exchange.getResponse().setComplete();
    }

    @GetMapping("/api/auth/user")
    public Mono<ResponseEntity<UserInfoDto>> getUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        String subject = authentication.getName();
        String username = authentication.getName();
        List<String> roles = new ArrayList<>(authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList());

        if (authentication.getPrincipal() instanceof OAuth2User oauth2User) {
            Object sub = oauth2User.getAttribute("sub");
            if (sub != null) {
                subject = sub.toString();
            }
            Object preferred = oauth2User.getAttribute("preferred_username");
            if (preferred != null) {
                username = preferred.toString();
            }

            // Extract Keycloak realm roles mapped to "roles" claim or "realm_access.roles"
            Object rolesClaim = oauth2User.getAttribute("roles");
            if (rolesClaim instanceof Collection<?> col) {
                for (Object r : col) {
                    if (r != null && !roles.contains(r.toString())) {
                        roles.add(r.toString());
                    }
                }
            }

            Object realmAccess = oauth2User.getAttribute("realm_access");
            if (realmAccess instanceof Map<?, ?> map) {
                Object rRoles = map.get("roles");
                if (rRoles instanceof Collection<?> col) {
                    for (Object r : col) {
                        if (r != null && !roles.contains(r.toString())) {
                            roles.add(r.toString());
                        }
                    }
                }
            }
        }

        return Mono.just(ResponseEntity.ok(new UserInfoDto(subject, username, roles)));
    }
}
