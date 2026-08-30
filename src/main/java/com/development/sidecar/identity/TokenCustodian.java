package com.development.sidecar.identity;


public interface TokenCustodian {


    TokenReference store(AccessToken token);

    AccessToken retrieve(String tokenRef);

    class TokenNotFoundException extends RuntimeException {

        public TokenNotFoundException(String message) {
            super(message);
        }
    }

    class TokenCustodyException extends RuntimeException {

        public TokenCustodyException(String message) {
            super(message);
        }

        public TokenCustodyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}