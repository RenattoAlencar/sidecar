package com.development.sidecar.identity;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TokenCustodyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TokenCustodyIntegrationTest.class);

    private static final String JOURNEY = "app-bank-authz-transacional";
    private static final String CHANNEL_TOKEN = "";
    private static final String OTP_CODE = "";

    private static final String PAYLOAD = """
            {"channel":{"amount":{"currency":"BRL","value":10.25}},\
            "risk":{"event_type":"transaction"}}""";

    @Autowired
    private AuthenticationJourneyClient journeyClient;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private TokenCustodian tokenCustodian;

    @Test
    void guarda_o_token_emitido_e_o_recupera_pela_referencia() {

        JourneyOutcome outcome = journeyClient.start(
                JOURNEY,
                CHANNEL_TOKEN,
                OTP_CODE,
                PAYLOAD.getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);

        AccessToken emitted = tokenIssuer.issue(outcome.step().tokenId());

        TokenReference reference = tokenCustodian.store(emitted);

        log.info("Referência recebida: {}", reference.tokenRef());

        AccessToken retrieved = tokenCustodian.retrieve(reference.tokenRef());

        assertThat(retrieved.accessToken()).isEqualTo(emitted.accessToken());
    }

    @Test
    void recusa_referencia_desconhecida() {

        assertThatThrownBy(() -> tokenCustodian.retrieve("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(TokenCustodian.TokenNotFoundException.class);
    }
}