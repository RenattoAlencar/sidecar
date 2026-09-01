# Autorização Transacional — Guia do Canal

Nos exemplos, `<org>` é o prefixo de cabeçalho da organização e `<seu-servico>`
o endereço do seu serviço.

---

## 1. Configurar

Você informa três coisas.

### Onde seu serviço atende

O componente assume a porta que seu serviço expunha; seu serviço muda para
outra.

```
sidecarPort: 8080                  # a porta que seu serviço expunha
proxy.target: http://127.0.0.1:8081  # onde seu serviço passou a atender
```

### Quais operações exigem autorização

O componente espera receber a lista neste formato:

```yaml
proxy:
  intercept-rules:
    - name: pix-transfer
      path: /api/v1/pix/transferencia
      methods: [ POST ]
      journey: app-bank-authz-transacional
```

| Campo | O que é |
|---|---|
| `name` | Um apelido, usado em registro e medição. Escolha livre |
| `path` | O endereço **exato**. `/api/v1/pix` não cobre `/api/v1/pix/transferencia` |
| `methods` | Só os que exigem autorização |
| `journey` | A jornada de autorização; vem de quem administra o provedor |

> **Como preencher isso no manifesto de implantação depende do gráfico usado.**
> Confirme com a plataforma qual campo alimenta essa lista.

### O que não pode ser protegido

**Métodos sem corpo — GET, HEAD, DELETE.** A autorização precisa da transação
para se prender a ela, e esses métodos não a carregam. Uma rota GET declarada
aqui é sempre recusada.

### O que fica de fora atravessa

Operação não declarada segue direto para o seu serviço, sem verificação. É o
comportamento pretendido — mas confira a lista: **uma operação esquecida fica
desprotegida sem aviso**.

---

## 2. Headers

| Header | Quando | Para quê |
|---|---|---|
| `<org>-authentication` | Sempre | O JWT do Cognito, como já é hoje |
| `<org>-token` | 1ª chamada | O código do autenticador (6 dígitos) |
| `<org>-authentication-am` | 2ª chamada | A referência recebida na 1ª chamada |
| `<org>-authz-session` | Só ao responder um desafio | A sessão recebida no 428 |
| `<org>-correlation-id` | Opcional | Rastreio |

> **`<org>-authentication-am`:** você envia a **referência**. O componente a
> troca pela credencial antes de chamar seu serviço — seu serviço nunca vê a
> referência.

---

## 3. Chamada

Nas operações protegidas, a mesma chamada acontece **duas vezes**. Mesmo
endereço, mesmo corpo; mudam os headers.

### 1ª — autorizar

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -H '<org>-token: 149707' \
  -d '{
        "channel": {
          "endToEndId": "E60701190202402011402DY568TDHVZ3",
          "amount": { "currency": "BRL", "value": 500.00 },
          "destinationKey": { "value": "maria@banco.com.br", "type": "EMAIL" },
          "message": "Pagamento"
        },
        "risk": { "session_id": "...", "event_type": "transaction" },
        "authN": { "transactionId": "..." }
      }'
```

**Esta chamada não efetiva a transação.** Ela só autoriza.

### 2ª — efetivar

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -H '<org>-authentication-am: 84da0844-d1f9-31f9-b4f4-79b420be8be4' \
  -d '{ ...o mesmo corpo da 1ª chamada... }'
```

### Operação não protegida

Nada muda. GET, POST fora da lista — a chamada é a que você já faz:

```bash
curl -i -X GET 'https://<seu-servico>/api/v1/pix/chaves?documento=18075470001' \
  -H '<org>-authentication: <JWT>'
```

### A regra que mais gera erro

**O corpo precisa ser idêntico nas duas chamadas.**

A autorização fica presa àquela transação. Se o valor ou o destinatário mudarem
entre uma chamada e outra, a efetivação é recusada — e o erro **não aponta para
a causa**.

Guarde o corpo enviado e reenvie o mesmo. Não monte o JSON de novo a partir dos
seus objetos: reserializar pode mudar a ordem das chaves ou o formato de um
número sem que nada pareça diferente.

---

## 4. Resposta

### 1ª chamada — autorizado

```json
{
  "status": "authorized",
  "tokenRef": "84da0844-d1f9-31f9-b4f4-79b420be8be4"
}
```

Guarde o `tokenRef` e use na 2ª chamada.

### 2ª chamada

A resposta do seu serviço, como sempre. A transação aconteceu.

---

## 5. Desafio

Não acontece hoje — o código do autenticador vai na 1ª chamada. Documentado
porque o formato já está definido.

**O que chega — 428:**

```json
{
  "authorizationRequired": true,
  "sessionId": "eyJ0eXAiOiJKV1Qi...",
  "challenge": { "type": "OTP", "provider": "AUTHFY" }
}
```

**O que responder** — sessão no header, resposta no corpo:

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -H '<org>-authz-session: eyJ0eXAiOiJKV1Qi...' \
  -d '{ "authz": { "response": "149707" } }'
```

**O corpo aqui é só o bloco `authz`** — sem `channel`, sem `risk`. A transação já
foi apresentada.

O `response` é sempre texto; o conteúdo depende do `type`:

| `type` | `response` |
|---|---|
| `OTP` | O código de 6 dígitos |
| `BIOMETRIA` | O retorno do serviço de biometria |
| `FIDO` | A assinatura do dispositivo |

Depois, o desfecho é o mesmo da 1ª chamada.

---

## 6. Erros

| Código | `error` | O que fazer |
|---|---|---|
| **403** | `denied` | Pedir novo código ao cliente e recomeçar |
| **400** | `invalid_request` | Corrigir o corpo — código novo não resolve |
| **400** | `bad_request` | Corrigir a chamada |
| **401** | `session_required` | Enviar o header de autenticação |
| **401** | `authorization_required` | A referência não vale mais — recomeçar |
| **401** | `session_expired` | A autorização demorou demais — recomeçar |
| **413** | `payload_too_large` | Corpo acima do limite |
| **502** | `bad_gateway` | Seu serviço não respondeu |
| **503** | `authorization_unavailable` | **Repetir** — nada foi efetivado |

Duas distinções que importam:

**403 vs 400.** No 403 um código novo resolve. No 400 `invalid_request`, não —
o problema é o corpo.

**503.** A transação **não aconteceu**. Não há o que desfazer, e repetir é
seguro.

---

## 7. Rastreio

Toda resposta traz `correlationId`, e ele também volta no header
`<org>-correlation-id`.

```json
{ "error": "denied", "correlationId": "Ov7kQm2xR9pLzA" }
```

**Registre esse valor.** É o que liga seus registros aos do componente, e é o
que você informa ao abrir chamado — com ele, quem investiga localiza a transação
inteira.

Se você enviar o header, o componente usa o seu valor. Se não enviar, ele gera
um.

---

## Checklist

- [ ] Operação não declarada responde como antes
- [ ] Operação declarada sem autorização responde **403**
- [ ] Autorização com código válido devolve `tokenRef`
- [ ] Efetivação com o `tokenRef` executa a transação
- [ ] Efetivação com `tokenRef` inválido responde **401**
- [ ] O `correlationId` aparece nos seus registros