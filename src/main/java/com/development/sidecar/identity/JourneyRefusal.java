package com.development.sidecar.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


final class JourneyRefusal {

    private static final Logger log = LoggerFactory.getLogger(JourneyRefusal.class);

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
                    return code.asString();
                }
            }
            log.debug("Recusa sem código no detalhe");
            return "sem código";

        } catch (Exception e) {
            log.debug("Recusa ilegível: {}", truncate(body));
            return "corpo ilegível";
        }
    }

    private static String truncate(String body) {
        return body.length() <= MAX_LOGGED_LENGTH
                ? body
                : body.substring(0, MAX_LOGGED_LENGTH) + "…";
    }
}