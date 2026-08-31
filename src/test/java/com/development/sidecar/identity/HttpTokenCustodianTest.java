package com.development.sidecar.identity;

import com.development.sidecar.config.TokenHandlerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class HttpTokenCustodianTest {

    private static final String URL = "https://guardiao.invalid/v1/token-refs";
    private static final String TOKEN_REF_HEADER = "X-Token-Ref";
    private static final String TOKEN_REF = "84da0844-d1f9-31f9-b4f4-79b420be8be4";
    private static final String CREDENTIAL = "credencial-de-servico";
    private static final String ACCESS_TOKEN = "opaco-do-provedor";

    @Mock
    private ServiceCredentialsProvider credentials;

    private MockRestServiceServer server;
    private TokenCustodian custodian;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer.bindTo(builder).build();

        lenient().when(credentials.credential()).thenReturn(CREDENTIAL);

        custodian = new HttpTokenCustodian(builder.build(), properties(), credentials);
    }

    @Nested
    @DisplayName("Entrega sob guarda")
    class Store {

        @Test
        @DisplayName("apresenta a credencial do componente e o token emitido")
        void entrega_o_token() {

            server.expect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                    .andExpect(jsonPath("$.accessToken").value(ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"dados\":{\"tokenRef\":\"" + TOKEN_REF + "\"}}"));

            TokenReference reference = custodian.store(accessToken());

            assertThat(reference.tokenRef()).isEqualTo(TOKEN_REF);
            server.verify();
        }

        @Test
        void recusa_quando_a_referencia_nao_volta() {

            server.expect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"dados\":{}}"));

            assertThatThrownBy(() -> custodian.store(accessToken()))
                    .isInstanceOf(TokenCustodian.TokenCustodyException.class);
        }

        @Test
        void recusa_sem_token_a_entregar() {

            assertThatThrownBy(() -> custodian.store(null))
                    .isInstanceOf(TokenCustodian.TokenCustodyException.class);
        }

        @Test
        @DisplayName("sem credencial não há como falar com o guardião")
        void traduz_falha_de_credencial() {

            lenient().when(credentials.credential()).thenThrow(
                    new ServiceCredentialsProvider.ServiceCredentialsException("x"));

            assertThatThrownBy(() -> custodian.store(accessToken()))
                    .isInstanceOf(TokenCustodian.TokenCustodyException.class);
        }
    }

    @Nested
    @DisplayName("Recuperação pela referência")
    class Retrieve {

        @Test
        void apresenta_a_referencia_no_cabecalho() {

            server.expect(method(HttpMethod.GET))
                    .andExpect(header(TOKEN_REF_HEADER, TOKEN_REF))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                    .andRespond(withSuccess(
                            "{\"dados\":{\"accessToken\":\"" + ACCESS_TOKEN + "\"}}",
                            MediaType.APPLICATION_JSON));

            AccessToken token = custodian.retrieve(TOKEN_REF);

            assertThat(token.accessToken()).isEqualTo(ACCESS_TOKEN);
            server.verify();
        }

        @Test
        @DisplayName("referência desconhecida pede nova autorização")
        void traduz_referencia_desconhecida() {

            server.expect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> custodian.retrieve(TOKEN_REF))
                    .isInstanceOf(TokenCustodian.TokenNotFoundException.class);
        }

        @Test
        @DisplayName("guardião indisponível não invalida a referência")
        void distingue_indisponibilidade_de_referencia_invalida() {

            server.expect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> custodian.retrieve(TOKEN_REF))
                    .isInstanceOf(TokenCustodian.TokenCustodyException.class)
                    .isNotInstanceOf(TokenCustodian.TokenNotFoundException.class);
        }

        @Test
        void recusa_resposta_sem_token() {

            server.expect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"dados\":{}}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> custodian.retrieve(TOKEN_REF))
                    .isInstanceOf(TokenCustodian.TokenNotFoundException.class);
        }

        @Test
        void recusa_referencia_ausente() {

            assertThatThrownBy(() -> custodian.retrieve("  "))
                    .isInstanceOf(TokenCustodian.TokenNotFoundException.class);
        }
    }

    private static AccessToken accessToken() {
        return new AccessToken(ACCESS_TOKEN, "Bearer", Duration.ofSeconds(3599), "write");
    }

    private static TokenHandlerProperties properties() {
        return new TokenHandlerProperties(
                URI.create(URL),
                TOKEN_REF_HEADER,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}