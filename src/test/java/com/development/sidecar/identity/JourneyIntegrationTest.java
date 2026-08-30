package com.development.sidecar.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
@EnabledIfEnvironmentVariable(named = "JOURNEY_CHANNEL_TOKEN", matches = ".+")
class JourneyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JourneyIntegrationTest.class);

    private static final String JOURNEY = "app-bank-authz-transacional";
    private static final String CHANNEL_TOKEN = "eydhsj...";
    private static final String OTP_CODE = "263748";



    private static final String PAYLOAD = """
            {"channel":{"amount":{"currency":"BRL","value":10.25}},\
            "risk":{"event_type":"transaction"}}""";

    @Autowired
    private AuthenticationJourneyClient journeyClient;

    @Test
    void inicia_a_jornada_e_apresenta_o_corpo_da_transacao() {


        JourneyOutcome outcome = journeyClient.start(
                JOURNEY,
                CHANNEL_TOKEN,
                OTP_CODE,
                PAYLOAD.getBytes(StandardCharsets.UTF_8));

        log.info("Desfecho: {}", outcome);

        assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);
    }

    @Test
    void recusa_a_jornada_quando_o_codigo_de_autenticador_nao_e_apresentado() {


        JourneyOutcome outcome = journeyClient.start(
                JOURNEY,
                CHANNEL_TOKEN,
                null,
                PAYLOAD.getBytes(StandardCharsets.UTF_8));

        log.info("Desfecho sem código: {}", outcome);

        assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
    }
}