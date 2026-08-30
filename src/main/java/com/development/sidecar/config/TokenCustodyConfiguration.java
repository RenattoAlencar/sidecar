package com.development.sidecar.config;

import com.development.sidecar.identity.HttpTokenCustodian;
import com.development.sidecar.identity.ServiceCredentialsProvider;
import com.development.sidecar.identity.TokenCustodian;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties({TokenHandlerProperties.class, ServiceCredentialsProperties.class})
public class TokenCustodyConfiguration {

    @Bean
    public RestClient serviceCredentialsRestClient(ServiceCredentialsProperties properties) {
        return build(properties.connectTimeout(), properties.readTimeout());
    }

    @Bean
    public RestClient tokenHandlerRestClient(TokenHandlerProperties properties) {
        return build(properties.connectTimeout(), properties.readTimeout());
    }

    @Bean
    public ServiceCredentialsProvider serviceCredentialsProvider(
            RestClient serviceCredentialsRestClient,
            ServiceCredentialsProperties properties) {

        return new ServiceCredentialsProvider(serviceCredentialsRestClient, properties);
    }

    @Bean
    public TokenCustodian tokenCustodian(RestClient tokenHandlerRestClient,
                                         TokenHandlerProperties properties,
                                         ServiceCredentialsProvider credentials) {

        return new HttpTokenCustodian(tokenHandlerRestClient, properties, credentials);
    }

    private static RestClient build(java.time.Duration connectTimeout,
                                    java.time.Duration readTimeout) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}