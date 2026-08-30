package com.development.sidecar.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;


final class PkceGenerator {

    private static final String CHALLENGE_METHOD = "S256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final int VERIFIER_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PkceGenerator() {
    }

    static Pkce generate() {
        byte[] bytes = new byte[VERIFIER_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        String verifier = ENCODER.encodeToString(bytes);

        return new Pkce(verifier, challengeOf(verifier), CHALLENGE_METHOD);
    }

    private static String challengeOf(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));

            return ENCODER.encodeToString(hashed);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "Algoritmo de resumo indisponível na plataforma: " + DIGEST_ALGORITHM, e);
        }
    }

    record Pkce(String verifier, String challenge, String method) {

        @Override
        public String toString() {
            return "Pkce[method=%s]".formatted(method);
        }
    }
}