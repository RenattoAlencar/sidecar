package com.development.sidecar.proxy;

import com.development.sidecar.config.ChannelProperties;
import com.development.sidecar.config.IdentityProperties;
import com.development.sidecar.config.ProxyProperties;
import com.development.sidecar.contract.ChallengeMapper;
import com.development.sidecar.contract.ChannelResponseWriter;
import com.development.sidecar.contract.TokenRefResponse;
import com.development.sidecar.identity.AuthorizationOrchestrator;
import com.development.sidecar.identity.AuthorizationResult;
import com.development.sidecar.route.RouteDecision;
import com.development.sidecar.route.RouteResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;


public class ProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private final RouteResolver routeResolver;
    private final RequestForwarder requestForwarder;
    private final AuthorizationOrchestrator orchestrator;
    private final ChannelResponseWriter responseWriter;
    private final ChannelProperties channelProperties;
    private final IdentityProperties identityProperties;
    private final ProxyProperties proxyProperties;

    public ProxyFilter(RouteResolver routeResolver,
                       RequestForwarder requestForwarder,
                       AuthorizationOrchestrator orchestrator,
                       ChannelResponseWriter responseWriter,
                       ChannelProperties channelProperties,
                       IdentityProperties identityProperties,
                       ProxyProperties proxyProperties) {

        this.routeResolver = routeResolver;
        this.requestForwarder = requestForwarder;
        this.orchestrator = orchestrator;
        this.responseWriter = responseWriter;
        this.channelProperties = channelProperties;
        this.identityProperties = identityProperties;
        this.proxyProperties = proxyProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = CorrelationId.resolve(
                request.getHeader(proxyProperties.correlationHeader()));

        MDC.put(CorrelationId.MDC_KEY, correlationId);

        try {
            handle(request, response, correlationId);

        } finally {
            if (!response.isCommitted()) {
                response.setHeader(proxyProperties.correlationHeader(), correlationId);
            }
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void handle(HttpServletRequest request,
                        HttpServletResponse response,
                        String correlationId) throws IOException {

        Optional<RequestForwarder.RejectionReason> framing =
                requestForwarder.framingRejection(request);

        if (framing.isPresent()) {
            rejectFraming(response, framing.get(), correlationId);
            return;
        }

        RouteDecision decision = routeResolver.resolve(request.getRequestURI(), method(request));

        switch (decision.outcome()) {

            case REJECT -> {
                log.warn("Requisição recusada na normalização: motivo={}",
                        decision.rejectionReason());
                responseWriter.error(response, HttpStatus.BAD_REQUEST, "bad_request",
                        correlationId);
            }

            case PASSTHROUGH -> {
                log.debug("Rota fora da matriz, encaminhando sem verificação");
                forward(request, response, correlationId, Map.of(), null);
            }

            case INTERCEPT -> intercept(request, response, decision, correlationId);
        }
    }

    private void intercept(HttpServletRequest request,
                           HttpServletResponse response,
                           RouteDecision decision,
                           String correlationId) throws IOException {

        String tokenRef = header(request, channelProperties.tokenReferenceHeader());
        String sessionId = header(request, channelProperties.sessionHeader());

        if (tokenRef != null) {
            effect(request, response, tokenRef, decision, correlationId);

        } else if (sessionId != null) {
            advance(request, response, sessionId, decision, correlationId);

        } else {
            start(request, response, decision, correlationId);
        }
    }

    private void start(HttpServletRequest request,
                       HttpServletResponse response,
                       RouteDecision decision,
                       String correlationId) throws IOException {

        byte[] payload;
        try {
            payload = requestForwarder.readBody(request);

        } catch (RequestForwarder.PayloadTooLargeException e) {
            responseWriter.error(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                    correlationId);
            return;
        }

        AuthorizationResult result = orchestrator.start(
                decision.rule().journey(),
                header(request, identityProperties.channelTokenHeader()),
                header(request, channelProperties.responseHeader()),
                payload,
                decision.metricTag());

        apply(request, response, result, correlationId, payload);
    }

    private void advance(HttpServletRequest request,
                         HttpServletResponse response,
                         String sessionId,
                         RouteDecision decision,
                         String correlationId) throws IOException {

        AuthorizationResult result = orchestrator.advance(
                sessionId,
                header(request, channelProperties.responseHeader()),
                decision.metricTag());

        apply(request, response, result, correlationId, null);
    }

    private void effect(HttpServletRequest request,
                        HttpServletResponse response,
                        String tokenRef,
                        RouteDecision decision,
                        String correlationId) throws IOException {

        AuthorizationResult result = orchestrator.resolve(tokenRef, decision.metricTag());

        apply(request, response, result, correlationId, null);
    }

    private void apply(HttpServletRequest request,
                       HttpServletResponse response,
                       AuthorizationResult result,
                       String correlationId,
                       byte[] payload) throws IOException {

        switch (result.type()) {

            case CHALLENGE -> responseWriter.challenge(response,
                    ChallengeMapper.toChallenge(result.step()), correlationId);

            case AUTHORIZED -> responseWriter.authorized(response,
                    TokenRefResponse.of(result.reference().tokenRef()), correlationId);

            case RESOLVED -> forward(request, response, correlationId,
                    Map.of(channelProperties.tokenReferenceHeader(),
                            result.token().accessToken()),
                    payload);

            case DENIED -> responseWriter.error(response, HttpStatus.FORBIDDEN, "denied",
                    correlationId);

            case EXPIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    "session_expired", correlationId);

            case SESSION_REQUIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    "session_required", correlationId);

            case AUTHORIZATION_REQUIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    "authorization_required", correlationId);

            case BAD_REQUEST -> responseWriter.error(response, HttpStatus.BAD_REQUEST,
                    "bad_request", correlationId);

            case UNAVAILABLE -> responseWriter.error(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "authorization_unavailable", correlationId);
        }
    }

    private void forward(HttpServletRequest request,
                         HttpServletResponse response,
                         String correlationId,
                         Map<String, String> injected,
                         byte[] payload) throws IOException {

        response.setHeader(proxyProperties.correlationHeader(), correlationId);

        try {
            requestForwarder.forward(request, response, injected, payload);

        } catch (RequestForwarder.PayloadTooLargeException e) {
            log.warn("Corpo acima do teto durante o encaminhamento");
            responseWriter.error(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                    correlationId);

        } catch (RequestForwarder.UpstreamException e) {
            log.error("Falha ao encaminhar ao serviço de negócio");
            log.debug("Detalhe da falha no encaminhamento", e);
            responseWriter.error(response, HttpStatus.BAD_GATEWAY, "bad_gateway", correlationId);
        }
    }

    private void rejectFraming(HttpServletResponse response,
                               RequestForwarder.RejectionReason reason,
                               String correlationId) throws IOException {

        HttpStatus status = reason == RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;

        log.warn("Requisição recusada antes da matriz: motivo={}", reason);
        responseWriter.error(response, status, "bad_request", correlationId);
    }

    private static String header(HttpServletRequest request, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = request.getHeader(name);

        return value == null || value.isBlank() ? null : value;
    }

    private static HttpMethod method(HttpServletRequest request) {
        String name = request.getMethod();

        return name == null ? null : HttpMethod.valueOf(name);
    }
}