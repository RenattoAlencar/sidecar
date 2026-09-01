package com.development.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalKindTest {

    @Test
    @DisplayName("código não informado: o canal pede o código ao titular")
    void codigo_nao_informado() {
        assertThat(RefusalKind.of("002")).isEqualTo(RefusalKind.CODE_REQUIRED);
    }

    @Test
    @DisplayName("código incorreto: o canal pede outro código")
    void codigo_incorreto() {
        assertThat(RefusalKind.of("003")).isEqualTo(RefusalKind.CODE_INVALID);
    }

    @ParameterizedTest(name = "código {0}")
    @ValueSource(strings = {"014", "015"})
    @DisplayName("recusa sobre o corpo: repetir com o mesmo corpo dá o mesmo resultado")
    void recusa_sobre_o_corpo(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.PAYLOAD_INVALID);
    }

    @ParameterizedTest(name = "código {0}")
    @ValueSource(strings = {"007", "999", "Authentication Failed"})
    @DisplayName("código não mapeado não vira ação: o canal recebe recusa genérica")
    void codigo_desconhecido(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.DENIED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void tolera_ausencia_de_codigo(String code) {
        assertThat(RefusalKind.of(code)).isEqualTo(RefusalKind.DENIED);
    }

    @Test
    void ignora_espaco_em_volta_do_codigo() {
        assertThat(RefusalKind.of("  014  ")).isEqualTo(RefusalKind.PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("só a recusa sobre o corpo é problema da requisição")
    void distingue_problema_de_requisicao() {

        assertThat(RefusalKind.PAYLOAD_INVALID.isRequestProblem()).isTrue();

        assertThat(RefusalKind.CODE_REQUIRED.isRequestProblem()).isFalse();
        assertThat(RefusalKind.CODE_INVALID.isRequestProblem()).isFalse();
        assertThat(RefusalKind.FACTOR_REQUIRED.isRequestProblem()).isFalse();
        assertThat(RefusalKind.DENIED.isRequestProblem()).isFalse();
    }

    @Test
    @DisplayName("o nome apresentado ao canal é estável")
    void nomes_apresentados_ao_canal() {

        assertThat(RefusalKind.CODE_REQUIRED.reason()).isEqualTo("code_required");
        assertThat(RefusalKind.CODE_INVALID.reason()).isEqualTo("code_invalid");
        assertThat(RefusalKind.FACTOR_REQUIRED.reason()).isEqualTo("factor_required");
        assertThat(RefusalKind.PAYLOAD_INVALID.reason()).isEqualTo("payload_invalid");
        assertThat(RefusalKind.DENIED.reason()).isEqualTo("denied");
    }
}