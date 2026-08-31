# Sidecar — Guia de Desenvolvimento e Diagnóstico

Como rodar, o que olhar quando algo falha, e por onde passa cada requisição.

---

## Mapa das classes

Uma requisição percorre estes pontos, nesta ordem:

```
ProxyFilter               porta de entrada, decide o caminho
 ├─ RequestForwarder      recusa por delimitação, lê o corpo, encaminha
 ├─ RouteResolver         a rota é verificada?
 ├─ AuthorizationOrchestrator
 │   ├─ AuthenticationJourneyClient   passos da jornada
 │   ├─ TokenIssuer                   código de autorização e troca
 │   └─ TokenCustodian                guarda e recupera
 └─ ChannelResponseWriter  escreve a resposta ao canal
```

### Onde cada coisa acontece

| Preciso entender... | Olhe em |
|---|---|
| Por que a rota não foi verificada | `RouteResolver` |
| Por que o cabeçalho não chegou ao BFF | `ProxyHeaderPolicy` |
| O que foi enviado à jornada | `JourneyRequest` |
| Como a recusa virou resposta | `JourneyRefusal` → `RefusalKind` → `ProxyFilter.denied` |
| Como o desafio virou 428 | `ChallengeMapper` |
| Por que a resposta ao desafio não foi lida | `ChallengeAnswerReader` |
| Por que o token não foi emitido | `HttpTokenIssuer` |
| Por que a credencial foi recusada | `ServiceCredentialsProvider` |

### Fronteiras que valem respeitar

- **`identity`** fala o dialeto do provedor. Estrutura de callback, `authId` e
  nomes de campo do provedor vivem aqui e não saem.
- **`contract`** fala o dialeto do canal. É onde a tradução acontece.
- **`proxy`** decide e encaminha. Não conhece jornada.
- **`route`** só decide se a rota é verificada.

Se você precisar mencionar `callbacks` fora de `identity`, provavelmente há um
vazamento de abstração.

---

## Rodar localmente

### O que precisa estar de pé

1. **Um alvo na porta do BFF.** Qualquer serviço que responda serve. Para
   diagnóstico, um que ecoe os cabeçalhos recebidos ajuda muito — é como se
   confere que a referência virou token.

2. **Acesso ao provedor de identidade** e ao **guardião de token** em
   homologação.

### Variáveis mínimas

```
PROXY_TARGET=http://127.0.0.1:8082
IDENTITY_BASE_URL=<provedor>
IDENTITY_CLIENT_ID=<cliente>
IDENTITY_CLIENT_SECRET=<segredo>
IDENTITY_SCOPES=<os cadastrados no cliente>
IDENTITY_SESSION_COOKIE_NAME=<cookie de sessão>
IDENTITY_REDIRECT_URI=<o mesmo cadastrado no cliente>
SERVICE_CREDENTIALS_URL=<sso>
SERVICE_CREDENTIALS_USERNAME=<usuário>
SERVICE_CREDENTIALS_PASSWORD=<senha>
TOKEN_HANDLER_URL=<guardião>
LOG_LEVEL_SIDECAR=DEBUG
```

### Opção da máquina virtual

Quando `service-credentials.host-header` está preenchido:

```
-Djdk.httpclient.allowRestrictedHeaders=host
```

**Confira a grafia.** A plataforma ignora propriedade desconhecida sem
reclamar, e o efeito de errar uma letra é o cabeçalho ser descartado em
silêncio — a credencial sai pela identidade errada e o guardião a recusa com
uma mensagem que fala de credencial revogada.

---

## Cenários

Substitua `{{host}}`, `{{jwt}}` e `{{otp}}`. O código do autenticador expira em
30 segundos — gere e use na sequência.

### 1. Passthrough

Rota fora da lista. Confirma que o proxy funciona sem autorização no caminho.

```bash
curl -i -X POST '{{host}}/api/v1/rota-qualquer' \
  -H 'Content-Type: application/json' \
  -d '{"teste": true}'
```

**Esperado:** a resposta do alvo, inalterada.
**No log:** `Rota fora da matriz, encaminhando sem verificação`

### 2. Rota verificada, sem código

```bash
curl -i -X POST '{{host}}/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -d '{"channel":{"amount":{"currency":"BRL","value":10.25}},"risk":{"event_type":"transaction"}}'
```

**Esperado:** `403 {"error":"denied"}` — a jornada transacional exige o código
no início.
**No log:** `Jornada recusada no passo de início: 002`

### 3. Autorização completa

```bash
curl -i -X POST '{{host}}/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -H 'x-porto-token: {{otp}}' \
  -d '{"channel":{"amount":{"currency":"BRL","value":10.25}},"risk":{"event_type":"transaction"},"authN":{"transactionId":"..."}}'
```

**Esperado:** `200 {"status":"authorized","tokenRef":"..."}`
**No log:** início → `PAYLOAD_REQUIRED` → corpo apresentado → concluída → token
emitido → sob guarda

### 4. Efetivação

```bash
curl -i -X POST '{{host}}/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -H 'x-porto-authentication-am: {{tokenRef}}' \
  -d '{"channel":{"amount":{"currency":"BRL","value":10.25}},"risk":{"event_type":"transaction"},"authN":{"transactionId":"..."}}'
```

**Esperado:** a resposta do alvo.
**Confirme no alvo:** o cabeçalho `x-porto-authentication-am` deve chegar com o
**token**, não com a referência. É a verificação central do componente.

### 5. Referência desconhecida

