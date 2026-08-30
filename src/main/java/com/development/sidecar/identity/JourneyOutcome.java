package com.development.sidecar.identity;

import java.util.List;
import java.util.Map;

public record JourneyOutcome(Type type, JourneyStep step, String reason) {

    public enum Type {
        CHALLENGE,
        COMPLETED,
        DENIED,
        EXPIRED

    }

    public static JourneyOutcome challenge(JourneyStep step) {
        return new JourneyOutcome(Type.CHALLENGE, step, null);
    }

    public static JourneyOutcome completed(JourneyStep step) {
        return new JourneyOutcome(Type.COMPLETED, step, null);
    }

    public static JourneyOutcome denied(String reason) {
        return new JourneyOutcome(Type.DENIED, null, reason);
    }

    public static JourneyOutcome expired() {
        return new JourneyOutcome(Type.EXPIRED, null, null);
    }

    public List<Map<String, Object>> callbacks() {
        return step == null ? List.of() : step.callbacks();
    }

    @Override
    public String toString() {
        return "JourneyOutcome[type=%s, reason=%s]".formatted(type, reason);
    }
}