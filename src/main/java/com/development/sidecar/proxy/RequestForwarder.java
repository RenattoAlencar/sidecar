package com.development.sidecar.proxy;

import com.development.sidecar.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RequestForwarder {

    private static final Logger log = LoggerFactory.getLogger(RequestForwarder.class);

    private static final String CONNECTION_HEADER = "Connection";
    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    private static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    private static final String HOST_HEADER = "Host";

    private static final char FRAGMENT_MARKER = '#';
    private static final char BACKSLASH = '\\';

    private static final int MAX_LOGGED_LENGTH = 64;

    private static final Set<String> BODYLESS_METHODS =
            Set.of("GET", "HEAD", "DELETE", "OPTIONS", "TRACE");

    private final HttpClient httpClient;
    private final ProxyProperties properties;
    private final ProxyHeaderPolicy headerPolicy;

    public RequestForwarder(HttpClient httpClient,
                            ProxyProperties properties,
                            ProxyHeaderPolicy headerPolicy) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.headerPolicy = headerPolicy;
    }

    public enum RejectionReason {
        AMBIGUOUS_FRAMING,
        PAYLOAD_TOO_LARGE
    }

    public Optional<RejectionReason> framingRejection(HttpServletRequest request) {

        boolean hasTransferEncoding = request.getHeader(TRANSFER_ENCODING_HEADER) != null;
        Set<String> declaredLengths = distinctValues(request.getHeaders(CONTENT_LENGTH_HEADER));

        if (hasTransferEncoding && !declaredLengths.isEmpty()) {
            return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
        }
        if (declaredLengths.size() > 1) {
            return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
        }
        if (declaredLengths.size() == 1) {
            long declared;
            try {
                declared = Long.parseLong(declaredLengths.iterator().next().trim());
            } catch (NumberFormatException e) {
                return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
            }
            if (declared < 0) {
                return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
            }
            if (declared > properties.maxBodyBytes()) {
                return Optional.of(RejectionReason.PAYLOAD_TOO_LARGE);
            }
        }
        return Optional.empty();
    }

    public byte[] readBody(HttpServletRequest request) throws IOException {

        if (BODYLESS_METHODS.contains(request.getMethod())) {
            return new byte[0];
        }
        try (InputStream body = new LimitedInputStream(request.getInputStream(),
                properties.maxBodyBytes())) {

            return body.readAllBytes();
        }
    }

    public void forward(HttpServletRequest request,
                        HttpServletResponse response,
                        Map<String, String> injected) throws IOException {

        forward(request, response, injected, null);
    }

    public void forward(HttpServletRequest request,
                        HttpServletResponse response,
                        Map<String, String> injected,
                        byte[] body) throws IOException {

        URI targetUri = buildTargetUri(request);
        HttpRequest upstreamRequest = buildUpstreamRequest(request, targetUri, injected, body);

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(upstreamRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Encaminhamento interrompido", e);

        } catch (IOException e) {
            if (containsPayloadTooLarge(e)) {
                throw new PayloadTooLargeException();
            }
            throw new UpstreamException("Falha ao contatar o serviço de negócio", e);
        }

        copyResponse(upstreamResponse, response);
    }

    private URI buildTargetUri(HttpServletRequest request) {

        URI target = properties.target();
        String base = target.toString();

        StringBuilder uri = new StringBuilder(
                base.endsWith("/") ? base.substring(0, base.length() - 1) : base);

        uri.append(request.getRequestURI());

        String queryString = request.getQueryString();

        if (queryString != null && !queryString.isEmpty()) {
            requireSafeQuery(queryString);
            uri.append('?').append(queryString);
        }

        URI candidate;
        try {
            candidate = new URI(uri.toString());
        } catch (URISyntaxException e) {
            log.warn("Destino não pôde ser construído a partir do caminho recebido");
            throw new InvalidTargetException("Destino inválido", e);
        }

        requireSameDestination(candidate, target);

        return candidate;
    }

    private static void requireSameDestination(URI candidate, URI target) {

        boolean sameDestination = target.getScheme() != null
                && target.getHost() != null
                && target.getScheme().equalsIgnoreCase(candidate.getScheme())
                && target.getHost().equalsIgnoreCase(candidate.getHost())
                && target.getPort() == candidate.getPort();

        if (!sameDestination) {
            log.error("Destino construído aponta fora do alvo configurado");
            throw new InvalidTargetException("Destino fora do alvo configurado");
        }
    }

    private static void requireSafeQuery(String queryString) {

        for (int index = 0; index < queryString.length(); index++) {
            char current = queryString.charAt(index);

            boolean unsafe = current == FRAGMENT_MARKER
                    || current == BACKSLASH
                    || current < 0x20
                    || current == 0x7F;

            if (unsafe) {
                log.warn("Consulta recusada por conter caractere não permitido");
                throw new InvalidTargetException("Consulta com caractere não permitido");
            }
        }
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest request,
                                             URI targetUri,
                                             Map<String, String> injected,
                                             byte[] body) throws IOException {

        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .timeout(properties.readTimeout())
                .method(request.getMethod(), bodyPublisher(request, body));

        copyRequestHeaders(request, builder);
        appendForwardedHeaders(request, builder);
        appendInjectedHeaders(builder, injected);

        return builder.build();
    }

    private void appendInjectedHeaders(HttpRequest.Builder builder,
                                       Map<String, String> injected) {

        if (injected == null || injected.isEmpty()) {
            return;
        }
        injected.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                return;
            }
            if (!headerPolicy.isReserved(name)) {
                log.warn("Cabeçalho escrito pelo componente não está reservado: {}",
                        sanitize(name));
            }
            builder.header(name, value);
        });
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request, byte[] body)
            throws IOException {

        if (BODYLESS_METHODS.contains(request.getMethod())) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (body != null) {
            return HttpRequest.BodyPublishers.ofByteArray(body);
        }
        InputStream stream = new LimitedInputStream(request.getInputStream(),
                properties.maxBodyBytes());

        return HttpRequest.BodyPublishers.ofInputStream(() -> stream);
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {

        Set<String> connectionTokens = ProxyHeaderPolicy.connectionTokens(
                Collections.list(request.getHeaders(CONNECTION_HEADER)).toArray(String[]::new));

        for (String headerName : Collections.list(request.getHeaderNames())) {

            if (headerPolicy.isReserved(headerName)) {
                log.warn("Cabeçalho reservado recebido do chamador e descartado: {}",
                        sanitize(headerName));
                continue;
            }
            if (!headerPolicy.isForwardable(headerName, connectionTokens)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(headerName))) {
                builder.header(headerName, value);
            }
        }
    }

    private void appendForwardedHeaders(HttpServletRequest request, HttpRequest.Builder builder) {

        String existingChain = joinValues(request.getHeaders(FORWARDED_FOR_HEADER));
        String remoteAddress = request.getRemoteAddr();

        builder.header(FORWARDED_FOR_HEADER,
                existingChain.isEmpty() ? remoteAddress : existingChain + ", " + remoteAddress);

        String proto = firstNonBlank(request.getHeader(FORWARDED_PROTO_HEADER),
                request.getScheme());

        if (proto != null) {
            builder.header(FORWARDED_PROTO_HEADER, proto);
        }

        String host = firstNonBlank(request.getHeader(FORWARDED_HOST_HEADER),
                request.getHeader(HOST_HEADER));

        if (host != null) {
            builder.header(FORWARDED_HOST_HEADER, host);
        }
    }

    private void copyResponse(HttpResponse<InputStream> upstreamResponse,
                              HttpServletResponse response) throws IOException {

        response.setStatus(upstreamResponse.statusCode());

        Set<String> connectionTokens = ProxyHeaderPolicy.connectionTokens(
                upstreamResponse.headers().allValues(CONNECTION_HEADER).toArray(String[]::new));

        upstreamResponse.headers().map().forEach((headerName, values) -> {
            if (headerPolicy.isReserved(headerName)
                    || !headerPolicy.isForwardable(headerName, connectionTokens)) {
                return;
            }
            values.forEach(value -> response.addHeader(headerName, value));
        });

        try (InputStream upstreamBody = upstreamResponse.body();
             OutputStream clientBody = response.getOutputStream()) {

            upstreamBody.transferTo(clientBody);

        } catch (IOException e) {
            log.warn("Falha ao transferir a resposta após o envio do status");
            throw e;
        }
    }

    private static String sanitize(String value) {

        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), MAX_LOGGED_LENGTH);

        StringBuilder clean = new StringBuilder(length);

        for (int index = 0; index < length; index++) {
            char current = value.charAt(index);

            clean.append(current < 0x20 || current == 0x7F ? '.' : current);
        }
        return clean.toString();
    }

    private static String joinValues(Enumeration<String> values) {
        if (values == null) {
            return "";
        }
        return Collections.list(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private static Set<String> distinctValues(Enumeration<String> values) {
        Set<String> distinct = new LinkedHashSet<>();

        if (values == null) {
            return distinct;
        }
        Collections.list(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(distinct::add);

        return distinct;
    }

    private static boolean containsPayloadTooLarge(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }

    static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        LimitedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();

            if (value != -1) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);

            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(long amount) throws IOException {
            count += amount;

            if (count > limit) {
                throw new PayloadTooLargeException();
            }
        }
    }

    public static class PayloadTooLargeException extends IOException {

        public PayloadTooLargeException() {
            super("Corpo da requisição acima do teto configurado");
        }
    }

    public static class UpstreamException extends RuntimeException {

        public UpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InvalidTargetException extends RuntimeException {

        public InvalidTargetException(String message) {
            super(message);
        }

        public InvalidTargetException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}