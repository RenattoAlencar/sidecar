package com.development.sidecar.identity;

public enum RefusalKind {

    RETRY,
    INVALID_REQUEST,
    UNKNOWN;

    private static final String CODE_MISSING = "002";
    private static final String CODE_INVALID = "003";
    private static final String CODE_INTERNAL = "006";
    private static final String PAYLOAD_MISMATCH = "014";
    private static final String PAYLOAD_FAILURE = "015";

    public static RefusalKind of(String reason) {

        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }

        return switch (reason.trim()) {
            case CODE_MISSING, CODE_INVALID, CODE_INTERNAL -> RETRY;
            case PAYLOAD_MISMATCH, PAYLOAD_FAILURE -> INVALID_REQUEST;
            default -> UNKNOWN;
        };
    }
}