```bash
curl -i -X POST '{{host}}/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -H 'x-porto-authentication-am: 00000000-0000-0000-0000-000000000000' \
  -d '{"channel":{},"risk":{}}'
```

**Esperado:** `401 {"error":"authorization_required"}`

### 6. Caminho recusado na normalização

```bash
curl -i -X POST '{{host}}/api/v1/pix/../pix/transferencia' \
  -H 'Content-Type: application/json' \
  -d '{}'
```

**Esperado:** `400 {"error":"bad_request"}`
**No log:** `Requisição recusada na normalização: motivo=caminho com salto de diretório`

### 7. Delimitação ambígua

```bash
curl -i -X POST '{{host}}/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'Transfer-Encoding: chunked' \
  -H 'Content-Length: 20' \
  -d '{"teste":true}'
```

**Esperado:** `400`, recusado antes de qualquer verificação.

### 8. Desafio

Exige uma jornada que emita desafio no meio. Primeiro obtenha a sessão:

```bash
curl -i -X POST '{{host}}/api/v1/fator/cadastro' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -d '{"inicio":true}'
```

**Esperado:** `428` com `sessionId` e `challenge`.

Depois responda:

```bash
curl -i -X POST '{{host}}/api/v1/fator/cadastro' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: {{jwt}}' \
  -H 'x-porto-authz-session: {{sessionId}}' \
  -d '{"authz":{"response":"{{resposta}}"}}'
```

---

## Diagnóstico

### O log conta a história

Com `LOG_LEVEL_SIDECAR=DEBUG`, uma autorização bem-sucedida deixa este rastro:

```
Rota interceptada: regra=<regra>
Iniciando a jornada '<jornada>' no realm '<realm>'
Código de autenticador apresentado ao provedor
Passo de início recebeu desafio
Apresentando o corpo da transação à jornada
Jornada concluída no passo de corpo da transação
Solicitando o código de autorização
Token emitido pelo provedor
Credencial de serviço obtida, emitida por <emissor>
Entregando o token sob guarda
Token sob guarda
Autorização concluída: regra=<regra>
```

**Onde parou é onde está o problema.** Cada linha ausente aponta um trecho.

### Sintomas e causas

| Sintoma | Onde olhar |
|---|---|
| `404` sem log nenhum | Caminho do contexto configurado, ou o filtro não registrado |
| `Rota fora da matriz` numa rota que deveria ser verificada | Indentação de `intercept-rules` no arquivo de configuração; caminho e método exatos |
| `Nenhuma rota verificada configurada` na subida | A lista não foi lida — quase sempre indentação |
| `Jornada recusada: 002` mesmo enviando o código | O cabeçalho do código não está configurado, ou o valor venceu |
| `Autorização não concedeu código` | Escopo, `redirect_uri` ou cliente. O registro mostra os parâmetros que vieram no lugar |
| `Token has been revoked` no guardião | Credencial de serviço emitida pela identidade errada. Confira o emissor no registro |
| `Provedor respondeu status 500` no passo do corpo | O callback foi enviado incompleto — precisa voltar como o provedor o emitiu |
| `Continuação sem resposta ao desafio` | O corpo não trouxe `authz.response`, ou está malformado |
| `Recusa sem código no detalhe` | A jornada não usa numeração; a mensagem diz mais |

### Aumentar o detalhe

```
LOG_LEVEL_SIDECAR=DEBUG          decisões do componente
LOG_LEVEL_HTTP=DEBUG             corpo e cabeçalhos das chamadas
```

**O segundo mostra o token do canal, o código do autenticador e o corpo da
transação.** Use na estação de trabalho e em nenhum outro lugar.

### Métricas

```
GET http://localhost:8090/actuator/prometheus
```

Só aparecem depois da primeira requisição — o medidor é registrado no primeiro
uso.

```
sidecar_authorization_total{rule="...",outcome="..."}
sidecar_forward_seconds{rule="..."}
```

---

## Testes de integração

As classes em `identity` exercitam a cadeia contra os serviços reais, sem
passar pelo componente HTTP. Úteis para isolar: se o teste passa e a chamada
por HTTP falha, o problema está no proxy.

| Teste | Cobre |
|---|---|
| `JourneyIntegrationTest` | jornada e apresentação do corpo |
| `TokenIssuanceIntegrationTest` | jornada e emissão do token |
| `TokenCustodyIntegrationTest` | cadeia completa, incluindo a guarda |

**Antes de enviar ao repositório:** remova o token do canal e o código do
autenticador das classes de teste. Prefira variáveis de ambiente na
configuração de execução.

---

## Armadilhas conhecidas

**Reserializar o corpo.** O provedor calcula um resumo sobre o corpo
apresentado. O componente o trata como bytes do início ao fim; converter para
objeto e de volta muda o resumo sem mudar a transação, e a falha aparece longe
da origem.

**Remontar o callback.** O provedor espera de volta a estrutura que emitiu.
Enviar só a parte que mudou fez o provedor responder 500 — verificado em teste.

**Cabeçalho injetado fora da lista de reservados.** O valor do chamador
atravessa junto e o destino pode ler o errado. O componente avisa no registro
quando isso acontece.

**Corpo consumido duas vezes.** O fluxo de entrada só pode ser lido uma vez. Nas
rotas verificadas ele é retido em memória; nas demais segue direto. Ler o corpo
num caminho que também encaminha exige a versão retida.

**Espera ativa.** O componente não faz polling, por decisão de projeto. Jornadas
que dependem disso não são atendidas.
