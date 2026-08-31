package com.development.sidecar.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JourneyRefusal {

    private static final Logger log = LoggerFactory.getLogger(JourneyRefusal.class);

    private static final int MAX_REASON_LENGTH = 64;
    private static final int MAX_LOGGED_LENGTH = 200;

    private JourneyRefusal() {
    }

    static String describe(String body) {

        if (body == null || body.isBlank()) {
            return "sem corpo";
        }
        try {
            var tree = JsonSupport.readTree(body);
            var detail = tree.get("detail");

            if (detail != null && !detail.isNull()) {
                var code = detail.get("errorCode");

                if (code != null && !code.isNull()) {
                    return sanitize(code.asString(), MAX_REASON_LENGTH);
                }
            }

            var message = tree.get("message");

            if (message != null && !message.isNull()) {
                return sanitize(message.asString(), MAX_REASON_LENGTH);
            }

            log.debug("Recusa sem código e sem mensagem");
            return "sem detalhe";

        } catch (Exception e) {
            log.debug("Recusa ilegível: {}", sanitize(body, MAX_LOGGED_LENGTH));
            return "corpo ilegível";
        }
    }

    private static String sanitize(String value, int maxLength) {

        if (value == null || value.isBlank()) {
            return "sem detalhe";
        }
        String trimmed = value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;

        StringBuilder clean = new StringBuilder(trimmed.length());

        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);

            clean.append(current < 0x20 || current == 0x7F ? '.' : current);
        }
        return clean.toString();
    }
}