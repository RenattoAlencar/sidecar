package com.development.sidecar.contract;

/**
 * Referência devolvida ao canal ao fim da autorização.
 */
public record TokenRefResponse(String status, String tokenRef) {

    private static final String AUTHORIZED = "authorized";

    public static TokenRefResponse of(String tokenRef) {
        return new TokenRefResponse(AUTHORIZED, tokenRef);
    }
}