package com.development.sidecar.contract;


import com.fasterxml.jackson.annotation.JsonInclude;

public record ChallengeResponse(boolean authorizationRequired,
                                String sessionId,
                                Challenge challenge) {


    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Challenge(String type, String provider, String target, Long retryAfter) {
    }


    public static ChallengeResponse of(String sessionId, String type, String provider) {
        return new ChallengeResponse(true, sessionId,
                new Challenge(type, provider, null, null));
    }


    public static ChallengeResponse handoff(String sessionId, String target, Long retryAfter) {
        return new ChallengeResponse(true, sessionId,
                new Challenge(HANDOFF_TYPE, null, target, retryAfter));
    }

    private static final String HANDOFF_TYPE = "DEEPLINK";
}