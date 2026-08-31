# Sidecar — Testes

O que está coberto, por quê, e o que fazer ao mexer no componente.

---

## Três famílias

**Testes de unidade** rodam sem rede e sem contexto. Cobrem decisão e tradução —
onde estão as regras do componente.

**Teste de cadeia** percorre o caminho inteiro com servidores simulados. Cobre as
junções entre as peças.

Os dois rodam na construção.

**Testes contra os serviços reais** chamam o provedor e o guardião em
homologação. Cobrem o que nenhum simulado alcança: o contrato de verdade. **Não
rodam na construção** — dependem de credenciais e de um código de autenticador
que vence em 30 segundos.

---

## Cobertura por classe

### Decisão e tradução — sem dependência externa

| Classe | O que o teste protege |
|---|---|
| `RouteResolver` | Comparação exata de caminho, método junto com caminho, recusa do que a comparação não alcança |
| `ProxyHeaderPolicy` | As três categorias que não atravessam, e a lista de reservados |
| `CorrelationId` | Aproveitamento do valor recebido e recusa do que inventaria entradas de registro |
| `ChallengeMapper` | Separação de tipo e provedor, preferência pela entrada sobre o rótulo |
| `JourneyRequest` | Cópia do callback com substituição só do valor |
| `RefusalKind` | Classificação dos códigos em duas ações |
| `ChallengeAnswerReader` | Leitura da resposta no corpo, inclusive longa |
| `ChannelProperties` | Recusa de nomes repetidos, sem depender de caixa |

### Orquestração — com substitutos

| Classe | O que o teste protege |
|---|---|
| `AuthorizationOrchestrator` | Tradução de cada desfecho, distinção entre recusa e indisponibilidade |
| `ProxyFilter` | Escolha do caminho pelos cabeçalhos, substituição da referência pelo token, estado de cada resposta |

### Clientes — com servidor simulado

| Classe | O que o teste protege |
|---|---|
| `HttpAuthenticationJourneyClient` | Encadeamento do corpo, callback devolvido completo, tradução de status |
| `HttpTokenIssuer` | Verificador sorteado, leitura do código no destino, troca |
| `HttpTokenCustodian` | Entrega e recuperação, distinção entre referência inválida e guardião fora |
| `ServiceCredentialsProvider` | Reaproveitamento enquanto vale, renovação com antecedência |
| `RequestForwarder` | Delimitação, teto de corpo, cópia de cabeçalhos, cadeia de encaminhamento |

### Cadeia completa

| Classe | O que o teste protege |
|---|---|
| `AuthorizationChainTest` | A sessão da jornada chega à emissão; o token emitido chega à guarda |

---

## Testes que existem por causa de um defeito real

Estes não são cobertura de rotina. Cada um trava algo que já falhou.

### `JourneyRequest` — o callback volta completo

```
mantem_o_rotulo_emitido_pelo_provedor
```

Enviar o callback sem o bloco de rótulo fez o provedor responder **500**. Ele
espera de volta a estrutura que emitiu, não apenas a parte que mudou.

### `HttpAuthenticationJourneyClient` — o corpo não é reserializado

```
nao_reserializa_o_corpo
```

O corpo do teste tem acento. Se alguém passar o corpo por um conversor de JSON,
o escape muda, o resumo calculado pelo provedor muda junto, e a conferência
falha na efetivação — longe da origem.

### `HttpTokenIssuer` — a recusa também redireciona

```
recusa_destino_com_erro
```

Um escopo não cadastrado fez o provedor redirecionar com um parâmetro de erro no
lugar do código. Sem procurar o código explicitamente, isso passaria como
sucesso.

### `ProxyFilter` — a referência não vaza

```
escreve_o_token_no_encaminhamento
```

Verifica que o serviço de negócio recebe o token e **não** a referência. É a
razão de ser do componente.

### `RouteResolver` — a comparação é exata

```
nao_casa_por_prefixo
```

Trocar a comparação por prefixo pareceria mais flexível e pegaria rotas vizinhas
por acidente.

---

## Comportamentos travados por teste

Estes documentam decisões que ninguém adivinharia lendo o código:

| Teste | O que ele fixa |
|---|---|
| `referencia_vence_sessao` | Chegando as duas, a efetivação ganha |
| `falha_na_emissao_nao_e_recusa` | O titular já provou quem é; pedir código novo seria errado |
| `distingue_indisponibilidade_de_referencia_invalida` | Uma pede nova autorização, a outra pede repetir |
| `recusa_tambem_fora_da_matriz` | Caminho malformado é recusado mesmo em rota que atravessaria |
| `varios_callbacks` | Com mais de um callback, o primeiro é o que nomeia o desafio |
| `callback_de_espera` | Espera ativa não é tratada, por decisão de projeto |
| `le_resposta_longa` | A resposta ao desafio vive no corpo porque cabeçalho tem teto |
| `o_componente_prevalece` | O cabeçalho escrito pelo componente vence o do chamador |

---

## A cadeia inteira

`AuthorizationChainTest` percorre o caminho completo — do início da jornada à
referência devolvida ao canal — com servidores simulados.

Cada peça já tem teste próprio; este verifica que **elas se encaixam**: que a
sessão emitida pela jornada chega à emissão do token como cookie, e que o token
emitido chega ao guardião. São as junções, onde erro de ligação aparece.

Roda na construção, sem depender de ambiente.

---

## Testes contra os serviços reais

Três classes chamam o provedor e o guardião em homologação:

| Classe | Alcance |
|---|---|
| `JourneyIntegrationTest` | Jornada e apresentação do corpo |
| `TokenIssuanceIntegrationTest` | Jornada e emissão do token |
| `TokenCustodyIntegrationTest` | Cadeia completa, incluindo a guarda |

**Não rodam na construção.** Cada uma é ignorada enquanto as variáveis de
ambiente não existirem:

```java
@EnabledIfEnvironmentVariable(named = "JOURNEY_CHANNEL_TOKEN", matches = ".+")
```

### Por que eles existem, se a cadeia já tem teste simulado

Um servidor simulado responde o que mandamos responder. Estes testes foram o que
revelou o 500 do callback incompleto, a recusa por escopo e o problema da
credencial emitida pela identidade errada — nenhum deles apareceria contra um
simulado.

**Rode-os antes de subir para qualquer ambiente novo.** É a única forma de saber
se o que mudou continua conversando com os serviços de verdade.

### Como rodar

Preencha as variáveis na configuração de execução, gere um código de
autenticador e rode em seguida — ele vence em 30 segundos.

```
JOURNEY_NAME
JOURNEY_CHANNEL_TOKEN
JOURNEY_OTP_CODE
IDENTITY_BASE_URL
IDENTITY_CLIENT_ID
IDENTITY_CLIENT_SECRET
IDENTITY_SCOPES
IDENTITY_SESSION_COOKIE_NAME
SERVICE_CREDENTIALS_URL
SERVICE_CREDENTIALS_USERNAME
SERVICE_CREDENTIALS_PASSWORD
TOKEN_HANDLER_URL
```

### Antes de enviar ao repositório

**Nenhum token do canal ou código de autenticador pode ir junto.** Leia os
valores do ambiente; se usar constantes para iterar, remova antes de confirmar.

---

## Ao mexer no componente

### Se um destes testes falhar, pare e leia o nome

Os testes com `@DisplayName` explicam a regra que protegem. Quando um falha, a
pergunta não é "como faço passar" — é se a regra mudou de propósito.

### O que ainda não tem teste

| Item | Por quê |
|---|---|
| `SidecarMetrics` | Sem lógica; a instrumentação é verificada pelos testes do componente de entrada |
| Configuração de beans | Verificada pela subida da aplicação |
| Não seguir redirecionamento | O servidor simulado não emula; só o ambiente real exercita |
| Cabeçalho restrito da plataforma | Idem — a restrição é da máquina virtual |
| Caminho com desafio de ponta a ponta | Depende de uma jornada que emita desafio e não use espera ativa |

### Ao acrescentar comportamento

Um teste novo vale quando ele **falha se a regra for removida**. Teste que passa
com qualquer implementação mede cobertura, não comportamento.

Escreva o `@DisplayName` como a regra, não como o método: quem lê o relatório de
falha precisa entender o que quebrou sem abrir o código.