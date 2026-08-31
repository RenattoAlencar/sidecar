# Autorização Transacional — Guia de Integração para o Canal

Este documento descreve o que o canal precisa fazer para chamar rotas
protegidas por autorização transacional.

---

## O que muda para o canal

**As URLs continuam as mesmas.** O componente de autorização fica na frente do
BFF e responde nos mesmos endereços. Não há endpoint novo para chamar, nem rota
adicional para configurar.

O que muda é que **algumas rotas passam a exigir autorização** antes de
efetivar a transação. Essas rotas são definidas por caminho e método — por
exemplo, `POST /api/v1/pix/transferencia`. As demais continuam funcionando
exatamente como hoje.

Para as rotas protegidas, a mesma chamada é feita **duas vezes**: a primeira
obtém a autorização, a segunda efetiva a transação.

### Por que a mesma chamada, e não um endpoint de autorização

Poderia haver um endereço separado só para autorizar. Optou-se por repetir a
chamada de negócio, por dois motivos.

**Nada muda no roteamento.** Um endereço novo exigiria rota nova no gateway de
borda, configuração de segurança própria e mudança em quem já chama. Repetindo
a rota existente, o canal continua chamando o que sempre chamou — o que decide
o comportamento são os cabeçalhos enviados.

**A autorização vale para aquela transação, e não para o usuário.** Autorizar
significa dizer "este pagamento, deste valor, para este destinatário, está
aprovado" — não "este usuário está autenticado". Por isso a transação inteira é
apresentada na primeira chamada: é ela que fica vinculada à autorização.

Daí decorre a regra do corpo idêntico. Se o valor ou o destinatário mudarem
entre autorizar e efetivar, o que foi aprovado não é o que está sendo
executado, e a transação é recusada adiante. É a proteção contra a troca de
dados entre as duas chamadas.

---

## Cabeçalhos

| Cabeçalho | Quando enviar | O que é |
|---|---|---|
| `x-porto-authentication` | Sempre | JWT do Cognito, como já é hoje |
| `x-porto-token` | 1ª chamada | Código do autenticador (6 dígitos) |
| `x-porto-authentication-am` | Chamada de efetivação | Referência recebida na 1ª chamada |
| `x-porto-authz-session` | Só ao responder um desafio | Sessão devolvida no 428 |
| `x-porto-correlation-id` | Opcional | Identificador para rastreio |

Os três primeiros cobrem o fluxo normal. O `x-porto-authz-session` só aparece
quando há desafio, explicado adiante.

---

## O fluxo em duas chamadas

### 1ª chamada — obter a autorização

Envie a transação normalmente, acrescentando o código do autenticador.

```http
POST /api/v1/pix/transferencia
Content-Type: application/json
x-porto-authentication: <JWT do Cognito>
x-porto-token: 149707

{
  "channel": {
    "endToEndId": "E60701190202402011402DY568TDHVZ3",
    "amount": { "currency": "BRL", "value": 10.25 },
    "destinationKey": { "value": "pix@banco.com.br", "type": "EMAIL" },
    "message": "Pagamento"
  },
  "risk": {
    "session_id": "...",
    "event_type": "transaction",
    "local_attrib_3": "Pix Chave"
  },
  "authN": {
    "transactionId": "..."
  }
}
```

**Resposta — 200:**

```json
{
  "status": "authorized",
  "tokenRef": "84da0844-d1f9-31f9-b4f4-79b420be8be4"
}
```

Guarde o `tokenRef`. Ele é o que autoriza a segunda chamada.

> Esta chamada **não efetiva a transação**. Ela apenas autoriza.

### 2ª chamada — efetivar

Repita a mesma chamada, agora com a referência no lugar do código.

```http
POST /api/v1/pix/transferencia
Content-Type: application/json
x-porto-authentication: <JWT do Cognito>
x-porto-authentication-am: 84da0844-d1f9-31f9-b4f4-79b420be8be4

{ ...o mesmo corpo da 1ª chamada... }
```

**Resposta:** a resposta normal do BFF. A transação foi efetivada.

---

## Regras importantes do corpo

**O corpo precisa ser idêntico nas duas chamadas.**

A autorização fica vinculada à transação específica que foi apresentada. Se
qualquer campo mudar entre a primeira e a segunda chamada — valor,
destinatário, chave —, a efetivação é recusada mais adiante.

Na prática: **guarde o corpo que você enviou e reenvie exatamente o mesmo.**
Não monte o JSON de novo a partir dos seus objetos, não recalcule campos, não
reordene, não reformate.

O ponto de atenção é que uma recusa por corpo divergente **não acontece no
componente de autorização** — ela acontece na efetivação, mais adiante, e o
erro não aponta para a causa. Reserializar o mesmo objeto pode mudar a ordem
das chaves ou o formato de um número sem que nada pareça diferente a olho nu, e
o sintoma aparece longe da origem.

**Envie o corpo completo**, com `channel`, `risk` e `authN`. Todos os três
blocos são considerados.

---

## Respostas possíveis

