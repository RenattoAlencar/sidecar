# Sidecar — Roteiro de Testes

Substitua `{{JWT}}` pelo token do provedor de borda e `{{OTP}}` pelo código do
autenticador — ele vence em 30 segundos, então gere na hora.

O componente atende em `localhost:8080`; o serviço de negócio simulado, em
`localhost:8082`.

---

## Preparação

### O que precisa estar de pé

```
[ curl ]  →  [ componente :8080 ]  →  [ serviço de negócio :8082 ]
                     ↓
            [ provedor de identidade ]
            [ guardião de token ]
```

O **curl faz o papel do canal**. O serviço de negócio é simulado por um
controller de eco, que devolve o que recebeu — é por ele que se confere o que
chegou depois de atravessar o componente.

### O serviço de negócio simulado

Suba uma aplicação Spring Boot em `8082` com o controller abaixo. Ele cobre cada
forma que a matriz de interceptação aceita: caminho exato, caminho com segmento
variável, e métodos com e sem corpo.

```java
package com.example.demo.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serviço de negócio simulado.
 * <p>
 * Devolve o que recebeu — método, caminho, cabeçalhos e corpo — para que se
 * possa conferir o que chegou depois de atravessar o componente. É por aqui que
 * se verifica o que mais importa: a referência do canal precisa ter virado a
 * credencial no cabeçalho de autorização.
 */
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

    /**
     * O corpo é opcional de propósito: serviços mais antigos o enviam em
     * exclusões, e é isso que se quer poder verificar aqui.
     */
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
        return ResponseEntity.ok().header("Allow", "GET, POST, HEAD, OPTIONS").build();
    }

    /**
     * Rota fora da matriz: confirma que o não declarado atravessa como antes.
     */
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
```

E no `application.properties` dele:

```
server.port=8082
```

### A matriz de interceptação

No componente, as regras que este roteiro exercita:

```yaml
proxy:
  target: http://127.0.0.1:8082

  intercept-rules:
    - name: pix-transfer
      path: /api/v1/pix/transferencia
      methods: [ POST ]
      journey: app-bank-authz-transacional

    - name: pix-chaves-consulta
      path: /api/v1/pix/chaves
      methods: [ GET ]
      journey: pdc-bank-authz-consultivo

    - name: pix-chaves-cadastro
      path: /api/v1/pix/chaves
      methods: [ POST ]
      journey: app-bank-authz-transacional

    - name: pix-chave-consulta
      path: /api/v1/pix/chaves/{chave}
      methods: [ GET ]
      journey: pdc-bank-authz-consultivo

    - name: pix-chave-alteracao
      path: /api/v1/pix/chaves/{chave}
      methods: [ PUT, PATCH, DELETE ]
      journey: app-bank-authz-transacional
```

> **O mesmo caminho aparece com jornadas diferentes por método.** Consultar e
> alterar chegam pelo mesmo endereço e não exigem a mesma prova.
>
> E `/api/v1/pix/validacao` fica de fora: é o que confirma o passthrough.

---

## 1 — Passthrough

Rota fora da matriz. Atravessa sem verificação.

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/validacao' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{"chave":"maria@banco.com.br"}'
```

**Esperado:** 200 com a resposta do serviço.
**No log do componente:** *Rota fora da matriz, encaminhando sem verificação*

---

## 2 — Rota protegida sem autorização

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{"channel":{"amount":{"currency":"BRL","value":10.25}},"risk":{"event_type":"transaction"}}'
```

**Esperado:** 403 com `reason: code_required`.

---

## 3 — Transferência autorizada

### 3.1 Autorizar

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{
        "channel": {
          "endToEndId": "E60701190202402011402DY568TDHVZ3",
          "amount": { "currency": "BRL", "value": 500.00 },
          "destinationKey": { "value": "maria@banco.com.br", "type": "EMAIL" },
          "message": "Pagamento"
        },
        "risk": { "session_id": "sessao-de-teste", "event_type": "transaction" },
        "authN": { "transactionId": "transacao-de-teste" }
      }'
```

**Esperado:** 200 com `tokenRef`.

### 3.2 Efetivar

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-authentication-am: {{TOKEN_REF}}' \
  -d '{
        "channel": {
          "endToEndId": "E60701190202402011402DY568TDHVZ3",
          "amount": { "currency": "BRL", "value": 500.00 },
          "destinationKey": { "value": "maria@banco.com.br", "type": "EMAIL" },
          "message": "Pagamento"
        },
        "risk": { "session_id": "sessao-de-teste", "event_type": "transaction" },
        "authN": { "transactionId": "transacao-de-teste" }
      }'
```

