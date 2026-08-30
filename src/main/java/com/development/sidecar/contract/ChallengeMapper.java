package com.development.sidecar.contract;

import com.development.sidecar.identity.JourneyStep;

import java.util.List;
import java.util.Map;

/**
 * Traduz o desafio emitido pelo provedor para o contrato do canal.
 * <p>
 * É o único ponto que conhece os dois lados. O que o provedor emite não
 * atravessa: nem a estrutura de callback, nem os nomes de campo dele.
 */
public final class ChallengeMapper {

    private static final String OUTPUT_FIELD = "output";
    private static final String INPUT_FIELD = "input";
    private static final String NAME_FIELD = "name";
    private static final String VALUE_FIELD = "value";
    private static final String PROMPT_NAME = "prompt";

    private static final String TYPE_SEPARATOR = ":";
    private static final String UNKNOWN_TYPE = "UNKNOWN";

    private ChallengeMapper() {
    }

    public static ChallengeResponse toChallenge(JourneyStep step) {

        String descriptor = descriptorOf(step.callbacks());

        int separator = descriptor.indexOf(TYPE_SEPARATOR);

        String type = separator > 0 ? descriptor.substring(0, separator) : descriptor;
        String provider = separator > 0 ? descriptor.substring(separator + 1) : null;

        return ChallengeResponse.of(step.authId(), type, provider);
    }

    /**
     * Lê o que o provedor indicou como desafio.
     * <p>
     * Prefere o valor sugerido na entrada, que é onde o provedor nomeia o
     * desafio; recorre ao rótulo quando não houver.
     */
    private static String descriptorOf(List<Map<String, Object>> callbacks) {

        if (callbacks.isEmpty()) {
            return UNKNOWN_TYPE;
        }
        Map<String, Object> callback = callbacks.get(0);

        String suggested = firstValue(callback.get(INPUT_FIELD));

        if (suggested != null && !suggested.isBlank()) {
            return suggested;
        }
        String prompt = namedValue(callback.get(OUTPUT_FIELD), PROMPT_NAME);

        return prompt == null || prompt.isBlank() ? UNKNOWN_TYPE : prompt;
    }

    private static String firstValue(Object entries) {

        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.get(0) instanceof Map<?, ?> entry
                ? asText(entry.get(VALUE_FIELD))
                : null;
    }

    private static String namedValue(Object fields, String name) {

        if (!(fields instanceof List<?> list)) {
            return null;
        }
        for (Object field : list) {
            if (field instanceof Map<?, ?> entry && name.equals(entry.get(NAME_FIELD))) {
                return asText(entry.get(VALUE_FIELD));
            }
        }
        return null;
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}