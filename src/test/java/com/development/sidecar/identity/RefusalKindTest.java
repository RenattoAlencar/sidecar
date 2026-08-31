package com.development.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalKindTest {

    @ParameterizedTest(name = "código {0}")
    @ValueSource(strings = {"002", "003", "006"})
    @DisplayName("recusa sobre a resposta ao desafio: um código novo pode resolver")
    void classifica_como_nova_tentativa(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.RETRY);
    }

    @ParameterizedTest(name = "código {0}")
    @ValueSource(strings = {"014", "015"})
    @DisplayName("recusa sobre o corpo: repetir com o mesmo corpo dá o mesmo resultado")
    void classifica_como_requisicao_invalida(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.INVALID_REQUEST);
    }

    @ParameterizedTest(name = "código {0}")
    @ValueSource(strings = {"001", "007", "999", "Authentication Failed"})
    @DisplayName("código não mapeado não vira ação: o canal recebe recusa genérica")
    void nao_classifica_o_desconhecido(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.UNKNOWN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void tolera_ausencia_de_codigo(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.UNKNOWN);
    }

    @Test
    void ignora_espaco_em_volta_do_codigo() {
        assertThat(RefusalKind.of("  014  ")).isEqualTo(RefusalKind.INVALID_REQUEST);
    }
}