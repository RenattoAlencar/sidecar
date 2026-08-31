package com.development.sidecar.identity;

import com.development.sidecar.config.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpTokenIssuerTest {

    private static final String BASE_URL = "https://provedor.invalid/am";
    private static final String SESSION = "S6C6ZyySbmGRePt6xAoDNCxB-Tk";
    private static final String CODE = "abc123";
    private static final String COOKIE_NAME = "cookie-de-sessao";
    private static final String CLIENT_ID = "cliente";
    private static final String REDIRECT_URI = "https://localhost:8080";

    private static final String TOKEN_RESPONSE = """
            {"access_token":"opaco-do-provedor","token_type":"Bearer",
            "expires_in":3599,"scope":"write"}""";

    private MockRestServiceServer server;
    private TokenIssuer issuer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);

        server = MockRestServiceServer.bindTo(builder).build();
        issuer = new HttpTokenIssuer(builder.build(), properties());
    }

    @Nested
    @DisplayName("Pedido do código de autorização")
    class Authorize {

        @Test
        @DisplayName("apresenta a sessão da jornada como cookie")
        void apresenta_a_sessao() {

            server.expect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE_NAME + "=" + SESSION))
                    .andExpect(queryParam("response_type", "code"))
                    .andExpect(queryParam("client_id", CLIENT_ID))
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            expectTokenExchange();

            issuer.issue(SESSION);

            server.verify();
        }

        @Test
        @DisplayName("o verificador é sorteado e seu resumo acompanha o pedido")
        void apresenta_o_resumo_do_verificador() {

            server.expect(queryParam("code_challenge_method", "S256"))
                    .andExpect(request -> assertThat(request.getURI().getQuery())
                            .contains("code_challenge="))
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            expectTokenExchange();

            issuer.issue(SESSION);

            server.verify();
        }

        @Test
        @DisplayName("sem destino de redirecionamento não há código a ler")
        void recusa_resposta_sem_destino() {

            server.expect(request -> {})
                    .andRespond(withStatus(HttpStatus.OK));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }

        @Test
        @DisplayName("o provedor também redireciona ao recusar: o código precisa ser procurado")
        void recusa_destino_com_erro() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI
                            + "?error=invalid_scope&error_description=escopo+desconhecido"));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }

        @Test
        void recusa_destino_sem_parametros() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }

        @Test
        void recusa_sem_sessao() {

            assertThatThrownBy(() -> issuer.issue("  "))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }
    }

    @Nested
    @DisplayName("Troca do código pelo token")
    class Exchange {

        @Test
        @DisplayName("apresenta o código, o segredo e o verificador sorteado")
        void apresenta_o_que_a_troca_exige() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            server.expect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(content().string(org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                            org.hamcrest.Matchers.containsString("code=" + CODE),
                            org.hamcrest.Matchers.containsString("code_verifier="))))
                    .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));

            issuer.issue(SESSION);

            server.verify();
        }

        @Test
        void devolve_o_token_emitido() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            expectTokenExchange();

            AccessToken token = issuer.issue(SESSION);

            assertThat(token.accessToken()).isEqualTo("opaco-do-provedor");
            assertThat(token.tokenType()).isEqualTo("Bearer");
            assertThat(token.expiresIn()).isEqualTo(Duration.ofSeconds(3599));
            assertThat(token.scope()).isEqualTo("write");
        }

        @Test
        void recusa_troca_sem_token() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            server.expect(request -> {})
                    .andRespond(withSuccess("{\"token_type\":\"Bearer\"}",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }

        @Test
        void recusa_quando_a_troca_falha() {

            server.expect(request -> {})
                    .andRespond(redirectTo(REDIRECT_URI + "?code=" + CODE));

            server.expect(request -> {})
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }
    }

    private void expectTokenExchange() {
        server.expect(request -> {})
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
    }

    private static org.springframework.test.web.client.ResponseCreator redirectTo(String location) {
        return withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location);
    }

    private static IdentityProperties properties() {
        return new IdentityProperties(
                URI.create(BASE_URL),
                "alpha",
                "service",
                CLIENT_ID,
                "segredo",
                REDIRECT_URI,
                "openid write",
                COOKIE_NAME,
                "x-canal-autenticacao",
                "x-canal-codigo",
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
    }
}