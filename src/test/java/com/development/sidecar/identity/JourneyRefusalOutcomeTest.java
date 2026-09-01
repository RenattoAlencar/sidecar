package com.development.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRefusalOutcomeTest {

    @Test
    @DisplayName("sessão do canal recusada não é recusa de autorização")
    void reconhece_problema_de_sessao() {

        assertThat(JourneyRefusalOutcome.isSessionProblem("001")).isTrue();
        assertThat(JourneyRefusalOutcome.isSessionProblem("  001  ")).isTrue();

        assertThat(JourneyRefusalOutcome.isSessionProblem("002")).isFalse();
    }

    @Test
    @DisplayName("falha do provedor não é recusa: o titular não tem o que corrigir")
    void reconhece_falha_do_provedor() {

        assertThat(JourneyRefusalOutcome.isProviderFailure("006")).isTrue();
        assertThat(JourneyRefusalOutcome.isProviderFailure("  006  ")).isTrue();

        assertThat(JourneyRefusalOutcome.isProviderFailure("003")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "014", "999"})
    @DisplayName("o restante segue como recusa")
    void o_restante_e_recusa(String code) {

        assertThat(JourneyRefusalOutcome.isSessionProblem(code)).isFalse();
        assertThat(JourneyRefusalOutcome.isProviderFailure(code)).isFalse();
    }
}