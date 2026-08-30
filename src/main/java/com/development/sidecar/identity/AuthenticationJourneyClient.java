package com.development.sidecar.identity;

public interface AuthenticationJourneyClient {

    JourneyOutcome start(String journey, String channelToken, String otpCode, byte[] payload);

    JourneyOutcome advance(String sessionId, String response);

    class JourneyUnavailableException extends RuntimeException {

        public JourneyUnavailableException(String message) {
            super(message);
        }

        public JourneyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}