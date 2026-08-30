package com.development.sidecar.config;

import com.development.sidecar.proxy.ProxyHeaderPolicy;
import com.development.sidecar.proxy.RequestForwarder;
import com.development.sidecar.route.RouteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {

    @Bean
    public HttpClient proxyHttpClient(ProxyProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public ProxyHeaderPolicy proxyHeaderPolicy(ProxyProperties properties) {
        return new ProxyHeaderPolicy(properties.reservedHeaders());
    }

    @Bean
    public RequestForwarder requestForwarder(HttpClient proxyHttpClient,
                                             ProxyProperties properties,
                                             ProxyHeaderPolicy proxyHeaderPolicy) {

        return new RequestForwarder(proxyHttpClient, properties, proxyHeaderPolicy);
    }

    @Bean
    public RouteResolver routeResolver(ProxyProperties properties) {
        return new RouteResolver(properties);
    }

    @Bean
    public ProxyHeaderPolicy proxyHeaderPolicy(ProxyProperties properties,
                                               ChannelProperties channelProperties) {

        Set<String> reserved = new LinkedHashSet<>(properties.reservedHeaders());
        reserved.addAll(channelProperties.reservedNames());

        return new ProxyHeaderPolicy(reserved);
    }
}