**Esperado:** a resposta do serviço.
**Confira no log do serviço:** o `x-porto-authentication-am` chegou com a
credencial, não com a referência.

---

## 4 — Consulta sem identificador

```bash
curl -i -X GET 'http://localhost:8080/api/v1/pix/chaves' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}'
```

**Esperado:** conforme a jornada configurada — 200 com `tokenRef`, ou 428 se
ela emitir desafio.

---

## 5 — Consulta com identificador

Exercita o segmento variável da matriz.

```bash
curl -i -X GET 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}'
```

**Confira no log do serviço:** *Chave solicitada: abc123*

---

## 6 — Substituição

```bash
curl -i -X PUT 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{
        "channel": { "chave": "maria@banco.com.br", "tipo": "EMAIL" },
        "risk": { "event_type": "key_update" },
        "authN": {}
      }'
```

Depois, a efetivação:

```bash
curl -i -X PUT 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-authentication-am: {{TOKEN_REF}}' \
  -d '{
        "channel": { "chave": "maria@banco.com.br", "tipo": "EMAIL" },
        "risk": { "event_type": "key_update" },
        "authN": {}
      }'
```

---

## 7 — Alteração parcial

```bash
curl -i -X PATCH 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{
        "channel": { "tipo": "EMAIL" },
        "risk": { "event_type": "key_update" },
        "authN": {}
      }'
```

---

## 8 — Exclusão com corpo

Exercita o corpo em `DELETE`, que antes era descartado.

```bash
curl -i -X DELETE 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{
        "channel": { "chave": "maria@banco.com.br" },
        "risk": { "event_type": "key_delete" },
        "authN": {}
      }'
```

**O que se verifica:** o corpo precisa aparecer no log do componente sendo
apresentado à jornada. Se aparecer vazio, a exclusão continua descartando.

---

## 9 — Recusas

### 9.1 Sem código

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** 403, `reason: code_required`.

### 9.2 Código incorreto

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: 000000' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** 403, `reason: code_invalid`.

### 9.3 Sem autenticação

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** 401, `session_required`.

### 9.4 Sessão vencida

Use um token do provedor de borda já expirado.

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT_VENCIDO}}' \
  -H 'x-porto-token: {{OTP}}' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** 401, `session_expired` — e **não** 403. Pedir código para sessão
vencida não conclui.

### 9.5 Referência desconhecida

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-authentication-am: 00000000-0000-0000-0000-000000000000' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** 401, `authorization_required`.

### 9.6 Referência já usada

Repita a chamada 3.2 com o mesmo `tokenRef`.

**Esperado:** 401, `authorization_required` — a referência vale uma vez.

---

## 10 — Recusas na normalização

### 10.1 Caminho com salto de diretório

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/../pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{}'
```

**Esperado:** 400, `bad_request`.

### 10.2 Delimitação ambígua

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'Transfer-Encoding: chunked' \
  -H 'Content-Length: 20' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{"teste":true}'
```

**Esperado:** 400, recusado antes de qualquer verificação.

### 10.3 Verbo desconhecido

```bash
curl -i -X PROPFIND 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'x-porto-authentication: {{JWT}}'
```

**Esperado:** 400, `bad_request`.

---

## 11 — Rastreio

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/validacao' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-correlation-id: minha-correlacao-123' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{}'
```

**Esperado:** o mesmo valor de volta no cabeçalho de resposta, e nas linhas de
registro do componente.

E com um valor que não serve:

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/validacao' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-correlation-id: valor com espaco' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{}'
```

**Esperado:** um identificador novo, gerado pelo componente.

---

## Ordem sugerida

| # | Cenário | Já exercitado |
|---|---|---|
| 1 | Passthrough | sim |
| 3 | Transferência | sim |
| 4 | Consulta sem identificador | sim |
| 5 | Consulta com identificador | **não** |
| 6 | Substituição | **não** |
| 7 | Alteração parcial | **não** |
| 8 | Exclusão com corpo | **não** |
| 9 | Recusas | parcialmente |
| 10 | Normalização | **não** |
| 11 | Rastreio | **não** |

Os marcados como não exercitados são os que valem atenção — vários cobrem código
alterado recentemente.