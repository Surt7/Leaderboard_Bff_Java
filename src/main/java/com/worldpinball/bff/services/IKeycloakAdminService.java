package com.worldpinball.bff.services;

import com.worldpinball.bff.dto.RegisterUserResult;
import reactor.core.publisher.Mono;

public interface IKeycloakAdminService {
    Mono<RegisterUserResult> registerUser(String username, String email, String password);
}
