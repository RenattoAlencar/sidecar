package com.development.sidecar.identity;

public interface TokenIssuer {

    AccessToken issue(String sessionId);

    class TokenIssuanceException extends RuntimeException {

        public TokenIssuanceException(String message) {
            super(message);
        }

        public TokenIssuanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}