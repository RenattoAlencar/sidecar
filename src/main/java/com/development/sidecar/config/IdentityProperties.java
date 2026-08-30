package com.development.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(

        @NotNull(message = "identity.base-url é obrigatório")
        URI baseUrl,

        @DefaultValue("alpha")
        @NotBlank(message = "identity.realm é obrigatório")
        String realm,

        @DefaultValue("service")
        @NotBlank(message = "identity.journey-type é obrigatório")
        String journeyType,

        @NotBlank(message = "identity.client-id é obrigatório")
        String clientId,

        @NotBlank(message = "identity.client-secret é obrigatório")
        String clientSecret,

        @NotBlank(message = "identity.redirect-uri é obrigatório")
        String redirectUri,

        @DefaultValue("openid")
        @NotBlank(message = "identity.scopes é obrigatório")
        String scopes,

        @NotBlank(message = "identity.session-cookie-name é obrigatório")
        String sessionCookieName,

        @DefaultValue("x-empresa-authentication")
        @NotBlank(message = "identity.channel-token-header é obrigatório")
        String channelTokenHeader,

        @DefaultValue("")
        String authenticatorCodeHeader,

        @DefaultValue("2s")
        @NotNull(message = "identity.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("10s")
        @NotNull(message = "identity.read-timeout é obrigatório")
        Duration readTimeout
) {

    public IdentityProperties {
        realm = trim(realm);
        journeyType = trim(journeyType);
        scopes = trim(scopes);
        sessionCookieName = trim(sessionCookieName);
        channelTokenHeader = trim(channelTokenHeader);
        authenticatorCodeHeader = trim(authenticatorCodeHeader);

        requirePositive(connectTimeout, "identity.connect-timeout");
        requirePositive(readTimeout, "identity.read-timeout");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }

    @Override
    public String toString() {
        return "IdentityProperties[baseUrl=%s, realm=%s, journeyType=%s, clientId=%s]"
                .formatted(baseUrl, realm, journeyType, clientId);
    }
}