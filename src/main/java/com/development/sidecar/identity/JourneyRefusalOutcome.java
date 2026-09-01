package com.development.sidecar.identity;


final class JourneyRefusalOutcome {


    private static final String SESSION_INVALID = "001";


    private static final String PROVIDER_FAILURE = "006";

    private JourneyRefusalOutcome() {
    }

    static boolean isSessionProblem(String code) {
        return SESSION_INVALID.equals(trimmed(code));
    }

    static boolean isProviderFailure(String code) {
        return PROVIDER_FAILURE.equals(trimmed(code));
    }

    private static String trimmed(String code) {
        return code == null ? "" : code.trim();
    }
}