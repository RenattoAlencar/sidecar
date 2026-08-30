package com.development.sidecar.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JourneyStep(String authId,
                          List<Map<String, Object>> callbacks,
                          String tokenId) {

    public JourneyStep {
        callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }

    public boolean isComplete() {
        return tokenId != null && !tokenId.isBlank();
    }

    public boolean hasChallenge() {
        return !callbacks.isEmpty() && authId != null && !authId.isBlank();
    }

    @Override
    public String toString() {
        return "JourneyStep[complete=%s, callbacks=%d]".formatted(isComplete(), callbacks.size());
    }
}