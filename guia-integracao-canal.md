# Sidecar — Guia de Integração do Canal

Documentação Técnica de Arquitetura e Protocolo de Autenticação

O Sidecar de Autenticação intercepta e valida requisições HTTP/HTTPS direcionadas
a microserviços internos antes da execução efetiva da regra de negócio.

> **Diretiva de Infraestrutura:** Após a inclusão do Sidecar, o microserviço de
> destino (`<seu-servico>`) deve ter suas portas e serviços restritos a acessos
> externos públicos, aceitando tráfego exclusivamente via proxy local.

---

## 1 — Configuração

A habilitação do componente exige três parâmetros centrais no manifesto da
aplicação:

**Endereçamento e Portas.** O Sidecar assume a porta externa original do serviço
(`sidecarPort: 8080`), enquanto o microserviço passa a escutar em uma interface
privada (`proxy.target: http://127.0.0.1:8081`).

```
sidecarPort: 8080                    # a porta que seu serviço expunha
proxy.target: http://127.0.0.1:8081  # onde seu serviço passou a atender
```

**Matriz de Interceptação (`intercept-rules`).** Declaração explícita das rotas
que exigem autorização.

```yaml
intercept-rules:
  - name: pix-transfer
    path: /api/v1/pix/transferencia
    methods: [ POST ]
    journey: app-bank-authz-transacional

  - name: pix-chave-consulta
    path: /api/v1/pix/chaves/{chave}
    methods: [ GET ]
    journey: pdc-bank-authz-consultivo
```

**Passthrough.** Métodos ou rotas não declarados trafegam diretamente para o
microserviço, sem validação.

> **Atenção:** rotas omitidas ficam desprotegidas sem aviso. A revisão da matriz
> é parte da entrega.

### 1.1 — Correspondência de rotas

O `path` aceita três formas:

| Forma | Exemplo | Casa |
|---|---|---|
| Caminho exato | `/api/v1/pix/chaves` | apenas ele |
| Segmento variável | `/api/v1/pix/chaves/{chave}` | `/chaves/abc123`, `/chaves/999` |
| Curinga de um segmento | `/api/v1/pix/chaves/*` | o mesmo que a forma anterior |

O nome dentro das chaves é livre — `{id}`, `{cpf}`, `{chave}` produzem o mesmo
casamento — e serve a quem lê a regra.

**Um segmento por variável.** `/chaves/{chave}` casa `/chaves/abc123`, mas não
`/chaves/abc/123`.

> **`**` não é aceito.** O curinga de múltiplos segmentos protegeria também as
> rotas criadas depois, sem revisão — e uma rota nasceria verificada, ou
> desprotegida, por acidente. A aplicação recusa subir com esse padrão.

### 1.2 — Métodos e vínculo transacional

| Método | Interceptável | Corpo | Observação |
|---|---|---|---|
| `POST` | sim | sim | vínculo com a transação |
| `PUT` | sim | sim | vínculo com a transação |
| `PATCH` | sim | sim | vínculo com a transação |
| `DELETE` | sim | sim | corpo opcional; serviços mais antigos o enviam |
| `GET` | sim | não tem | — |
| `HEAD`, `OPTIONS` | sim | não tem | — |

**Sobre o vínculo.** Quando a jornada calcula um resumo sobre o corpo
apresentado, é ele que prende a autorização àquela transação específica. Um
método sem corpo não tem o que vincular — a autorização prova que o titular
aprovou a operação, sem dizer sobre o quê.

**Quem decide se o corpo é necessário é a jornada, não o Sidecar.** Se ela pedir
o corpo e não houver, a chamada termina em **503 `authorization_unavailable`**, e
o registro do componente aponta o que faltou. Confirme com quem administra o
provedor qual jornada atende a sua rota.

---

## 2 — Cabeçalhos (Headers) da API

| Header | Momento de Envio | Finalidade Técnica |
|---|---|---|
| `x-porto-authentication` | Todas as chamadas | Token JWT do provedor de identidade de borda |
| `x-porto-token` | 1ª chamada (Autorização) | Código do autenticador (6 dígitos) |
| `x-porto-authentication-am` | 2ª chamada (Efetivação) | Identificador `tokenRef` retornado na 1ª chamada |
| `x-porto-authz-session` | Resposta a Desafios | Token de sessão emitido na resposta HTTP 428 |
| `x-porto-correlation-id` | Opcional | Chave para rastreabilidade distribuída |

> **Nota de Arquitetura:** o header `x-porto-authentication-am` é interceptado
> pelo Sidecar, que realiza a troca do `tokenRef` pela credencial real antes de
> repassar a chamada ao seu serviço — o microserviço jamais lê a referência.

---

