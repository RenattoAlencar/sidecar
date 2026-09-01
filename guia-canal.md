# Adotando a Autorização Transacional — Passo a Passo

Do zero até a primeira transação autorizada. Para a equipe que vai colocar o
componente na frente do seu serviço.

Nos exemplos, `<org>` é o prefixo de cabeçalho adotado pela organização.

---

## Antes de começar

Levante estas quatro coisas. Sem elas, não dá para configurar.

| O quê | Com quem | Exemplo |
|---|---|---|
| Quais operações exigem autorização | Sua equipe, com risco | `POST /api/v1/pix/transferencia` |
| Qual jornada de autorização usar | Quem administra o provedor | `app-bank-authz-transacional` |
| Em que porta seu serviço atende hoje | Sua equipe | `8081` |
| Endereços e credenciais do ambiente | Plataforma | — |

Uma observação sobre a primeira linha: **liste só o que precisa**. Consultar
saldo ou validar chave não precisa de autorização transacional; transferir
dinheiro, sim. Cada operação na lista acrescenta uma chamada ao fluxo.

---

## Passo 1 — Colocar o componente no agrupamento

O componente roda junto do seu serviço, no mesmo agrupamento, em contêiner
separado. Ele passa a atender a porta que seu serviço expunha, e seu serviço
muda para outra.

```
Antes:   [ ingress ] → [ seu serviço :8080 ]

Depois:  [ ingress ] → [ componente :8080 ] → [ seu serviço :8081 ]
```

Quem faz esse ajuste é o manifesto de implantação. O que sua equipe informa:

```yaml
forgerock:
  enabled: true
  sidecarPort: 8080          # a porta que seu serviço expunha
  proxy:
    target: "http://127.0.0.1:8081"   # onde seu serviço passou a atender
```

> **O endereço é local.** Os dois contêineres compartilham o mesmo espaço de
> rede, então a chamada não sai do agrupamento.

---

## Passo 2 — Declarar as operações protegidas

Cada operação exige três informações: um nome, o endereço e a jornada.

```yaml
forgerock:
  routes:
    - name: "pix-transfer"
      path: "/api/v1/pix/transferencia"
      methods: [ "POST" ]
      journey: "app-bank-authz-transacional"

    - name: "ted-transfer"
      path: "/api/v1/transferencias/ted"
      methods: [ "POST" ]
      journey: "app-bank-authz-transacional"
```

Três cuidados:

**O endereço é exato, não prefixo.** `/api/v1/pix` **não** cobre
`/api/v1/pix/transferencia`. Cada operação precisa da própria entrada.

**O método importa.** Se consultar e transferir usam o mesmo endereço, e só a
segunda precisa de autorização, declare apenas `POST`.

**O que não estiver na lista atravessa sem verificação.** Não é falha — é o
comportamento pretendido. Mas confira a lista: uma operação esquecida fica
desprotegida sem aviso.

---

## Passo 3 — Confirmar que subiu certo

Depois da implantação, três verificações rápidas.

### O componente reconheceu as operações?

No registro da subida, procure por:

```
Nenhuma rota verificada configurada: todo o tráfego atravessa direto
```

**Se essa linha aparecer**, a lista não chegou ao componente e nada está sendo
protegido.

### Uma operação fora da lista atravessa?

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/consulta' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -d '{"chave":"teste@banco.com.br"}'
```

Deve responder exatamente como antes do componente existir.

### Uma operação da lista exige autorização?

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -d '{"channel":{...},"risk":{...}}'
```

Deve responder **403** — a autorização foi exigida e não foi apresentada.

Se responder normalmente, a operação não está protegida: confira endereço e
método na configuração.

---

## Passo 4 — Ajustar o aplicativo

As chamadas passam a acontecer em duas etapas. **Mesmo endereço, mesmo corpo**;
muda só o que vai nos cabeçalhos.

### Etapa 1 — Autorizar

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT do Cognito>' \
  -H '<org>-token: 149707' \
  -d '{
        "channel": {
          "endToEndId": "E60701190202402011402DY568TDHVZ3",
          "amount": { "currency": "BRL", "value": 500.00 },
          "destinationKey": { "value": "maria@banco.com.br", "type": "EMAIL" },
          "message": "Pagamento"
        },
        "risk": {
          "session_id": "...",
          "event_type": "transaction"
        },
        "authN": {
          "transactionId": "..."
        }
      }'
