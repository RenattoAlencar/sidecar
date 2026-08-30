package com.development.sidecar.contract;

/**
 * Desafio apresentado ao canal.
 * <p>
 * Traduz o que o provedor emitiu: o canal recebe o tipo do desafio e a sessão a
 * devolver, sem a estrutura de callback do provedor.
 *
 * @param sessionId sessão a reapresentar junto com a resposta
 * @param challenge o que precisa ser cumprido
 */
public record ChallengeResponse(boolean authorizationRequired,
                                String sessionId,
                                Challenge challenge) {

    public record Challenge(String type, String provider) {
    }

    public static ChallengeResponse of(String sessionId, String type, String provider) {
        return new ChallengeResponse(true, sessionId, new Challenge(type, provider));
    }
}