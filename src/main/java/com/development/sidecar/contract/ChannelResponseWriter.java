package com.development.sidecar.contract;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escreve ao canal as respostas que não vêm do serviço de negócio.
 * <p>
 * Fora do encaminhamento, tudo que o canal recebe é escrito aqui: o desafio, a
 * referência do token e as recusas.
 */
public class ChannelResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ChannelResponseWriter.class);

    /**
     * Distingue desafio de erro sem depender do corpo.
     */
    public static final String AUTHORIZATION_REQUIRED_HEADER = "x-authz-required";

    private static final String ERROR_FIELD = "error";
    private static final String CORRELATION_FIELD = "correlationId";

    private final ObjectMapper objectMapper;
    private final String correlationHeader;

    public ChannelResponseWriter(ObjectMapper objectMapper, String correlationHeader) {
        this.objectMapper = objectMapper;
        this.correlationHeader = correlationHeader;
    }

    /**
     * Apresenta o desafio.
     * <p>
     * Não usa o mesmo estado de recusa da sessão do canal: os dois pedem ações
     * opostas — renovar a sessão, ou cumprir um desafio — e o canal precisa
     * distingui-los sem inspecionar o corpo.
     */
    public void challenge(HttpServletResponse response,
                          ChallengeResponse challenge,
                          String correlationId) throws IOException {

        response.setHeader(AUTHORIZATION_REQUIRED_HEADER, "true");

        write(response, HttpStatus.PRECONDITION_REQUIRED, challenge, correlationId);
    }

    public void authorized(HttpServletResponse response,
                           TokenRefResponse authorized,
                           String correlationId) throws IOException {

        write(response, HttpStatus.OK, authorized, correlationId);
    }

    /**
     * Recusa com um motivo curto, sem detalhe do provedor.
     * <p>
     * O que falhou dentro fica no registro: o canal recebe o que muda a ação
     * dele, e nada sobre a mecânica da autorização.
     */
    public void error(HttpServletResponse response,
                      HttpStatus status,
                      String error,
                      String correlationId) throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ERROR_FIELD, error);
        body.put(CORRELATION_FIELD, correlationId);

        write(response, status, body, correlationId);
    }

    private void write(HttpServletResponse response,
                       HttpStatus status,
                       Object body,
                       String correlationId) throws IOException {

        if (response.isCommitted()) {
            log.warn("Resposta já iniciada, o componente não pôde escrever: status={}",
                    status.value());
            return;
        }

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(correlationHeader, correlationId);

        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.flushBuffer();
    }
}