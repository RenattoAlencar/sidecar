package com.development.sidecar.identity;

import com.development.sidecar.config.IdentityProperties;
import com.development.sidecar.config.TokenHandlerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;


class AuthorizationChainTest {

    private static final String PROVIDER_URL = "https://provedor.invalid/am";
    private static final String CUSTODY_URL = "https://guardiao.invalid/v1/token-refs";
    private static final String CREDENTIALS_URL = "https://sso.invalid/token";

    private static final String JOURNEY = "jornada-transacional";
    private static final String CHANNEL_TOKEN = "eyJraWQiOi...";
    private static final String CODE = "149707";
    private static final String AUTH_ID = "eyJ0eXAiOiJKV1Qi...";
    private static final String SESSION = "S6C6ZyySbmGRePt6xAoDNCxB-Tk";
    private static final String ACCESS_TOKEN = "opaco-do-provedor";
    private static final String TOKEN_REF = "84da0844-d1f9-31f9-b4f4-79b420be8be4";

    private static final byte[] PAYLOAD =
            "{\"channel\":{\"message\":\"não identificado\"},\"risk\":{},\"authN\":{}}"
                    .getBytes(StandardCharsets.UTF_8);

    private MockRestServiceServer provider;
    private MockRestServiceServer custody;
    private MockRestServiceServer credentials;

    private AuthorizationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {

        RestClient.Builder providerBuilder = RestClient.builder().baseUrl(PROVIDER_URL);
        RestClient.Builder custodyBuilder = RestClient.builder();
        RestClient.Builder credentialsBuilder = RestClient.builder();

        provider = MockRestServiceServer.bindTo(providerBuilder).build();
        custody = MockRestServiceServer.bindTo(custodyBuilder).build();
        credentials = MockRestServiceServer.bindTo(credentialsBuilder).build();

        RestClient providerClient = providerBuilder.build();

        ServiceCredentialsProvider credentialsProvider = new ServiceCredentialsProvider(
                credentialsBuilder.build(), credentialsProperties());

        orchestrator = new AuthorizationOrchestrator(
                new HttpAuthenticationJourneyClient(providerClient, identityProperties()),
                new HttpTokenIssuer(providerClient, identityProperties()),
                new HttpTokenCustodian(
                        custodyBuilder.build(), custodyProperties(), credentialsProvider));
    }

    @Test
    @DisplayName("da apresentação da transação à referência devolvida ao canal")
    void percorre_a_cadeia_inteira() {

        expectJourney();
        expectIssuance();
        expectCustody();

        AuthorizationResult result =
                orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, "regra");

        assertThat(result.type()).isEqualTo(AuthorizationResult.Type.AUTHORIZED);
        assertThat(result.reference().tokenRef()).isEqualTo(TOKEN_REF);