### 200 — Autorizado (1ª chamada)

```json
{ "status": "authorized", "tokenRef": "..." }
```

Siga para a segunda chamada.

### 403 — Recusado

```json
{ "error": "denied", "correlationId": "..." }
```

A autorização foi recusada. As causas mais comuns são código do autenticador
ausente, incorreto ou vencido.

**O que fazer:** peça um novo código ao usuário e recomece da primeira chamada.

> Quando o problema é o corpo, e não o código, a resposta é **400
> `invalid_request`** — nesse caso um código novo não resolve.

### 401 — Sessão

```json
{ "error": "session_required", "correlationId": "..." }
```
O JWT do Cognito não foi enviado. Envie o cabeçalho `x-porto-authentication`.

```json
{ "error": "authorization_required", "correlationId": "..." }
```
A referência apresentada não vale mais — pode ter sido usada, ter vencido, ou
não existir. **Recomece da primeira chamada.**

```json
{ "error": "session_expired", "correlationId": "..." }
```
A autorização demorou demais e a sessão venceu. **Recomece da primeira
chamada.**

### 400 — Requisição inválida

```json
{ "error": "bad_request", "correlationId": "..." }
```

O corpo está ausente ou a requisição está malformada.

```json
{ "error": "invalid_request", "correlationId": "..." }
```

O corpo apresentado não foi aceito pela autorização — falta campo obrigatório,
ou o corpo mudou entre a primeira e a segunda chamada.

**O que fazer:** um código novo não resolve. Verifique se o corpo está completo
e se é exatamente o mesmo nas duas chamadas.

### 503 — Indisponível

```json
{ "error": "authorization_unavailable", "correlationId": "..." }
```

O serviço de autorização não respondeu. **Tente novamente** — a transação não
foi efetivada e nada precisa ser desfeito.

---

## Desafio

Quando a jornada não conclui de imediato, ela emite um desafio: o usuário
precisa provar algo antes de a autorização sair. Nesse caso a resposta é **428**,
e o fluxo ganha uma chamada intermediária.

> **Na jornada transacional de hoje isto não acontece**, porque o código do
> autenticador é enviado logo na primeira chamada. A seção existe porque
> desafios estão previstos, e o formato já está definido.

### O que o canal recebe

```http
HTTP/1.1 428 Precondition Required
x-authz-required: true

{
  "authorizationRequired": true,
  "sessionId": "eyJ0eXAiOiJKV1Qi...",
  "challenge": { "type": "OTP", "provider": "AUTHFY" }
}
```

O `challenge.type` diz **o que** cumprir; o `provider`, **com quem**. O
`sessionId` identifica a jornada e precisa voltar na próxima chamada.

### O que o canal envia

Mesma rota, com a sessão em cabeçalho e a resposta no corpo:

```http
POST /api/v1/pix/transferencia
Content-Type: application/json
x-porto-authentication: <JWT do Cognito>
x-porto-authz-session: <o sessionId recebido>

{
  "authz": {
    "response": "149707"
  }
}
```

**O corpo desta chamada é só o bloco `authz`.** Não envie `channel`, `risk` nem
`authN` — a transação já foi apresentada na primeira chamada.

### O campo `response` por tipo de desafio

A forma é sempre a mesma; muda o conteúdo, conforme o `challenge.type` recebido.

| `type` | O que colocar em `response` |
|---|---|
| `OTP` | O código de 6 dígitos |
| `BIOMETRIA` | O retorno do SDK de biometria |
| `FIDO` | A assinatura produzida pelo dispositivo |

```json
{ "authz": { "response": "149707" } }
```

```json
{ "authz": { "response": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." } }
```

O componente não interpreta o valor — ele o apresenta à jornada como veio. Por
isso o campo é sempre texto, qualquer que seja o desafio.

### Depois da resposta

O desfecho é o mesmo da primeira chamada: **200** com o `tokenRef`, ou uma das
recusas descritas acima. Recebido o `tokenRef`, siga para a efetivação
normalmente.

Se vier outro **428**, há mais um desafio a cumprir: repita com o novo
`sessionId`.

---

## Rastreio

Envie `x-porto-correlation-id` para ligar seus registros aos do componente. Se
não enviar, um identificador é gerado e devolvido no mesmo cabeçalho.

Ele aparece no campo `correlationId` de toda resposta de erro — vale registrá-lo
para investigação.

---

## Resumo para quem vai implementar

1. Nas rotas protegidas, chame **duas vezes** com o **mesmo corpo**
2. Na 1ª chamada envie `x-porto-token` (código do autenticador)
3. Guarde o `tokenRef` da resposta
4. Na 2ª chamada envie `x-porto-authentication-am` com esse `tokenRef`
5. Se vier **428**, responda ao desafio: `x-porto-authz-session` em cabeçalho e
   `{"authz":{"response":"..."}}` no corpo
6. Em 403 ou 401, **recomece da primeira chamada** com um código novo
7. Em 400 `invalid_request`, **corrija o corpo** — código novo não resolve
8. Em 503, **repita** — nada foi efetivado
