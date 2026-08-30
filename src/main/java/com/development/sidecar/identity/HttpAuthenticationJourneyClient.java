package com.development.sidecar.identity;

import com.development.sidecar.config.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class HttpAuthenticationJourneyClient implements AuthenticationJourneyClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAuthenticationJourneyClient.class);

    private static final String AUTHENTICATE_PATH = "/json/realms/%s/authenticate";

    private static final String API_VERSION_HEADER = "Accept-API-Version";
    private static final String API_VERSION = "resource=2.1";

    private static final String INDEX_TYPE_PARAM = "authIndexType";
    private static final String INDEX_VALUE_PARAM = "authIndexValue";

    private static final String PROMPT_NAME = "prompt";
    private static final String OUTPUT_FIELD = "output";
    private static final String NAME_FIELD = "name";
    private static final String VALUE_FIELD = "value";

    private static final String PAYLOAD_PROMPT = "PAYLOAD_REQUIRED";
    private static final String CHALLENGE_PROMPT = "CHALLENGE_REQUIRED";

    private final RestClient restClient;
    private final IdentityProperties properties;

    public HttpAuthenticationJourneyClient(RestClient restClient,
                                           IdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public JourneyOutcome start(String journey,
                                String channelToken,
                                String otpCode,
                                byte[] payload) {

        if (journey == null || journey.isBlank()) {
            throw new JourneyUnavailableException("Jornada não informada ao iniciar");
        }
        if (channelToken == null || channelToken.isBlank()) {
            throw new JourneyUnavailableException("Token do canal ausente ao iniciar a jornada");
        }

        log.info("Iniciando a jornada '{}' no realm '{}'", journey, properties.realm());

        JourneyOutcome opening = open(journey, channelToken, otpCode);

        return awaitingPayload(opening)
                ? submitPayload(opening.step(), payload)
                : opening;
    }

    @Override
    public JourneyOutcome advance(String sessionId, String response) {

        if (sessionId == null || sessionId.isBlank()) {
            throw new JourneyUnavailableException("Jornada sem identificador ao continuar");
        }

        log.info("Continuando a jornada: respondendo o desafio");

        try {
            return post("continuação", null,
                    JourneyRequest.answering(sessionId, CHALLENGE_PROMPT, response));

        } catch (JourneyRequest.JourneyRequestException e) {
            throw new JourneyUnavailableException("Não foi possível montar a continuação", e);
        }
    }

    private JourneyOutcome open(String journey, String channelToken, String otpCode) {

        return post("início", journey, request -> {
            request.header(properties.channelTokenHeader(), channelToken);

            boolean headerConfigured = !properties.authenticatorCodeHeader().isBlank();
            boolean codePresent = otpCode != null && !otpCode.isBlank();

            if (headerConfigured && codePresent) {
                log.debug("Código de autenticador apresentado ao provedor");
                request.header(properties.authenticatorCodeHeader(), otpCode);
            }
            return Map.of();
        });
    }

    private JourneyOutcome submitPayload(JourneyStep step, byte[] payload) {

        if (payload == null || payload.length == 0) {
            throw new JourneyUnavailableException(
                    "Jornada pediu o corpo da transação e não havia corpo a apresentar");
        }

        log.debug("Apresentando o corpo da transação à jornada");

        String body = new String(payload, StandardCharsets.UTF_8);

        try {
            return post("corpo da transação", null, JourneyRequest.answering(step, body));

        } catch (JourneyRequest.JourneyRequestException e) {
            throw new JourneyUnavailableException(
                    "Não foi possível responder ao passo do provedor", e);
        }
    }

    private boolean awaitingPayload(JourneyOutcome outcome) {
        if (outcome.type() != JourneyOutcome.Type.CHALLENGE || outcome.step() == null) {
            return false;
        }
        return PAYLOAD_PROMPT.equals(promptOf(outcome.callbacks()));
    }

    private static String promptOf(List<Map<String, Object>> callbacks) {
        if (callbacks == null || callbacks.size() != 1) {
            return null;
        }
        Object output = callbacks.get(0).get(OUTPUT_FIELD);

        if (!(output instanceof List<?> fields)) {
            return null;
        }
        for (Object field : fields) {
            if (field instanceof Map<?, ?> entry && PROMPT_NAME.equals(entry.get(NAME_FIELD))) {
                Object value = entry.get(VALUE_FIELD);
                return value == null ? null : value.toString();
            }
        }
        return null;
    }

    private JourneyOutcome post(String stepName, String journey, RequestCustomizer customizer) {
        try {
            var request = restClient.post()
                    .uri(uriBuilder -> {
                        uriBuilder.path(AUTHENTICATE_PATH.formatted(properties.realm()));
                        if (journey != null) {
                            uriBuilder
                                    .queryParam(INDEX_TYPE_PARAM, properties.journeyType())
                                    .queryParam(INDEX_VALUE_PARAM, journey);
                        }
                        return uriBuilder.build();
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(API_VERSION_HEADER, API_VERSION);

            Object body = customizer.customize(request);

            String responseBody = request.body(body).retrieve().body(String.class);

            return translate(stepName, HttpStatus.OK.value(), responseBody);

        } catch (RestClientResponseException e) {
            return translate(stepName, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8));

        } catch (RestClientException e) {
            throw new JourneyUnavailableException(
                    "Falha ao contatar o provedor de identidade no passo de " + stepName, e);
        }
    }

    private JourneyOutcome post(String stepName, String journey, Object body) {
        return post(stepName, journey, request -> body);
    }

    private JourneyOutcome translate(String stepName, int status, String body) {

        if (status == HttpStatus.REQUEST_TIMEOUT.value()) {
            log.debug("Sessão da jornada expirada no passo de {}", stepName);
            return JourneyOutcome.expired();
        }

        if (status == HttpStatus.UNAUTHORIZED.value()) {
            String reason = JourneyRefusal.describe(body);
            log.info("Jornada recusada no passo de {}: {}", stepName, reason);
            return JourneyOutcome.denied(reason);
        }

        if (status != HttpStatus.OK.value()) {
            log.warn("Status inesperado do provedor no passo de {}: {}", stepName, status);
            log.debug("Corpo da resposta inesperada: {}", body);

            throw new JourneyUnavailableException(
                    "Provedor de identidade respondeu status " + status
                            + " no passo de " + stepName);
        }

        JourneyStep step = readStep(stepName, body);

        if (step.isComplete()) {
            log.info("Jornada concluída no passo de {}", stepName);
            return JourneyOutcome.completed(step);
        }

        if (step.hasChallenge()) {
            log.info("Passo de {} recebeu desafio", stepName);
            return JourneyOutcome.challenge(step);
        }

        log.warn("Jornada encerrou sem sessão e sem desafio no passo de {}", stepName);
        return JourneyOutcome.denied("jornada encerrada sem desfecho");
    }

    private JourneyStep readStep(String stepName, String body) {
        if (body == null || body.isBlank()) {
            throw new JourneyUnavailableException(
                    "Provedor devolveu corpo vazio no passo de " + stepName);
        }
        try {
            return JsonSupport.read(body, JourneyStep.class);
        } catch (Exception e) {
            throw new JourneyUnavailableException(
                    "Resposta do provedor ilegível no passo de " + stepName, e);
        }
    }

    @FunctionalInterface
    private interface RequestCustomizer {
        Object customize(RestClient.RequestBodySpec request);
    }
}