        provider.verify();
        custody.verify();
        credentials.verify();
    }

    @Test
    @DisplayName("a referência devolve o mesmo token que foi guardado")
    void recupera_o_token_guardado() {

        credentials.expect(request -> {})
                .andRespond(withSuccess(credentialResponse(), MediaType.APPLICATION_JSON));

        custody.expect(request -> {})
                .andRespond(withSuccess(
                        "{\"dados\":{\"accessToken\":\"" + ACCESS_TOKEN + "\"}}",
                        MediaType.APPLICATION_JSON));

        AuthorizationResult result = orchestrator.resolve(TOKEN_REF, "regra");

        assertThat(result.type()).isEqualTo(AuthorizationResult.Type.RESOLVED);
        assertThat(result.token().accessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("referência desconhecida pede nova autorização")
    void referencia_desconhecida() {

        credentials.expect(request -> {})
                .andRespond(withSuccess(credentialResponse(), MediaType.APPLICATION_JSON));

        custody.expect(request -> {})
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        AuthorizationResult result = orchestrator.resolve(TOKEN_REF, "regra");

        assertThat(result.type()).isEqualTo(AuthorizationResult.Type.AUTHORIZATION_REQUIRED);
    }

    @Test
    @DisplayName("sem o código do autenticador, a jornada recusa")
    void recusa_sem_codigo() {

        provider.expect(request -> {})
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":401,"message":"OTP nulo (Journey encerrada)",
                                "detail":{"errorCode":"002"}}"""));

        AuthorizationResult result =
                orchestrator.start(JOURNEY, CHANNEL_TOKEN, null, PAYLOAD, "regra");

        assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
        assertThat(result.refusal()).isEqualTo(RefusalKind.RETRY);
    }

    @Test
    @DisplayName("corpo fora do contrato é recusa que um código novo não resolve")
    void recusa_por_corpo() {

        provider.expect(request -> {})
                .andRespond(withSuccess(payloadCallback(), MediaType.APPLICATION_JSON));

        provider.expect(request -> {})
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":401,"message":"Payload dessincronizado",
                                "detail":{"errorCode":"014"}}"""));

        AuthorizationResult result =
                orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, "regra");

        assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
        assertThat(result.refusal()).isEqualTo(RefusalKind.INVALID_REQUEST);
    }

    /**
     * Início da jornada e apresentação do corpo, com a verificação de que o corpo
     * chega como veio.
     */
    private void expectJourney() {

        provider.expect(request -> {})
                .andRespond(withSuccess(payloadCallback(), MediaType.APPLICATION_JSON));

        provider.expect(jsonPath("$.authId").value(AUTH_ID))
                .andExpect(jsonPath("$.callbacks[0].input[0].value")
                        .value(new String(PAYLOAD, StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.callbacks[0].output[0].value").value("PAYLOAD_REQUIRED"))
                .andRespond(withSuccess(
                        "{\"tokenId\":\"" + SESSION + "\"}", MediaType.APPLICATION_JSON));
    }

    /**
     * Pedido do código de autorização e troca pelo token.
     */
    private void expectIssuance() {

        provider.expect(request -> assertThat(request.getHeaders()
                        .getFirst(HttpHeaders.COOKIE))
                        .contains(SESSION))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://localhost?code=abc123"));

        provider.expect(request -> {})
                .andRespond(withSuccess("""
                        {"access_token":"%s","token_type":"Bearer",
                        "expires_in":3599,"scope":"write"}""".formatted(ACCESS_TOKEN),
                        MediaType.APPLICATION_JSON));
    }

    /**
     * Credencial do componente e entrega do token ao guardião.
     */
    private void expectCustody() {

        credentials.expect(request -> {})
                .andRespond(withSuccess(credentialResponse(), MediaType.APPLICATION_JSON));

        custody.expect(jsonPath("$.accessToken").value(ACCESS_TOKEN))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"dados\":{\"tokenRef\":\"" + TOKEN_REF + "\"}}"));
    }

    private static String payloadCallback() {
        return """
                {"authId":"%s","callbacks":[{"type":"NameCallback",
                "output":[{"name":"prompt","value":"PAYLOAD_REQUIRED"}],
                "input":[{"name":"IDToken1","value":"PAYLOAD_REQUIRED"}]}]}"""
                .formatted(AUTH_ID);
    }

    private static String credentialResponse() {
        return "{\"access_token\":\"credencial\",\"expires_in\":3600}";
    }

    private static IdentityProperties identityProperties() {
        return new IdentityProperties(
                URI.create(PROVIDER_URL),
                "alpha", "service", "cliente", "segredo", "https://localhost",
                "openid write", "cookie-de-sessao",
                "x-canal-autenticacao", "x-canal-codigo",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private static TokenHandlerProperties custodyProperties() {
        return new TokenHandlerProperties(
                URI.create(CUSTODY_URL), "X-Token-Ref",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static com.development.sidecar.config.ServiceCredentialsProperties
            credentialsProperties() {

        return new com.development.sidecar.config.ServiceCredentialsProperties(
                URI.create(CREDENTIALS_URL), "componente", "segredo", "",
                Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}