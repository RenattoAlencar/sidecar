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

**Verificado:** 200 com a resposta do serviço.

| Onde | O que aparece |
|---|---|
| Log do componente | *Rota fora da matriz, encaminhando sem verificação* |
| Log do serviço | os cabeçalhos e o corpo, como enviados |

A rota não está na matriz, então nada é exigido — nem o código, nem a jornada.

---

## 2 — Rota protegida sem autorização

```bash
curl -i -X POST 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{JWT}}' \
  -d '{"channel":{"amount":{"currency":"BRL","value":10.25}},"risk":{"event_type":"transaction"}}'
```

**Verificado:** 403 com `reason: code_required`.

```json
{
  "error": "denied",
  "reason": "code_required",
  "correlationId": "..."
}
```

| Onde | O que aparece |
|---|---|
| Log do componente | *Jornada recusada no passo de início: 002* |
| Log do serviço | nada — a chamada não chega até ele |

O código do provedor (`002`) fica no registro; o canal recebe apenas a ação que
lhe cabe. E a transação não alcança o serviço de negócio: a recusa acontece
antes do encaminhamento.

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

**Verificado:** 428 com o desafio traduzido.

```json
{
  "authorizationRequired": true,
  "sessionId": "eyJ0eXAiOiJKV1Qi...",
  "challenge": {
    "type": "DEEPLINK",
    "target": "portosuperapp://porto/portobank/pdc?...",
    "retryAfter": 8000
  }
}
```

A jornada consultiva não conclui na sessão do componente: ela emite um endereço
a abrir e aguarda que outro aplicativo resolva. O que este cenário prova é que a
rota foi interceptada e o desafio traduzido — não que a autorização conclui.

Para ver o ciclo completo, reapresente a sessão sem resposta no corpo (cenário
5.1). Sem alguém abrindo o endereço, o desafio se repete até expirar.

---

## 5 — Consulta com identificador

Exercita o segmento variável da matriz.

```bash
curl -i -X GET 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-token: {{OTP}}'
```

**Verificado:** 428 com `type: DEEPLINK`, como no cenário 4 — a rota com
segmento variável aponta para a mesma jornada consultiva.

O que este cenário prova é que **o segmento variável casa**: sem ele, a rota
teria atravessado sem verificação.

### 5.1 Reapresentar a sessão

Enquanto o desafio não é cumprido, o ciclo se repete:

```bash
curl -i -X GET 'http://localhost:8080/api/v1/pix/chaves/abc123' \
  -H 'x-porto-authentication: {{JWT}}' \
  -H 'x-porto-authz-session: {{SESSION_ID}}'
```

**Esperado:** outro 428, com o mesmo `sessionId`. Ao concluir do outro lado,
200 com `tokenRef`.

---

## 6 — Substituição

### 6.1 Autorizar

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

**Verificado:** 200 com `tokenRef` — o mesmo desfecho da transferência.

O serviço de negócio **não é chamado** nesta etapa: a autorização termina no
componente. O log da chave só aparece na efetivação.

### 6.2 Efetivar

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

**Confira no log do serviço:** *Chave a substituir: abc123*, e o corpo enviado.

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

**Verificado:** 200 com `tokenRef`.

Como no cenário 6, o serviço não é chamado na autorização. Para vê-lo receber a
chamada — e registrar a chave —, efetive com o `tokenRef`, trocando
`x-porto-token` por `x-porto-authentication-am`.

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

**Verificado:** o corpo chega ao serviço de negócio — antes da mudança na lista
de métodos sem corpo, ele era descartado.

**Onde conferir:**

| Onde | O que procurar |
|---|---|
| Log do serviço | a linha `corpo:` com o conteúdo enviado |
| Log do componente, em DEBUG | *Apresentando o corpo da transação à jornada* |
| Resposta | 200 com `tokenRef` confirma que a jornada aceitou o corpo |

Uma recusa com `reason: payload_invalid` indicaria que o corpo chegou, mas não
no formato que a jornada espera — o que também prova que ele não foi
descartado.

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

### 10.2 Delimitação ambígua — não exercitável por chamada normal

O componente recusa requisições que declaram comprimento e codificação juntos,
antes de qualquer verificação. **Mas isso não se testa por curl:** ele gerencia
esses dois cabeçalhos sozinho, sobrescrevendo o que se declara, e apenas um
deles chega.

Montando a requisição crua, o container recusa antes de o componente ver — a
proteção existe em duas camadas, e a de fora responde primeiro.

A verificação fica com o teste unitário: `RequestForwarderTest`, casos
`recusa_declaracao_dupla` e `recusa_comprimentos_divergentes`.

### 10.3 Verbo desconhecido — não exercitável por chamada normal

```bash
curl -i -X PROPFIND 'http://localhost:8080/api/v1/pix/transferencia' \
  -H 'x-porto-authentication: {{JWT}}'
```

**Verificado:** 405, do container. O verbo não chega ao componente — o servidor
o recusa antes.

O tratamento no componente permanece como defesa em profundidade, para o caso de
um verbo não padrão ser aceito pelo container.

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
| 10.1 | Caminho com salto de diretório | **não** |
| 10.2 | Delimitação ambígua | não exercitável — teste unitário |
| 10.3 | Verbo desconhecido | recusado pelo container |
| 11 | Rastreio | **não** |

Os marcados como não exercitados são os que valem atenção — vários cobrem código
alterado recentemente.

**Sobre os cenários 4 e 5:** ambos terminam em 428 com `DEEPLINK`, porque a
jornada consultiva não conclui na sessão do componente. Isso é o comportamento
correto da jornada, não uma falha — o que se verifica ali é a interceptação e a
tradução do desafio.