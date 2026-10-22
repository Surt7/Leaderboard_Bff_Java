package com.worldpinball.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebFluxSecurity
public class BffSecurityConfiguration {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            Optional<ReactiveClientRegistrationRepository> clientRegistrationRepository) {
        var spec = http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(headers -> headers
                .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                .contentTypeOptions(Customizer.withDefaults())
                .hsts(hsts -> hsts
                    .maxAge(Duration.ofDays(730))
                    .includeSubdomains(true)
                    .preload(true)
                )
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; frame-ancestors 'none';")
                )
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                .permissionsPolicy(permissions -> permissions
                    .policy("accelerometer=(), camera=(), geolocation=(), microphone=()")
                )
            )
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/alive", "/health", "/actuator/**").permitAll()
                .pathMatchers("/api/auth/**").permitAll()
                .pathMatchers("/hubs/**", "/ws/**").permitAll()
                .anyExchange().permitAll()
            );

        clientRegistrationRepository.ifPresent(repo -> {
            var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(repo);
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            spec.oauth2Login(oauth2 -> oauth2.authorizationRequestResolver(resolver));
        });

        return spec.build();
    }

    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName("__Host-worldpinball-session");
        resolver.addCookieInitializer(builder -> {
            builder.path("/");
            builder.secure(true);
            builder.httpOnly(true);
            builder.sameSite("Lax");
            builder.maxAge(Duration.ofHours(8));
        });
        return resolver;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("https://*.worldpinball.local", "http://*.worldpinball.local", "https://worldpinball.local", "http://worldpinball.local", "http://localhost:*", "https://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