## 3 — Fluxo de Chamada e Resposta

Para rotas protegidas, o protocolo opera em duas etapas idênticas em payload,
variando apenas o conjunto de headers.

### Passo 1: Solicitação de Autorização

O cliente envia o payload da transação acompanhado do código no header
`x-porto-token`.

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: <JWT>' \
  -H 'x-porto-token: 149707' \
  -d '{
        "channel": {
          "endToEndId": "E607011902024020114020YS68TDHVZ3",
          "amount": { "currency": "BRL", "value": 500.00 },
          "destinationKey": { "value": "maria@banco.com.br", "type": "EMAIL" },
          "message": "Pagamento"
        },
        "risk": { "session_id": "...", "event_type": "transaction" },
        "authN": { "transactionId": "..." }
      }'
```

**Resposta:**

```json
{
  "status": "authorized",
  "tokenRef": "84da0844-d1f9-31f9-b4f4-79b420be8be4"
}
```

**Propriedades do `tokenRef`.** Referência temporária guardada em repositório
interno. Possui uso único para a transação em questão e não exige persistência no
lado do cliente. Esta chamada apenas autoriza, **não efetiva** a operação.

### Passo 2: Efetivação da Operação

O cliente envia exatamente o mesmo payload, substituindo o `x-porto-token` pelo
`x-porto-authentication-am`.

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: <JWT>' \
  -H 'x-porto-authentication-am: 84da0844-d1f9-31f9-b4f4-79b420be8be4' \
  -d '{ ...o mesmo corpo do passo 1... }'
```

> **Integridade Criptográfica:** o payload precisa ser estritamente idêntico
> entre os dois passos. Alterações no corpo, reordenação de chaves JSON ou
> alteração na formatação de números invalidarão a efetivação — e a recusa ocorre
> adiante, sem apontar a causa.

### Consulta protegida (sem corpo)

Rotas de consulta seguem o mesmo protocolo de dois passos, sem payload:

```bash
curl -i -X GET 'https://<seu-servico>/api/v1/pix/chaves/18075470001' \
  -H 'x-porto-authentication: <JWT>' \
  -H 'x-porto-token: 149707'
```

**O retorno depende da jornada configurada para a rota.**

| A jornada | O retorno |
|---|---|
| conclui na sessão do Sidecar | `200` com `tokenRef` — siga para o Passo 2 |
| exige validação em outro aplicativo | `428` com `DEEPLINK` — veja a seção 5.2 |

A jornada consultiva atualmente disponível é do segundo tipo: ela emite um
endereço a abrir e aguarda que outra parte resolva. Confirme com quem administra
o provedor qual jornada atende a sua rota, e trate os dois retornos no cliente.

---

## 4 — Resumo do Fluxo Transacional

```
1ª chamada  ->  200 { tokenRef }              -> autorizar
     ↓
2ª chamada  ->  resposta do seu serviço       -> efetivar
```

| Passo | Header Alterado | Retorno da API | Ação Transacional |
|---|---|---|---|
| Passo 1 | `x-porto-token` (código) | HTTP 200 + `{ tokenRef }` | Validação de credenciais e autorização |
| Passo 2 | `x-porto-authentication-am` (`tokenRef`) | Resposta original do serviço | Efetivação da regra de negócio |

---

## 5 — Tratamento de Desafios (Step-up Authentication)

Quando a jornada exige validação adicional, o Sidecar responde com
**HTTP 428 Precondition Required**.

Há dois formatos, conforme o desafio seja cumprido pelo próprio canal ou fora
dele.

### 5.1 — Desafio cumprido pelo canal

```json
{
  "authorizationRequired": true,
  "sessionId": "eyJ0eXAiOiJKV1Qi...",
  "challenge": { "type": "OTP", "provider": "AUTHFY" }
}
```

O cliente reenvia a requisição com o identificador de sessão no header
`x-porto-authz-session` e a resposta no payload. **Nesta etapa envia-se apenas o
bloco `authz`**, suprimindo o restante do payload da transação.

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: <JWT>' \
  -H 'x-porto-authz-session: eyJ0eXAiOiJKV1Qi...' \
  -d '{ "authz": { "response": "149707" } }'
```

| Tipo (`type`) | Conteúdo de `response` |
|---|---|
| `OTP` | Código de verificação de 6 dígitos |
| `BIOMETRIA` | Retorno do provedor biométrico |
| `FIDO` | Assinatura digital do dispositivo |

### 5.2 — Desafio cumprido fora do canal

Quando a validação ocorre em outro aplicativo, o Sidecar devolve o endereço a
abrir e o tempo a aguardar:

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

**Procedimento:**

1. Entregar o `target` ao aplicativo, que o abre
2. Aguardar o `retryAfter` (milissegundos)
3. Reapresentar a sessão, **sem resposta no corpo**

```bash
curl -i -X POST 'https://<seu-servico>/api/v1/pix/transferencia' \
  -H 'Content-Type: application/json' \
  -H 'x-porto-authentication: <JWT>' \
  -H 'x-porto-authz-session: eyJ0eXAiOiJKV1Qi...' \
  -d '{}'
