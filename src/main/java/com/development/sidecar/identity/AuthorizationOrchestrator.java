package com.development.sidecar.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthorizationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationOrchestrator.class);

    private final AuthenticationJourneyClient journeyClient;
    private final TokenIssuer tokenIssuer;
    private final TokenCustodian tokenCustodian;

    public AuthorizationOrchestrator(AuthenticationJourneyClient journeyClient,
                                     TokenIssuer tokenIssuer,
                                     TokenCustodian tokenCustodian) {
        this.journeyClient = journeyClient;
        this.tokenIssuer = tokenIssuer;
        this.tokenCustodian = tokenCustodian;
    }

    public AuthorizationResult start(String journey,
                                     String channelToken,
                                     String authenticatorCode,
                                     byte[] payload,
                                     String rule) {

        if (channelToken == null || channelToken.isBlank()) {
            log.warn("Rota interceptada sem o token do canal: regra={}", rule);
            return AuthorizationResult.sessionRequired();
        }

        if (journey == null || journey.isBlank()) {
            log.error("Rota interceptada sem jornada configurada: regra={}", rule);
            return AuthorizationResult.unavailable();
        }

        if (payload == null || payload.length == 0) {
            log.warn("Rota interceptada sem corpo: regra={}", rule);
            return AuthorizationResult.badRequest();
        }

        log.info("Rota interceptada: regra={}", rule);

        JourneyOutcome outcome;
        try {
            outcome = journeyClient.start(journey, channelToken, authenticatorCode, payload);

        } catch (AuthenticationJourneyClient.JourneyUnavailableException e) {
            log.error("Provedor de identidade indisponível: regra={}", rule);
            log.debug("Detalhe da indisponibilidade ao iniciar a jornada", e);
            return AuthorizationResult.unavailable();
        }

        return apply(outcome, rule);
    }

    public AuthorizationResult advance(String sessionId, String response, String rule) {

        if (response == null || response.isBlank()) {
            log.warn("Continuação sem resposta ao desafio: regra={}", rule);
            return AuthorizationResult.badRequest();
        }

        log.info("Continuando a jornada: regra={}", rule);

        JourneyOutcome outcome;
        try {
            outcome = journeyClient.advance(sessionId, response);

        } catch (AuthenticationJourneyClient.JourneyUnavailableException e) {
            log.error("Provedor de identidade indisponível ao continuar: regra={}", rule);
            log.debug("Detalhe da indisponibilidade ao continuar a jornada", e);
            return AuthorizationResult.unavailable();
        }

        return apply(outcome, rule);
    }

    public AuthorizationResult resolve(String tokenRef, String rule) {

        log.info("Resolvendo referência: regra={}", rule);

        AccessToken token;
        try {
            token = tokenCustodian.retrieve(tokenRef);

        } catch (TokenCustodian.TokenNotFoundException e) {
            log.info("Referência não corresponde a autorização válida: regra={}", rule);
            return AuthorizationResult.authorizationRequired();

        } catch (TokenCustodian.TokenCustodyException e) {
            log.error("Guardião de token indisponível ao recuperar: regra={}", rule);
            log.debug("Detalhe da falha ao recuperar o token", e);
            return AuthorizationResult.unavailable();
        }

        log.info("Autorização confirmada, encaminhando: regra={}", rule);
        return AuthorizationResult.resolved(token);
    }

    private AuthorizationResult apply(JourneyOutcome outcome, String rule) {

        return switch (outcome.type()) {

            case CHALLENGE -> {
                if (outcome.step() == null) {
                    log.error("Desafio recebido sem passo da jornada: regra={}", rule);
                    yield AuthorizationResult.unavailable();
                }
                log.info("Desafio emitido ao canal: regra={}", rule);
                yield AuthorizationResult.challenge(outcome.step());
            }

            case COMPLETED -> issue(outcome, rule);

            case DENIED -> refuse(outcome.reason(), rule);

            case EXPIRED -> {
                log.info("Sessão da jornada expirada: regra={}", rule);
                yield AuthorizationResult.expired();
            }
        };
    }

    private AuthorizationResult refuse(String code, String rule) {

        if (JourneyRefusalOutcome.isSessionProblem(code)) {
            log.info("Sessão do canal recusada pelo provedor: regra={}", rule);
            return AuthorizationResult.expired();
        }

        if (JourneyRefusalOutcome.isProviderFailure(code)) {
            log.error("Provedor falhou ao validar a autorização: regra={}", rule);
            return AuthorizationResult.unavailable();
        }

        RefusalKind refusal = RefusalKind.of(code);

        log.info("Jornada negada: regra={}, motivo={}, código={}", rule, refusal.reason(), code);

        return AuthorizationResult.denied(refusal);
    }

    private AuthorizationResult issue(JourneyOutcome outcome, String rule) {

        String sessionId = outcome.step() == null ? null : outcome.step().tokenId();

        if (sessionId == null || sessionId.isBlank()) {
            log.error("Jornada concluída sem sessão emitida: regra={}", rule);
            return AuthorizationResult.unavailable();
        }

        AccessToken token;
        try {
            token = tokenIssuer.issue(sessionId);

        } catch (TokenIssuer.TokenIssuanceException e) {
            log.error("Falha ao obter o token depois da jornada: regra={}", rule);
            log.debug("Detalhe da falha na emissão do token", e);
            return AuthorizationResult.unavailable();
        }

        TokenReference reference;
        try {
            reference = tokenCustodian.store(token);

        } catch (TokenCustodian.TokenCustodyException e) {
            log.error("Falha ao entregar o token sob guarda: regra={}", rule);
            log.debug("Detalhe da falha na entrega sob guarda", e);
            return AuthorizationResult.unavailable();
        }

        log.info("Autorização concluída: regra={}", rule);
        return AuthorizationResult.authorized(reference);
    }
}