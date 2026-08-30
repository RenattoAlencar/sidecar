package com.development.sidecar.identity;

import java.util.List;
import java.util.Map;

final class JourneyRequest {

    private static final String NAME_CALLBACK = "NameCallback";
    private static final String PROMPT_FIELD = "prompt";
    private static final String INPUT_FIELD = "IDToken1";

    private JourneyRequest() {
    }

    static Map<String, Object> answering(String authId, String value) {
        return Map.of(
                "authId", authId,
                "callbacks", List.of(Map.of(
                        "type", NAME_CALLBACK,
                        "input", List.of(Map.of(
                                "name", INPUT_FIELD,
                                "value", value))))
        );
    }
}