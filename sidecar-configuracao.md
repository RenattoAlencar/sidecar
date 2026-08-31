# Sidecar — Configuração

Toda configuração vem de variável de ambiente. Este documento diz **quem define
cada uma** e com que valor.

Nos exemplos, `<org>` é o prefixo de cabeçalho adotado pela organização.

---

## Como ler as tabelas

| Marca | Quem define | Onde vive |
|---|---|---|
| **FIXO** | O componente | Já vem no contêiner. Não mexa |
| **AMBIENTE** | Plataforma / segurança | ConfigMap e Secret do ambiente |
| **CANAL** | A equipe do serviço de negócio | Manifesto da instância |

A equipe do canal preenche **apenas as marcadas como CANAL**.

---

## Resumo por responsável

### O que o canal preenche

| Variável | Exemplo |
|---|---|
| `PROXY_TARGET` | `http://127.0.0.1:8081` |
| `SIDECAR_PORT` | `8080` |
| `NAME_<ROTA>` | `pix-transfer` |
| `PATH_<ROTA>` | `/api/v1/pix/transferencia` |
| `JOURNEY_<ROTA>` | `app-bank-authz-transacional` |

As três últimas se repetem por rota verificada.

### O que o canal não preenche

Nomes de cabeçalho, endereços do provedor e do guardião, credenciais, tempos
limite. Vêm do contêiner ou do ambiente.

---

## Contrato

Nomes de cabeçalho e limites. Iguais para todos os canais: mudá-los em uma
instância quebraria o contrato que o canal já conhece.

| Definido por | Variável | Valor | O que é |
|---|---|---|---|
| **FIXO** | `CHANNEL_SESSION_HEADER` | `x-<org>-authz-session` | Sessão da jornada, no desafio |
| **FIXO** | `CHANNEL_RESPONSE_HEADER` | `x-<org>-token` | Código do autenticador |
| **FIXO** | `CHANNEL_TOKEN_REFERENCE_HEADER` | `x-<org>-authentication-am` | Referência do token |
| **FIXO** | `IDENTITY_CHANNEL_TOKEN_HEADER` | `x-<org>-authentication` | Token do canal |
| **FIXO** | `IDENTITY_AUTHENTICATOR_CODE_HEADER` | `x-<org>-token` | Repasse do código ao provedor |
| **FIXO** | `PROXY_CORRELATION_HEADER` | `x-<org>-correlation-id` | Rastreio |
| **FIXO** | `TOKEN_HANDLER_TOKEN_REF_HEADER` | `X-Token-Ref` | Referência, na consulta ao guardião |
| **FIXO** | `PROXY_MAX_BODY_BYTES` | `2097152` | Teto do corpo, em bytes |

> `CHANNEL_RESPONSE_HEADER` e `IDENTITY_AUTHENTICATOR_CODE_HEADER` têm o mesmo
> valor de propósito: é o mesmo cabeçalho que o canal envia e que o componente
> repassa ao provedor.
>
> Se a organização precisar renomear um cabeçalho, muda em todas as instâncias
> de uma vez — nunca em uma só.

---

## Provedor de identidade

| Definido por | Variável | Exemplo | Observação |
|---|---|---|---|
| **AMBIENTE** | `IDENTITY_BASE_URL` | `https://<provedor-hml>/am` | Raiz, sem o caminho de autenticação |
| **AMBIENTE** | `IDENTITY_REALM` | `alpha` | |
| **AMBIENTE** | `IDENTITY_JOURNEY_TYPE` | `service` | |
| **AMBIENTE** | `IDENTITY_CLIENT_ID` | `sidecar-authz` | Cliente cadastrado no provedor |
| **AMBIENTE** | `IDENTITY_CLIENT_SECRET` | — | **Segredo.** Nunca no repositório |
| **AMBIENTE** | `IDENTITY_REDIRECT_URI` | `https://localhost:8080` | Precisa ser **idêntico** ao cadastrado no cliente, inclusive barra final |
| **AMBIENTE** | `IDENTITY_SCOPES` | `write` | Os cadastrados no cliente. Escopo não concedido faz a autorização falhar sem dizer por quê |
| **AMBIENTE** | `IDENTITY_SESSION_COOKIE_NAME` | `417726ee02928f6` | Nome do cookie de sessão do provedor |

---

## Credencial do componente

| Definido por | Variável | Exemplo | Observação |
|---|---|---|---|
| **AMBIENTE** | `SERVICE_CREDENTIALS_URL` | `https://<sso>/auth/realms/<realm>/protocol/openid-connect/token` | |
| **AMBIENTE** | `SERVICE_CREDENTIALS_USERNAME` | `sidecar` | |
| **AMBIENTE** | `SERVICE_CREDENTIALS_PASSWORD` | — | **Segredo** |
| **AMBIENTE** | `SERVICE_CREDENTIALS_HOST_HEADER` | vazio, ou `<sso-exposto>` | Veja o aviso abaixo |

> **Se preencher o cabeçalho de servidor**, a máquina virtual precisa de
> `-Djdk.httpclient.allowRestrictedHeaders=host`. Sem isso ele é descartado **sem
> erro e sem registro**: a credencial sai pela identidade errada e o guardião a
> recusa com uma mensagem que fala de credencial revogada.
>
> Apontar a URL direto para o endereço exposto evita a questão.

---

## Guardião de token

| Definido por | Variável | Exemplo |
|---|---|---|
| **AMBIENTE** | `TOKEN_HANDLER_URL` | `https://<guardiao>/token-handler/v1/token-refs` |

---

## Instância

**É o que a equipe do canal define.** Cada sidecar atende um serviço de negócio,
com suas rotas.

