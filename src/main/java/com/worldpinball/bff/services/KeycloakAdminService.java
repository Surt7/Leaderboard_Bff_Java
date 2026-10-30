package com.worldpinball.bff.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldpinball.bff.dto.RegisterUserResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class KeycloakAdminService implements IKeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${keycloak.admin-url:${KEYCLOAK_ADMIN_URL:http://keycloak:8080}}")
    private String keycloakAdminUrl;

    @Value("${keycloak.realm:${KEYCLOAK_REALM:worldpinball}}")
    private String targetRealm;

    @Value("${keycloak.admin.username:${KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME:${KEYCLOAK_ADMIN:admin}}}")
    private String adminUsername;

    @Value("${keycloak.admin.password:${KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD:${KEYCLOAK_ADMIN_PASSWORD:}}}")
    private String adminPassword;

    public KeycloakAdminService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<RegisterUserResult> registerUser(String username, String email, String password) {
        // 1. Input sanitization & validation
        if (username == null || username.trim().length() < 3) {
            return Mono.just(new RegisterUserResult.ValidationError("Le GamerTag doit comporter au moins 3 caractères."));
        }

        String cleanUsername = username.trim();
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";

        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return Mono.just(new RegisterUserResult.ValidationError("Format d'adresse email invalide."));
        }

        if (password == null || password.length() < 6) {
            return Mono.just(new RegisterUserResult.ValidationError("Le mot de passe doit comporter au moins 6 caractères."));
        }

        // 2. Obtain Admin Access Token from Keycloak
        return getAdminAccessToken()
                .flatMap(adminToken -> {
                    String baseUrl = normalizeBaseUrl(keycloakAdminUrl);
                    String createUserUrl = baseUrl + "/admin/realms/" + targetRealm + "/users";

                    Map<String, Object> credential = Map.of(
                            "type", "password",
                            "value", password,
                            "temporary", false
                    );

                    Map<String, Object> userPayload = Map.of(
                            "username", cleanUsername,
                            "email", cleanEmail,
                            "firstName", cleanUsername,
                            "lastName", "Player",
                            "enabled", true,
                            "emailVerified", true,
                            "credentials", List.of(credential)
                    );

                    return webClient.post()
                            .uri(createUserUrl)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(userPayload)
                            .exchangeToMono(response -> handleCreateUserResponse(response, cleanUsername, cleanEmail, baseUrl, adminToken));
                })
                .onErrorResume(ex -> {
                    log.error("Unexpected error occurred during player registration for {}: {}", cleanUsername, ex.getMessage(), ex);
                    return Mono.just(new RegisterUserResult.Failure("Une erreur réseau est survenue lors de la création du compte."));
                });
    }

    private Mono<RegisterUserResult> handleCreateUserResponse(
            ClientResponse response,
            String cleanUsername,
            String cleanEmail,
            String baseUrl,
            String adminToken
    ) {
        HttpStatus status = HttpStatus.valueOf(response.statusCode().value());

        if (status == HttpStatus.CREATED) {
            URI location = response.headers().asHttpHeaders().getLocation();
            String createdUserId = extractUserIdFromLocation(location);

            log.info("Successfully registered player {} with ID {}", cleanUsername, createdUserId);

            // Assign "Player" role explicitly if needed
            return assignPlayerRole(baseUrl, adminToken, createdUserId)
                    .thenReturn((RegisterUserResult) new RegisterUserResult.Success(createdUserId));
        }

        if (status == HttpStatus.CONFLICT) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(conflictBody -> {
                        log.warn("Keycloak user registration conflict for {} / {}: {}", cleanUsername, cleanEmail, conflictBody);
                        if (conflictBody.toLowerCase().contains("same username")) {
                            return new RegisterUserResult.UsernameConflict("Ce GamerTag est déjà utilisé par un autre joueur.");
                        }
                        if (conflictBody.toLowerCase().contains("same email")) {
                            return new RegisterUserResult.EmailConflict("Cette adresse email est déjà associée à un compte.");
                        }
                        return new RegisterUserResult.UsernameConflict("Un compte avec cet identifiant ou cet email existe déjà.");
                    });
        }

        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(errContent -> {
                    log.error("Keycloak registration failed with status {}: {}", status, errContent);
                    return new RegisterUserResult.Failure("Erreur d'inscription (" + status.value() + ").");
                });
    }

    private Mono<Void> assignPlayerRole(String baseUrl, String adminToken, String userId) {
        String roleUrl = baseUrl + "/admin/realms/" + targetRealm + "/roles/Player";
        String mappingUrl = baseUrl + "/admin/realms/" + targetRealm + "/users/" + userId + "/role-mappings/realm";

        return webClient.get()
                .uri(roleUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(roleNode -> webClient.post()
                        .uri(mappingUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(List.of(roleNode))
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .onErrorResume(err -> {
                    log.warn("Could not explicitly assign Player role to user {}: {}", userId, err.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<String> getAdminAccessToken() {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.error("Keycloak admin password is not configured (KEYCLOAK_ADMIN_PASSWORD is empty).");
            return Mono.error(new IllegalStateException("Le mot de passe d'administration Keycloak n'est pas configuré."));
        }

        String baseUrl = normalizeBaseUrl(keycloakAdminUrl);
        String tokenUrl = baseUrl + "/realms/master/protocol/openid-connect/token";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("client_id", "admin-cli");
        formData.add("username", adminUsername);
        formData.add("password", adminPassword);

        return webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.path("access_token").asText())
                .filter(token -> !token.isBlank())
                .switchIfEmpty(Mono.error(new IllegalStateException("Impossible d'authentifier la requête d'administration auprès de Keycloak.")));
    }

    private String extractUserIdFromLocation(URI location) {
        if (location == null) {
            return java.util.UUID.randomUUID().toString();
        }
        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        return (lastSlash >= 0 && lastSlash < path.length() - 1)
                ? path.substring(lastSlash + 1)
                : location.toString();
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://keycloak:8080";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