```

Resposta:

```json
{
  "status": "authorized",
  "tokenRef": "84da0844-d1f9-31f9-b4f4-79b420be8be4"
}
```

**Esta chamada não efetiva a transação.** Ela só autoriza. Guarde o `tokenRef`.

### Etapa 2 — Efetivar

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT do Cognito>' \
  -H '<org>-authentication-am: 84da0844-d1f9-31f9-b4f4-79b420be8be4' \
  -d '{ ...exatamente o mesmo corpo da etapa 1... }'
```

Resposta: a do seu serviço, como sempre. A transação aconteceu.

---

## A regra que mais gera erro

**O corpo precisa ser idêntico nas duas chamadas.**

Guarde o corpo que você enviou e reenvie o mesmo. Não monte o JSON de novo a
partir dos seus objetos, não reordene, não reformate.

A autorização fica presa àquela transação específica. Se o valor ou o
destinatário mudarem entre as duas chamadas, a efetivação é recusada mais
adiante — e o erro **não aponta para a causa**.

Reserializar o mesmo objeto pode mudar a ordem das chaves ou o formato de um
número sem que nada pareça diferente a olho nu.

---

## O que fazer em cada resposta

| Resposta | O que aconteceu | O que fazer |
|---|---|---|
| **200** com `tokenRef` | Autorizado | Seguir para a etapa 2 |
| **403** `denied` | Código ausente, incorreto ou vencido | Pedir novo código e recomeçar |
| **400** `invalid_request` | Corpo incompleto ou divergente | Corrigir o corpo; código novo não resolve |
| **401** `session_required` | JWT não enviado | Enviar o cabeçalho de autenticação |
| **401** `authorization_required` | Referência já usada ou vencida | Recomeçar da etapa 1 |
| **401** `session_expired` | A autorização demorou demais | Recomeçar da etapa 1 |
| **503** `authorization_unavailable` | O serviço de autorização não respondeu | Repetir — nada foi efetivado |
| **428** | Há um desafio a cumprir | Ver a seção seguinte |

Toda resposta traz `correlationId`. **Registre esse valor** — é o que liga seus
registros aos do componente numa investigação.

---

## Se vier um desafio

Não acontece na configuração atual, porque o código do autenticador vai na
primeira chamada. Está aqui porque o formato já está definido.

**O que chega:**

```json
{
  "authorizationRequired": true,
  "sessionId": "eyJ0eXAiOiJKV1Qi...",
  "challenge": { "type": "OTP", "provider": "AUTHFY" }
}
```

**O que responder** — mesmo endereço, sessão em cabeçalho, resposta no corpo:

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H '<org>-authentication: <JWT>' \
  -H '<org>-authz-session: eyJ0eXAiOiJKV1Qi...' \
  -d '{ "authz": { "response": "149707" } }'
```

**O corpo desta chamada é só o bloco `authz`** — sem `channel`, sem `risk`. A
transação já foi apresentada antes.

O campo `response` é sempre texto; o que colocar nele depende do `type` que veio:

| `type` | `response` |
|---|---|
| `OTP` | O código de 6 dígitos |
| `BIOMETRIA` | O retorno do serviço de biometria |
| `FIDO` | A assinatura do dispositivo |

Depois disso, o desfecho é o mesmo da etapa 1: **200** com `tokenRef`, ou uma
das recusas.

---

## Roteiro de validação

Antes de considerar concluído:

- [ ] Operação fora da lista responde como antes
- [ ] Operação da lista sem autorização responde **403**
- [ ] Autorização com código válido devolve `tokenRef`
- [ ] Efetivação com o `tokenRef` executa a transação
- [ ] Efetivação com `tokenRef` inválido responde **401**
- [ ] O `correlationId` aparece nos seus registros
- [ ] O registro do componente **não** mostra corpo de transação

O último item importa: se o corpo aparecer no registro, há configuração de
registro em nível de depuração que precisa ser desligada antes de produção.

---

## Quando algo não funciona

| Sintoma | Onde olhar |
|---|---|
| A operação atravessa sem exigir autorização | Endereço e método na configuração; e se a lista chegou ao componente |
| Sempre responde **403**, mesmo com código válido | O código pode estar vencido — ele dura 30 segundos |
| Sempre responde **503** | Endereços e credenciais do ambiente |
| A efetivação falha depois de autorizar | Confira se o corpo é idêntico nas duas chamadas |
| Nenhum registro do componente aparece | Configuração de registro — falar com a plataforma |

Ao abrir chamado, informe o `correlationId` da resposta. Com ele, quem investiga
localiza a transação inteira.