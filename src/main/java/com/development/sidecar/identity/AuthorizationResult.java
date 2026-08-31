package com.development.sidecar.identity;


public record AuthorizationResult(Type type,
                                  JourneyStep step,
                                  TokenReference reference,
                                  AccessToken token,
                                  RefusalKind refusal) {

    public enum Type {
        CHALLENGE,
        AUTHORIZED,
        RESOLVED,
        DENIED,
        EXPIRED,
        SESSION_REQUIRED,
        AUTHORIZATION_REQUIRED,
        UNAVAILABLE,
        BAD_REQUEST
    }

    public static AuthorizationResult challenge(JourneyStep step) {
        return new AuthorizationResult(Type.CHALLENGE, step, null, null, null);
    }

    public static AuthorizationResult authorized(TokenReference reference) {
        return new AuthorizationResult(Type.AUTHORIZED, null, reference, null, null);
    }

    public static AuthorizationResult resolved(AccessToken token) {
        return new AuthorizationResult(Type.RESOLVED, null, null, token, null);
    }

    public static AuthorizationResult denied(RefusalKind refusal) {
        return new AuthorizationResult(Type.DENIED, null, null, null,
                refusal == null ? RefusalKind.UNKNOWN : refusal);
    }

    public static AuthorizationResult expired() {
        return new AuthorizationResult(Type.EXPIRED, null, null, null, null);
    }

    public static AuthorizationResult sessionRequired() {
        return new AuthorizationResult(Type.SESSION_REQUIRED, null, null, null, null);
    }

    public static AuthorizationResult authorizationRequired() {
        return new AuthorizationResult(Type.AUTHORIZATION_REQUIRED, null, null, null, null);
    }

    public static AuthorizationResult unavailable() {
        return new AuthorizationResult(Type.UNAVAILABLE, null, null, null, null);
    }

    public static AuthorizationResult badRequest() {
        return new AuthorizationResult(Type.BAD_REQUEST, null, null, null, null);
    }

    @Override
    public String toString() {
        return "AuthorizationResult[type=%s, refusal=%s]".formatted(type, refusal);
    }
}
