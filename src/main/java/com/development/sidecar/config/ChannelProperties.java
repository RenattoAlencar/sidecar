package com.development.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Validated
@ConfigurationProperties(prefix = "channel")
public record ChannelProperties(

        @DefaultValue("x-authz-session")
        @NotBlank(message = "channel.session-header é obrigatório")
        String sessionHeader,

        @DefaultValue("x-authz-response")
        @NotBlank(message = "channel.response-header é obrigatório")
        String responseHeader,

        @DefaultValue("x-authz-token-ref")
        @NotBlank(message = "channel.token-reference-header é obrigatório")
        String tokenReferenceHeader
) {

    public ChannelProperties {
        sessionHeader = trim(sessionHeader);
        responseHeader = trim(responseHeader);
        tokenReferenceHeader = trim(tokenReferenceHeader);

        requireDistinct(sessionHeader, responseHeader, tokenReferenceHeader);
    }

    private static void requireDistinct(String... names) {

        Set<String> distinct = new LinkedHashSet<>();

        for (String name : names) {
            if (!name.isBlank() && !distinct.add(name.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Os cabeçalhos do canal precisam ter nomes distintos: " + name
                                + " está repetido.");
            }
        }
    }

    public List<String> reservedNames() {
        return List.of(sessionHeader, responseHeader, tokenReferenceHeader);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}