package com.development.sidecar;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/pix")
public class EchoController {

    private static final Logger log = LoggerFactory.getLogger(EchoController.class);


    @PostMapping("/transferencia")
    public ResponseEntity<Map<String, Object>> transferir(HttpServletRequest request,
                                                          @RequestBody(required = false)
                                                          String body) {

        return ResponseEntity.ok(echo(request, body));
    }

    @GetMapping("/chaves")
    public ResponseEntity<Map<String, Object>> listarChaves(HttpServletRequest request) {

        return ResponseEntity.ok(echo(request, null));
    }

    @PostMapping("/chaves")
    public ResponseEntity<Map<String, Object>> cadastrarChave(HttpServletRequest request,
                                                              @RequestBody(required = false)
                                                              String body) {

        return ResponseEntity.ok(echo(request, body));
    }

    @GetMapping("/chaves/{chave}")
    public ResponseEntity<Map<String, Object>> consultarChave(@PathVariable String chave,
                                                              HttpServletRequest request) {

        log.info("Chave solicitada: {}", chave);

        return ResponseEntity.ok(echo(request, null));
    }

    @PutMapping("/chaves/{chave}")
    public ResponseEntity<Map<String, Object>> substituirChave(@PathVariable String chave,
                                                               HttpServletRequest request,
                                                               @RequestBody(required = false)
                                                               String body) {

        log.info("Chave a substituir: {}", chave);

        return ResponseEntity.ok(echo(request, body));
    }

    @PatchMapping("/chaves/{chave}")
    public ResponseEntity<Map<String, Object>> alterarChave(@PathVariable String chave,
                                                            HttpServletRequest request,
                                                            @RequestBody(required = false)
                                                            String body) {

        log.info("Chave a alterar: {}", chave);

        return ResponseEntity.ok(echo(request, body));
    }

    @DeleteMapping("/chaves/{chave}")
    public ResponseEntity<Map<String, Object>> excluirChave(@PathVariable String chave,
                                                            HttpServletRequest request,
                                                            @RequestBody(required = false)
                                                            String body) {

        log.info("Chave a excluir: {}", chave);

        return ResponseEntity.ok(echo(request, body));
    }

    @RequestMapping(value = "/chaves", method = RequestMethod.HEAD)
    public ResponseEntity<Void> verificarChaves(HttpServletRequest request) {

        echo(request, null);

        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/chaves", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> metodosSuportados(HttpServletRequest request) {

        echo(request, null);

        return ResponseEntity.ok()
                .header("Allow", "GET, POST, HEAD, OPTIONS")
                .build();
    }

    @PostMapping("/validacao")
    public ResponseEntity<Map<String, Object>> validar(HttpServletRequest request,
                                                       @RequestBody(required = false)
                                                       String body) {

        return ResponseEntity.ok(echo(request, body));
    }

    private Map<String, Object> echo(HttpServletRequest request, String body) {

        Map<String, String> headers = new LinkedHashMap<>();

        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(), request.getHeader(name));
        }

        log.info("Recebido: {} {}", request.getMethod(), request.getRequestURI());
        headers.forEach((name, value) -> log.info("  {}: {}", name, value));
        log.info("  corpo: {}", body == null ? "(sem corpo)" : body);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recebido", true);
        response.put("metodo", request.getMethod());
        response.put("caminho", request.getRequestURI());
        response.put("consulta", request.getQueryString());
        response.put("headers", headers);
        response.put("corpo", body);

        return response;
    }
}