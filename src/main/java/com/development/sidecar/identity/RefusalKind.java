package com.development.sidecar.identity;

public enum RefusalKind {


    CODE_REQUIRED("code_required"),


    CODE_INVALID("code_invalid"),

    FACTOR_REQUIRED("factor_required"),


    PAYLOAD_INVALID("payload_invalid"),


    DENIED("denied");

    private static final String CODE_MISSING = "002";
    private static final String CODE_WRONG = "003";
    private static final String PAYLOAD_MISMATCH = "014";
    private static final String PAYLOAD_FAILURE = "015";

    private final String reason;

    RefusalKind(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    public boolean isRequestProblem() {
        return this == PAYLOAD_INVALID;
    }

    public static RefusalKind of(String code) {

        if (code == null || code.isBlank()) {
            return DENIED;
        }

        return switch (code.trim()) {
            case CODE_MISSING -> CODE_REQUIRED;
            case CODE_WRONG -> CODE_INVALID;
            case PAYLOAD_MISMATCH, PAYLOAD_FAILURE -> PAYLOAD_INVALID;
            default -> DENIED;
        };
    }
}