### Destino e portas

| Definido por | Variável | Exemplo | Observação |
|---|---|---|---|
| **CANAL** | `PROXY_TARGET` | `http://127.0.0.1:8081` | O serviço de negócio no mesmo agrupamento. Endereço local, porque os dois compartilham o mesmo espaço de rede |
| **CANAL** | `SIDECAR_PORT` | `8080` | Onde o componente atende. Assume a porta que o serviço de negócio expunha |
| **AMBIENTE** | `SIDECAR_MANAGEMENT_PORT` | `8090` | Saúde e medições, em porta separada |

### Rotas verificadas

Uma entrada por rota que exige autorização. Caminho **e** método.

```yaml
proxy:
  intercept-rules:
    - name: ${NAME_PIX_TRANSFERENCIA:pix-transfer}
      path: ${PATH_PIX_TRANSFERENCIA:/api/v1/pix/transferencia}
      methods: [ POST ]
      journey: ${JOURNEY_PIX_TRANSFERENCIA:app-bank-authz-transacional}

    - name: ${NAME_TED:ted-transfer}
      path: ${PATH_TED:/api/v1/transferencias/ted}
      methods: [ POST ]
      journey: ${JOURNEY_TED:app-bank-authz-transacional}
```

| Definido por | Campo | O que é |
|---|---|---|
| **CANAL** | `name` | Identificação nas medições e no registro. Escolha livre |
| **CANAL** | `path` | Caminho **exato**. Não é prefixo: `/pix` não pega `/pix/transferencia` |
| **CANAL** | `methods` | Só os que exigem autorização. Consultar e transacionar chegam pelo mesmo endereço, e só o segundo precisa |
| **CANAL** | `journey` | A jornada a iniciar. Vem de quem administra o provedor |

**O que não estiver aqui atravessa sem verificação.** É deliberado: verificar
tudo tornaria o componente responsável por jornadas que não precisam dele.

---

## Registro

| Definido por | Variável | Valor | Observação |
|---|---|---|---|
| **AMBIENTE** | `LOG_LEVEL` | `INFO` | |
| **AMBIENTE** | `LOG_LEVEL_SIDECAR` | `INFO` | `DEBUG` para diagnosticar |
| **AMBIENTE** | `LOG_LEVEL_HTTP` | `INFO` | **Nunca `DEBUG` fora da estação de trabalho** — mostra o token do canal, o código do autenticador e o corpo da transação |

O nome do pacote na configuração de registro precisa bater com o do projeto.
Apontar para o pacote errado faz o nível nunca ser aplicado, e nenhum registro do
componente aparece.

---

## Tempos limite

Padrões conservadores. Ajuste só com medição em mãos.

| Definido por | Variável | Padrão | Alcance |
|---|---|---|---|
| **FIXO** | `PROXY_CONNECT_TIMEOUT` | `2s` | Serviço de negócio |
| **FIXO** | `PROXY_READ_TIMEOUT` | `10s` | Serviço de negócio |
| **FIXO** | `IDENTITY_CONNECT_TIMEOUT` | `2s` | Provedor |
| **FIXO** | `IDENTITY_READ_TIMEOUT` | `10s` | Provedor — a jornada tem mais passos |
| **FIXO** | `SERVICE_CREDENTIALS_CONNECT_TIMEOUT` | `2s` | Credencial |
| **FIXO** | `SERVICE_CREDENTIALS_READ_TIMEOUT` | `5s` | Credencial |
| **FIXO** | `SERVICE_CREDENTIALS_REFRESH_SKEW` | `30s` | Antecedência da renovação |
| **FIXO** | `TOKEN_HANDLER_CONNECT_TIMEOUT` | `2s` | Guardião |
| **FIXO** | `TOKEN_HANDLER_READ_TIMEOUT` | `5s` | Guardião |

> O componente está no caminho crítico: toda transação passa por ele duas vezes,
> e a segunda soma uma chamada ao guardião. Tempo limite alto demais segura a
> transação; baixo demais recusa o que passaria.
>
> Se um canal precisar de valores diferentes, leve à discussão em vez de ajustar
> só na instância — a diferença costuma indicar problema em outro lugar.

---

## Ao subir uma instância nova

1. **Aponte o destino** para o serviço de negócio (`PROXY_TARGET`)
2. **Assuma a porta** que ele expunha (`SIDECAR_PORT`)
3. **Liste as rotas** que exigem autorização, com caminho e método exatos
4. **Confirme os segredos** vindos do cofre, não do repositório
5. **Suba e leia o registro:** se aparecer *nenhuma rota verificada
   configurada*, a lista não foi lida — quase sempre indentação
6. **Chame uma rota não listada** e confirme que ela atravessa
7. **Chame uma rota listada** e confirme que a autorização é exigida

---

## Sinais de configuração errada

| Sintoma | Causa provável | Onde olhar |
|---|---|---|
| Rota que deveria ser verificada atravessa | Caminho ou método divergente; ou `intercept-rules` fora do bloco `proxy` | CANAL |
| *Nenhuma rota verificada configurada* na subida | A lista chegou vazia | CANAL |
| Autorização falha sem explicar | Escopo não concedido ao cliente, ou endereço de redirecionamento diferente do cadastrado | AMBIENTE |
| Guardião recusa a credencial | Cabeçalho de servidor descartado — confira a opção da máquina virtual | AMBIENTE |
| Nenhum registro do componente | Nome do pacote errado na configuração de registro | AMBIENTE |
| Serviço de negócio recebe a referência em vez do token | O cabeçalho da referência não está reservado | FIXO |