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

    private static final String TYPE_FIELD = "type";
    private static final String OUTPUT_FIELD = "output";
    private static final String INPUT_FIELD = "input";
    private static final String NAME_FIELD = "name";
    private static final String VALUE_FIELD = "value";
    private static final String PROMPT_NAME = "prompt";

    private static final String POLLING_CALLBACK = "PollingWaitCallback";
    private static final String WAIT_TIME_NAME = "waitTime";
    private static final String MESSAGE_NAME = "message";

    private static final String TYPE_SEPARATOR = ":";
    private static final String UNKNOWN_TYPE = "UNKNOWN";

    private static final int MAX_TYPE_LENGTH = 32;
    private static final int MAX_TARGET_LENGTH = 2048;

    private ChallengeMapper() {
    }

    public static ChallengeResponse toChallenge(JourneyStep step) {

        List<Map<String, Object>> callbacks = step.callbacks();

        if (isHandoff(callbacks)) {
            return handoff(step, callbacks.get(0));
        }

        String descriptor = descriptorOf(callbacks);

        int separator = descriptor.indexOf(TYPE_SEPARATOR);

        String type = separator > 0 ? descriptor.substring(0, separator) : descriptor;
        String provider = separator > 0 ? descriptor.substring(separator + 1) : null;

        return ChallengeResponse.of(step.authId(), safe(type), safe(provider));
    }

    /**
     * O desafio é cumprido fora do canal.
     * <p>
     * O provedor não pede uma resposta: pede que se aguarde enquanto outra parte
     * resolve. O que ele emite é um endereço a abrir e um tempo a esperar — e o
     * canal reapresenta a sessão depois disso, até haver desfecho.
     */
    private static boolean isHandoff(List<Map<String, Object>> callbacks) {

        if (callbacks.isEmpty()) {
            return false;
        }
        return POLLING_CALLBACK.equals(callbacks.get(0).get(TYPE_FIELD));
    }

    private static ChallengeResponse handoff(JourneyStep step, Map<String, Object> callback) {

        Object output = callback.get(OUTPUT_FIELD);

        String target = target(namedValue(output, MESSAGE_NAME));
        Long retryAfter = milliseconds(namedValue(output, WAIT_TIME_NAME));

        return ChallengeResponse.handoff(step.authId(), target, retryAfter);
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

    /**
     * Limpa o que o provedor nomeou antes de atravessar para o canal.
     * <p>
     * O valor vem de fora e vai para a resposta. Um rótulo é um identificador
     * curto: o que não couber nessa forma não é rótulo, e não deve atravessar
     * como se fosse.
     */
    private static String safe(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.length() > MAX_TYPE_LENGTH
                ? value.substring(0, MAX_TYPE_LENGTH)
                : value;

        String clean = trimmed.replaceAll("[^A-Za-z0-9_.-]", "");

        return clean.isBlank() ? UNKNOWN_TYPE : clean;
    }

    /**
     * Limita o endereço, sem alterá-lo.
     * <p>
     * Diferente do rótulo, aqui não há o que limpar: o endereço precisa chegar ao
     * canal como o provedor o escreveu, ou não abre. O que se pode fazer é
     * recusar o que não tem forma de endereço.
     */
    private static String target(String value) {

        if (value == null || value.isBlank() || value.length() > MAX_TARGET_LENGTH) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);

            if (current < 0x20 || current == 0x7F) {
                return null;
            }
        }
        return value;
    }

    private static Long milliseconds(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());

            return parsed > 0 ? parsed : null;

        } catch (NumberFormatException e) {
            return null;
        }
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