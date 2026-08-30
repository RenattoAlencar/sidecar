package com.development.sidecar.config;

import com.development.sidecar.contract.ChannelResponseWriter;
import com.development.sidecar.identity.AuthenticationJourneyClient;
import com.development.sidecar.identity.AuthorizationOrchestrator;
import com.development.sidecar.identity.TokenCustodian;
import com.development.sidecar.identity.TokenIssuer;
import com.development.sidecar.proxy.ProxyFilter;
import com.development.sidecar.proxy.RequestForwarder;
import com.development.sidecar.route.RouteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class SidecarConfiguration {

    @Bean
    public AuthorizationOrchestrator authorizationOrchestrator(
            AuthenticationJourneyClient journeyClient,
            TokenIssuer tokenIssuer,
            TokenCustodian tokenCustodian) {

        return new AuthorizationOrchestrator(journeyClient, tokenIssuer, tokenCustodian);
    }

    @Bean
    public ChannelResponseWriter channelResponseWriter(ObjectMapper objectMapper,
                                                       ProxyProperties proxyProperties) {

        return new ChannelResponseWriter(objectMapper, proxyProperties.correlationHeader());
    }

    @Bean
    public FilterRegistrationBean<ProxyFilter> proxyFilterRegistration(
            RouteResolver routeResolver,
            RequestForwarder requestForwarder,
            AuthorizationOrchestrator orchestrator,
            ChannelResponseWriter responseWriter,
            ChannelProperties channelProperties,
            IdentityProperties identityProperties,
            ProxyProperties proxyProperties) {

        ProxyFilter filter = new ProxyFilter(
                routeResolver,
                requestForwarder,
                orchestrator,
                responseWriter,
                channelProperties,
                identityProperties,
                proxyProperties);

        FilterRegistrationBean<ProxyFilter> registration = new FilterRegistrationBean<>(filter);

        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

        return registration;
    }
}