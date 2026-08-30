package com.development.sidecar.identity;

import com.development.sidecar.config.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

public class HttpTokenIssuer implements TokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(HttpTokenIssuer.class);

    private static final String AUTHORIZE_PATH = "/oauth2/realms/%s/authorize";
    private static final String TOKEN_PATH = "/oauth2/realms/%s/access_token";

    private static final String CODE_PARAM = "code";

    private final RestClient restClient;
    private final IdentityProperties properties;

    public HttpTokenIssuer(RestClient restClient, IdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public AccessToken issue(String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            throw new TokenIssuanceException("Sessão ausente ao emitir o token");
        }

        PkceGenerator.Pkce pkce = PkceGenerator.generate();

        String code = authorize(sessionId, pkce);

        return exchange(code, pkce);
    }

    private String authorize(String sessionId, PkceGenerator.Pkce pkce) {

        log.debug("Solicitando o código de autorização");

        ResponseEntity<Void> response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(AUTHORIZE_PATH.formatted(properties.realm()))
                            .queryParam("response_type", CODE_PARAM)
                            .queryParam("client_id", properties.clientId())
                            .queryParam("redirect_uri", properties.redirectUri())
                            .queryParam("scope", properties.scopes())
                            .queryParam("code_challenge", pkce.challenge())
                            .queryParam("code_challenge_method", pkce.method())
                            .build())
                    .header(HttpHeaders.COOKIE, sessionCookie(sessionId))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientException e) {
            throw new TokenIssuanceException("Falha ao solicitar o código de autorização", e);
        }

        URI location = response.getHeaders().getLocation();

        if (location == null) {
            log.error("Provedor respondeu à autorização sem destino: status={}",
                    response.getStatusCode().value());
            throw new TokenIssuanceException("Autorização sem destino de redirecionamento");
        }

        return codeOf(location);
    }

    private AccessToken exchange(String code, PkceGenerator.Pkce pkce) {

        log.debug("Trocando o código de autorização pelo token");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add(CODE_PARAM, code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code_verifier", pkce.verifier());

        TokenResponse response;
        try {
            response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(TOKEN_PATH.formatted(properties.realm()))
                            .build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

        } catch (RestClientException e) {
            throw new TokenIssuanceException("Falha ao trocar o código pelo token", e);
        }

        if (response == null || response.accessToken() == null
                || response.accessToken().isBlank()) {

            throw new TokenIssuanceException("Provedor devolveu a troca sem token");
        }

        log.info("Token emitido pelo provedor");

        return new AccessToken(
                response.accessToken(),
                response.tokenType(),
                Duration.ofSeconds(response.expiresIn()),
                response.scope());
    }

    private String codeOf(URI location) {

        String query = location.getQuery();

        if (query == null || query.isBlank()) {
            throw new TokenIssuanceException("Destino da autorização sem parâmetros");
        }

        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');

            if (separator > 0 && CODE_PARAM.equals(parameter.substring(0, separator))) {
                String code = parameter.substring(separator + 1);

                if (!code.isBlank()) {
                    return code;
                }
            }
        }

        log.error("Destino da autorização não trouxe o código");
        throw new TokenIssuanceException("Autorização não concedeu código");
    }

    private String sessionCookie(String sessionId) {
        return properties.sessionCookieName() + "=" + sessionId;
    }
}