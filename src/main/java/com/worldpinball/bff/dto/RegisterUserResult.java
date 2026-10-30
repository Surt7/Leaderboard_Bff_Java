package com.worldpinball.bff.dto;

public sealed interface RegisterUserResult {
    record Success(String userId) implements RegisterUserResult {}
    record UsernameConflict(String message) implements RegisterUserResult {}
    record EmailConflict(String message) implements RegisterUserResult {}
    record ValidationError(String message) implements RegisterUserResult {}
    record Failure(String message) implements RegisterUserResult {}
}
