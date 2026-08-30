package com.development.sidecar.config;


import com.development.sidecar.identity.AuthenticationJourneyClient;
import com.development.sidecar.identity.HttpAuthenticationJourneyClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

    @Bean
    public RestClient identityRestClient(IdentityProperties properties) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public AuthenticationJourneyClient authenticationJourneyClient(
            RestClient identityRestClient,
            IdentityProperties properties) {

        return new HttpAuthenticationJourneyClient(identityRestClient, properties);
    }
}