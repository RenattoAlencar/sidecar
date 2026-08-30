package com.development.sidecar.identity;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TokenIssuanceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TokenIssuanceIntegrationTest.class);

    private static final String JOURNEY = "";
    private static final String CHANNEL_TOKEN = "";
    private static final String OTP_CODE = "";

    private static final String PAYLOAD = """
            {"channel":{"amount":{"currency":"BRL","value":10.25}},\
            "risk":{"event_type":"transaction"}}""";

    @Autowired
    private AuthenticationJourneyClient journeyClient;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Test
    void conclui_a_jornada_e_emite_o_token() {

        JourneyOutcome outcome = journeyClient.start(
                JOURNEY,
                CHANNEL_TOKEN,
                OTP_CODE,
                PAYLOAD.getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);

        AccessToken token = tokenIssuer.issue(outcome.step().tokenId());

        log.info("Token emitido: {}", token);

        assertThat(token.accessToken()).isNotBlank();
    }
}