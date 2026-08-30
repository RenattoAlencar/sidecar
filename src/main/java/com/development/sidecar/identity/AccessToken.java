package com.development.sidecar.identity;

import java.time.Duration;

public record AccessToken(String accessToken,
                          String tokenType,
                          Duration expiresIn,
                          String scope) {

    public AccessToken {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Token emitido sem valor");
        }
    }

    @Override
    public String toString() {
        return "AccessToken[tokenType=%s, expiresIn=%s, scope=%s]"
                .formatted(tokenType, expiresIn, scope);
    }
}