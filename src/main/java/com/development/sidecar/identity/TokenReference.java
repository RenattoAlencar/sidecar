package com.development.sidecar.identity;


public record TokenReference(String tokenRef) {

    public TokenReference {
        if (tokenRef == null || tokenRef.isBlank()) {
            throw new IllegalArgumentException("Referência de token sem valor");
        }
    }
}