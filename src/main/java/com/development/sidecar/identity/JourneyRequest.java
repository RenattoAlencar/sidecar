package com.development.sidecar.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JourneyRequest {

    private static final String AUTH_ID_FIELD = "authId";
    private static final String CALLBACKS_FIELD = "callbacks";
    private static final String TYPE_FIELD = "type";
    private static final String OUTPUT_FIELD = "output";
    private static final String INPUT_FIELD = "input";
    private static final String NAME_FIELD = "name";
    private static final String VALUE_FIELD = "value";

    private static final String NAME_CALLBACK = "NameCallback";
    private static final String PROMPT_NAME = "prompt";
    private static final String DEFAULT_INPUT_NAME = "IDToken1";

    private JourneyRequest() {
    }

    static Map<String, Object> answering(JourneyStep step, String value) {

        List<Map<String, Object>> callbacks = step.callbacks();

        if (callbacks.isEmpty()) {
            throw new JourneyRequestException("Passo sem callback a responder");
        }

        List<Map<String, Object>> answered = new ArrayList<>(callbacks.size());

        for (int index = 0; index < callbacks.size(); index++) {
            Map<String, Object> callback = callbacks.get(index);

            answered.add(index == 0 ? withValue(callback, value) : copyOf(callback));
        }

        return body(step.authId(), answered);
    }

    static Map<String, Object> answering(String authId, String prompt, String value) {

        if (authId == null || authId.isBlank()) {
            throw new JourneyRequestException("Continuação sem identificador de jornada");
        }

        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put(TYPE_FIELD, NAME_CALLBACK);
        callback.put(OUTPUT_FIELD, List.of(field(PROMPT_NAME, prompt)));
        callback.put(INPUT_FIELD, List.of(field(DEFAULT_INPUT_NAME, value)));

        return body(authId, List.of(callback));
    }

    private static Map<String, Object> body(String authId,
                                            List<Map<String, Object>> callbacks) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(AUTH_ID_FIELD, authId);
        body.put(CALLBACKS_FIELD, callbacks);
        return body;
    }

    private static Map<String, Object> field(String name, String value) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put(NAME_FIELD, name);
        field.put(VALUE_FIELD, value);
        return field;
    }

    private static Map<String, Object> withValue(Map<String, Object> callback, String value) {

        Map<String, Object> copy = copyOf(callback);
        Object input = copy.get(INPUT_FIELD);

        if (!(input instanceof List<?> entries) || entries.isEmpty()) {
            throw new JourneyRequestException("Callback sem entrada onde escrever a resposta");
        }

        List<Object> inputCopy = new ArrayList<>(entries.size());

        for (int index = 0; index < entries.size(); index++) {
            Object entry = entries.get(index);

            if (index == 0 && entry instanceof Map<?, ?> original) {
                Map<String, Object> fieldCopy = new LinkedHashMap<>();
                original.forEach((key, existing) -> fieldCopy.put(String.valueOf(key), existing));
                fieldCopy.put(VALUE_FIELD, value);
                inputCopy.add(fieldCopy);
            } else {
                inputCopy.add(entry);
            }
        }

        copy.put(INPUT_FIELD, inputCopy);
        return copy;
    }

    private static Map<String, Object> copyOf(Map<String, Object> callback) {
        return new LinkedHashMap<>(callback);
    }

    static class JourneyRequestException extends RuntimeException {

        JourneyRequestException(String message) {
            super(message);
        }
    }
}