package com.development.sidecar.route;

import com.development.sidecar.config.ProxyProperties.InterceptRule;

public record RouteDecision(Outcome outcome, InterceptRule rule, String rejectionReason) {

    public enum Outcome {

        INTERCEPT,
        PASSTHROUGH,
        REJECT

    }

    public static RouteDecision intercept(InterceptRule rule) {
        return new RouteDecision(Outcome.INTERCEPT, rule, null);
    }

    public static RouteDecision passthrough() {
        return new RouteDecision(Outcome.PASSTHROUGH, null, null);
    }

    public static RouteDecision reject(String reason) {
        return new RouteDecision(Outcome.REJECT, null, reason);
    }

    public String metricTag() {
        return rule == null ? "sem-regra" : rule.name();
    }
}