```

Enquanto a validação não concluir, o Sidecar responde outro **428** com o mesmo
`sessionId` — repita o ciclo. Ao concluir, responde **200** com o `tokenRef`.

> **A espera é responsabilidade do cliente.** O Sidecar não retém a chamada
> aguardando desfecho: cada consulta é respondida de imediato.

---

## 6 — Matriz de Erros e Contingências

Toda recusa retorna `error` e `correlationId`. Quando a recusa parte da
autorização, retorna também `reason` — é ele que indica a ação corretiva.

```json
{
  "error": "denied",
  "reason": "code_invalid",
  "correlationId": "0v7kQm2xR9pLzA"
}
```

### 6.1 — Recusas da autorização

| Código | `error` | `reason` | Ação Recomendada |
|---|---|---|---|
| 403 | `denied` | `code_required` | Solicitar o código ao cliente |
| 403 | `denied` | `code_invalid` | Solicitar novo código e reiniciar |
| 403 | `denied` | `factor_required` | Direcionar ao cadastro do autenticador |
| 403 | `denied` | `denied` | Recusado sem detalhe — não insistir |
| 400 | `invalid_request` | `payload_invalid` | Corrigir o corpo; novo código não resolve |

### 6.2 — Demais recusas

| Código | `error` | Causa Provável | Ação Recomendada |
|---|---|---|---|
| 400 | `bad_request` | Falha na estrutura da chamada | Ajustar parâmetros e headers |
| 401 | `session_required` | Ausência do header de autenticação | Incluir `x-porto-authentication` |
| 401 | `session_expired` | Sessão do provedor de borda recusada ou vencida | Renovar a sessão e reiniciar |
| 401 | `authorization_required` | `tokenRef` inválido, expirado ou consumido | Recomeçar do Passo 1 |
| 413 | `payload_too_large` | Requisição excede o limite | Reduzir o volume de dados |
| 502 | `bad_gateway` | Microserviço de destino indisponível | Verificar saúde da aplicação |
| 503 | `authorization_unavailable` | Indisponibilidade no autorizador | Reexecutar o Passo 1 — a transação não foi consumida |

### 6.3 — Distinções que importam

**403 `code_invalid` vs. 401 `session_expired`.** No primeiro, o código não
serve — solicite outro. No segundo, o JWT não vale mais — renove a sessão.
Solicitar código para sessão vencida não conclui.

**403 vs. 400 `invalid_request`.** No 403 um código novo resolve. No 400, não —
o problema é o corpo.

**503.** A transação **não foi efetivada**. Não há estorno a realizar, e repetir
é seguro.

---

## 7 — Rastreabilidade e Logs (Correlation ID)

Toda resposta emitida pelo Sidecar retorna um identificador único de rastreio no
header `x-porto-correlation-id` e no corpo da mensagem.

**Boas Práticas de Observabilidade.** O microserviço deve obrigatoriamente
registrar o `correlationId` em seus logs de aplicação.

**Abertura de Incidentes.** Este identificador é o dado primário exigido pelas
equipes de suporte e plataforma para correlacionar o rastreio distribuído de
ponta a ponta.

**Injeção de Header.** Caso a aplicação cliente envie um `x-porto-correlation-id`,
o Sidecar respeitará o valor fornecido; caso contrário, um novo identificador é
gerado automaticamente.

> Valores recebidos passam por validação de forma. Um identificador com quebra de
> linha, espaço ou acima de 64 caracteres é descartado e substituído — o que
> rompe a correlação com os logs do cliente.

---

## 8 — Informações Adicionais

Documentos e guias complementares:

- Sidecar — Configuração
- Sidecar — Guia de Desenvolvimento e Diagnóstico

### Checklist de Validação de Integração

☐ A aplicação responde normalmente a chamadas não declaradas nas rotas de
interceptação

☐ Chamadas a rotas protegidas sem header de autorização retornam 401 ou 403

☐ Envio de código válido no Passo 1 retorna HTTP 200 contendo o `tokenRef`

☐ Passo 2 com o `tokenRef` executa a operação no microserviço

☐ Passo 2 com `tokenRef` já consumido retorna 401 `authorization_required`

☐ Rotas com segmento variável (`{id}`) casam os valores esperados

☐ O `correlationId` é registrado nos logs da aplicação