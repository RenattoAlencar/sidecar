package com.development.sidecar.observability;

import com.development.sidecar.identity.AuthorizationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class SidecarMetrics {

    private static final String AUTHORIZATION_COUNTER = "sidecar.authorization";
    private static final String FORWARD_TIMER = "sidecar.forward";
    private static final String IDENTITY_TIMER = "sidecar.identity";
    private static final String CUSTODY_TIMER = "sidecar.custody";

    private static final String RULE_TAG = "rule";
    private static final String OUTCOME_TAG = "outcome";
    private static final String STEP_TAG = "step";
    private static final String RESULT_TAG = "result";

    private final MeterRegistry registry;

    public SidecarMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void authorization(String rule, AuthorizationResult.Type outcome) {
        registry.counter(AUTHORIZATION_COUNTER,
                RULE_TAG, rule,
                OUTCOME_TAG, outcome.name().toLowerCase()).increment();
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void forwarded(Timer.Sample sample, String rule) {
        sample.stop(registry.timer(FORWARD_TIMER, RULE_TAG, rule));
    }

    public void identity(Timer.Sample sample, String step, String result) {
        sample.stop(registry.timer(IDENTITY_TIMER, STEP_TAG, step, RESULT_TAG, result));
    }

    public void custody(Timer.Sample sample, String operation, String result) {
        sample.stop(registry.timer(CUSTODY_TIMER, STEP_TAG, operation, RESULT_TAG, result));
    }
}