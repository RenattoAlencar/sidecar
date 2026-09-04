package com.development.sidecar.proxy;

import com.development.sidecar.config.ChannelProperties;
import com.development.sidecar.config.IdentityProperties;
import com.development.sidecar.config.ProxyProperties;
import com.development.sidecar.contract.ChallengeAnswerReader;
import com.development.sidecar.contract.ChallengeMapper;
import com.development.sidecar.contract.ChannelResponseWriter;
import com.development.sidecar.contract.TokenRefResponse;
import com.development.sidecar.identity.AuthorizationOrchestrator;
import com.development.sidecar.identity.AuthorizationResult;
import com.development.sidecar.identity.RefusalKind;
import com.development.sidecar.observability.SidecarMetrics;
import com.development.sidecar.route.RouteDecision;
import com.development.sidecar.route.RouteResolver;
import io.micrometer.core.instrument.Timer;
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

    private static final String PASSTHROUGH_RULE = "passthrough";

    private static final String ERROR_BAD_REQUEST = "bad_request";
    private static final String ERROR_INVALID_REQUEST = "invalid_request";
    private static final String ERROR_DENIED = "denied";
    private static final String ERROR_SESSION_EXPIRED = "session_expired";
    private static final String ERROR_SESSION_REQUIRED = "session_required";
    private static final String ERROR_AUTHORIZATION_REQUIRED = "authorization_required";
    private static final String ERROR_PAYLOAD_TOO_LARGE = "payload_too_large";
    private static final String ERROR_BAD_GATEWAY = "bad_gateway";
    private static final String ERROR_UNAVAILABLE = "authorization_unavailable";

    private final RouteResolver routeResolver;
    private final RequestForwarder requestForwarder;
    private final AuthorizationOrchestrator orchestrator;
    private final ChannelResponseWriter responseWriter;
    private final ChallengeAnswerReader answerReader;
    private final SidecarMetrics metrics;
    private final ChannelProperties channelProperties;
    private final IdentityProperties identityProperties;
    private final ProxyProperties proxyProperties;

    public ProxyFilter(RouteResolver routeResolver,
                       RequestForwarder requestForwarder,
                       AuthorizationOrchestrator orchestrator,
                       ChannelResponseWriter responseWriter,
                       ChallengeAnswerReader answerReader,
                       SidecarMetrics metrics,
                       ChannelProperties channelProperties,
                       IdentityProperties identityProperties,
                       ProxyProperties proxyProperties) {

        this.routeResolver = routeResolver;
        this.requestForwarder = requestForwarder;
        this.orchestrator = orchestrator;
        this.responseWriter = responseWriter;
        this.answerReader = answerReader;
        this.metrics = metrics;
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

        } catch (Exception e) {
            unexpected(response, correlationId, e);

        } finally {
            if (!response.isCommitted()) {
                response.setHeader(proxyProperties.correlationHeader(), correlationId);
            }
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /**
     * Última barreira: o canal recebe o mesmo formato de sempre, aconteça o que
     * acontecer.
     * <p>
     * Sem isto, uma falha não prevista sobe para o contêiner e o canal recebe a
     * página de erro padrão — em HTML, sem identificador de correlação, e sem
     * nada que ligue o ocorrido aos registros do componente.
     */
    private void unexpected(HttpServletResponse response,
                            String correlationId,
                            Exception cause) {

        log.error("Falha não prevista no processamento da requisição");
        log.debug("Detalhe da falha não prevista", cause);

        try {
            responseWriter.error(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ERROR_UNAVAILABLE, correlationId);

        } catch (Exception writing) {
            log.error("Falha ao escrever a recusa da falha não prevista");
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
                responseWriter.error(response, HttpStatus.BAD_REQUEST, ERROR_BAD_REQUEST,
                        correlationId);
            }

            case PASSTHROUGH -> {
                log.debug("Rota fora da matriz, encaminhando sem verificação");
                forward(request, response, correlationId, Map.of(), null, PASSTHROUGH_RULE);
            }

            case INTERCEPT -> intercept(request, response, decision, correlationId);
        }
    }

    /**
     * Distingue os três momentos pela presença dos cabeçalhos do canal.
     */
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

    /**
     * Primeira apresentação: o corpo da transação segue ao provedor como veio, e
     * o código do autenticador, quando o canal já o tem, vem em cabeçalho.
     */
    private void start(HttpServletRequest request,
                       HttpServletResponse response,
                       RouteDecision decision,
                       String correlationId) throws IOException {

        byte[] payload;
        try {
            payload = requestForwarder.readBody(request);

        } catch (RequestForwarder.PayloadTooLargeException e) {
            responseWriter.error(response, HttpStatus.PAYLOAD_TOO_LARGE, ERROR_PAYLOAD_TOO_LARGE,
                    correlationId);
            return;
        }

        AuthorizationResult result = orchestrator.start(
                decision.rule().journey(),
                header(request, identityProperties.channelTokenHeader()),
                header(request, channelProperties.responseHeader()),
                payload,
                decision.metricTag());

        apply(request, response, result, decision, correlationId, payload);
    }

    /**
     * Resposta ao desafio: a sessão vem em cabeçalho, a resposta vem no corpo.
     * <p>
     * O corpo desta chamada não segue adiante — nem ao provedor, que recebe
     * apenas o callback, nem ao serviço de negócio, porque a chamada termina
     * aqui.
     */
    private void advance(HttpServletRequest request,
                         HttpServletResponse response,
                         String sessionId,
                         RouteDecision decision,
                         String correlationId) throws IOException {

        byte[] body;
        try {
            body = requestForwarder.readBody(request);

        } catch (RequestForwarder.PayloadTooLargeException e) {
            responseWriter.error(response, HttpStatus.PAYLOAD_TOO_LARGE, ERROR_PAYLOAD_TOO_LARGE,
                    correlationId);
            return;
        }

        AuthorizationResult result = orchestrator.advance(
                sessionId,
                answerReader.read(body),
                decision.metricTag());

        apply(request, response, result, decision, correlationId, null);
    }

    /**
     * Efetivação: troca a referência pelo token e encaminha.
     */
    private void effect(HttpServletRequest request,
                        HttpServletResponse response,
                        String tokenRef,
                        RouteDecision decision,
                        String correlationId) throws IOException {

        AuthorizationResult result = orchestrator.resolve(tokenRef, decision.metricTag());

        apply(request, response, result, decision, correlationId, null);
    }

    private void apply(HttpServletRequest request,
                       HttpServletResponse response,
                       AuthorizationResult result,
                       RouteDecision decision,
                       String correlationId,
                       byte[] payload) throws IOException {

        String rule = decision.metricTag();

        metrics.authorization(rule, result.type());

        switch (result.type()) {

            case CHALLENGE -> responseWriter.challenge(response,
                    ChallengeMapper.toChallenge(result.step()), correlationId);

            case AUTHORIZED -> responseWriter.authorized(response,
                    TokenRefResponse.of(result.reference().tokenRef()), correlationId);

            case RESOLVED -> forward(request, response, correlationId,
                    Map.of(channelProperties.tokenReferenceHeader(),
                            result.token().accessToken()),
                    payload, rule);

            case DENIED -> denied(response, result, correlationId);

            case EXPIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    ERROR_SESSION_EXPIRED, correlationId);

            case SESSION_REQUIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    ERROR_SESSION_REQUIRED, correlationId);

            case AUTHORIZATION_REQUIRED -> responseWriter.error(response, HttpStatus.UNAUTHORIZED,
                    ERROR_AUTHORIZATION_REQUIRED, correlationId);

            case BAD_REQUEST -> responseWriter.error(response, HttpStatus.BAD_REQUEST,
                    ERROR_BAD_REQUEST, correlationId);

            case UNAVAILABLE -> responseWriter.error(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ERROR_UNAVAILABLE, correlationId);
        }
    }

    /**
     * Traduz a recusa: o estado diz a família, o motivo diz a ação.
     * <p>
     * O motivo é nome próprio do componente, não o código do provedor: cada
     * jornada tem a sua numeração, e uma delas mudar não pode mudar o contrato do
     * canal junto.
     */
    private void denied(HttpServletResponse response,
                        AuthorizationResult result,
                        String correlationId) throws IOException {

        RefusalKind refusal = result.refusal() == null ? RefusalKind.DENIED : result.refusal();

        if (refusal.isRequestProblem()) {
            responseWriter.error(response, HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST,
                    refusal.reason(), correlationId);
            return;
        }

        responseWriter.error(response, HttpStatus.FORBIDDEN, ERROR_DENIED,
                refusal.reason(), correlationId);
    }

    private void forward(HttpServletRequest request,
                         HttpServletResponse response,
                         String correlationId,
                         Map<String, String> injected,
                         byte[] payload,
                         String rule) throws IOException {

        response.setHeader(proxyProperties.correlationHeader(), correlationId);

        Timer.Sample sample = metrics.start();

        try {
            requestForwarder.forward(request, response, injected, payload);

        } catch (RequestForwarder.InvalidTargetException e) {
            log.warn("Requisição recusada na construção do destino");
            responseWriter.error(response, HttpStatus.BAD_REQUEST, ERROR_BAD_REQUEST,
                    correlationId);

        } catch (RequestForwarder.PayloadTooLargeException e) {
            log.warn("Corpo acima do teto durante o encaminhamento");
            responseWriter.error(response, HttpStatus.PAYLOAD_TOO_LARGE, ERROR_PAYLOAD_TOO_LARGE,
                    correlationId);

        } catch (RequestForwarder.UpstreamException e) {
            log.error("Falha ao encaminhar ao serviço de negócio");
            log.debug("Detalhe da falha no encaminhamento", e);
            responseWriter.error(response, HttpStatus.BAD_GATEWAY, ERROR_BAD_GATEWAY,
                    correlationId);

        } finally {
            metrics.forwarded(sample, rule);
        }
    }

    private void rejectFraming(HttpServletResponse response,
                               RequestForwarder.RejectionReason reason,
                               String correlationId) throws IOException {

        HttpStatus status = reason == RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;

        log.warn("Requisição recusada antes da matriz: motivo={}", reason);

        responseWriter.error(response, status,
                status == HttpStatus.PAYLOAD_TOO_LARGE
                        ? ERROR_PAYLOAD_TOO_LARGE
                        : ERROR_BAD_REQUEST,
                correlationId);
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

        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return HttpMethod.valueOf(name);

        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}