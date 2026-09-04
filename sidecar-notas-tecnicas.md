# Sidecar de Autorização Transacional — Notas Técnicas

Documento para a equipe de desenvolvimento. O guia de integração do canal é
separado.

---

## O que o componente faz

Fica no mesmo POD do BFF, em contêiner próprio, e assume as rotas que hoje estão
no ingress. Intercepta as que exigem autorização transacional e encaminha o
resto sem tocar.

Não retém estado nem persiste dados. O que precisa sobreviver entre chamadas
fica no provedor de identidade (a sessão da jornada) ou no guardião de token (o
token emitido).

### Divisão de responsabilidades

| Componente | Papel |
|---|---|
| Sidecar | orquestra a jornada, traduz protocolo, resolve a referência |
| Provedor de identidade | autentica, avalia risco, emite o token |
| Guardião de token | guarda o token, devolve a referência |
| Resource Server | verifica o token e efetiva a transação |

Nenhum guarda o que é do outro.

---

## Fluxo

1. Canal chama a rota de negócio com o corpo da transação e o código do
   autenticador
2. Sidecar inicia a jornada no provedor, apresentando o token do canal e o
   código
3. A jornada pede o corpo da transação; o sidecar apresenta os bytes como
   recebeu
4. A jornada calcula o resumo do corpo e conclui, emitindo a sessão
5. Sidecar troca a sessão por um token (código de autorização com verificador,
   depois a troca)
6. Sidecar entrega o token ao guardião e recebe a referência
7. Canal recebe a referência
8. Canal repete a chamada com a referência
9. Sidecar resolve a referência no guardião e encaminha ao BFF com o token

---

## Decisões e o que as motivou

### O corpo não é reserializado

O provedor calcula um resumo sobre o corpo apresentado. Reordenar chaves, mudar
espaçamento ou normalizar número muda o resumo sem mudar a transação.

Por isso o corpo trafega como `byte[]` desde a leitura até a apresentação ao
provedor. Nenhuma camada o converte para objeto e de volta.

O corpo é retido em memória **apenas nas rotas verificadas**, limitado por
`proxy.max-body-bytes`. Nas demais, segue em fluxo contínuo.

### A comparação de rota é exata

`path.equals`, não prefixo. Prefixo pegaria rotas vizinhas por acidente.

Caminhos com codificação, salto de diretório ou separador repetido são
recusados: eles podem não coincidir com uma rota verificada aqui e coincidir
depois, já interpretados pelo destino — atravessando sem verificação.

### A decisão de verificar é do componente

A rota vem de configuração, por caminho e método. Nenhum cabeçalho da requisição
participa dessa decisão — se participasse, quem chama escolheria o próprio nível
de exigência.

### O vocabulário do provedor não atravessa

O canal nunca vê estrutura de callback, `authId` ou nomes de campo do provedor.
A tradução acontece em um único ponto (`ChallengeMapper`), e é o que permite
mudar a jornada sem mudar o contrato do canal.

### O componente devolve o callback como recebeu

Ao responder um passo da jornada, o callback emitido pelo provedor é copiado e
apenas o valor da entrada é substituído. Remontá-lo de memória obrigaria o
componente a conhecer o formato de cada jornada.

**Isto foi observado na prática:** enviar o callback sem o bloco `output` fez o
provedor responder 500.

### A credencial de serviço é do componente

Quem chama o guardião é o sidecar, com usuário e senha próprios. O que o canal
apresentou fica para trás — o canal não alcança o guardião.

A credencial é guardada em memória enquanto vale, com folga de renovação. Não
sobrevive ao processo.

### Um cabeçalho, dois sentidos

`x-empresa-authentication-am` é o mesmo nome nas duas direções: o canal envia a
referência, o componente escreve o token. Por isso ele **precisa estar
reservado** — fora da lista, o valor do canal atravessa junto e o BFF pode ler
a referência achando que é token.

Os cabeçalhos do bloco `channel` são acrescentados à lista de reservados
automaticamente, sem depender do arquivo de configuração.

### 428 para o desafio, não 401

O canal já usa 401 para sessão do Cognito expirada. Misturar obrigaria a
inspecionar o corpo para saber se renova a sessão ou pede código ao usuário.

### Recusa por corpo é 400, não 403

Os códigos de recusa do provedor são classificados em duas ações: o que um novo
código resolve (403) e o que não resolve (400). Repetir com o mesmo corpo
malformado só produz a mesma recusa.

---

## Pontos de atenção

### O componente não faz polling

**Decisão de projeto:** o componente não implementa espera ativa. Ele faz uma
chamada ao provedor por passo e devolve o resultado.

Se uma jornada emitir `PollingWaitCallback` — pedindo que o cliente aguarde e
reenvie o mesmo corpo até haver desfecho —, o componente **não trata**: o
callback seria interpretado como um desafio comum e devolvido ao canal, que não
saberia o que fazer com ele.

**Consequência:** jornadas que dependem de polling não são atendidas por este
componente. Isso inclui:

- `factor-onboarding` — entra em polling enquanto a biometria é analisada
- `*******` — o lado do PDC é um laço de polling aguardando o
  aplicativo

As jornadas atendidas precisam concluir de forma síncrona, um passo por chamada.

Manter a espera fora do componente é o que permite que ele siga sem estado e
fora do caminho de decisões que não são dele: uma espera de minutos dentro do
POD do serviço de negócio consumiria um encadeamento por transação em
andamento.

### O componente não verifica o vínculo entre corpo autorizado e corpo efetivado

**Verificado em teste:** autorizar com `channel` + `risk` + `authN` e efetivar
sem o `authN` passa pelo sidecar sem recusa.

Isso é esperado — o sidecar não vê o resumo calculado pela jornada. Pelo
desenho, quem compara é o **Resource Server**: ele recebe as claims e o resumo
no introspect, e compara com a transação que vai efetivar.

**A confirmar com a equipe responsável:**

- A comparação está prevista no Resource Server?
- Como o resumo é calculado do lado dele?
- A regra de canonicalização é a mesma do nó da jornada?

Se ninguém comparar, a proteção contra troca de dados entre as duas chamadas
não existe, mesmo com toda a mecânica montada.

### O componente precisa ser inescapável

A camada só sustenta o que promete se:

- não houver rota que chegue ao BFF sem passar pelo componente
- o BFF recusar chamada sensível sem `x-empresa-authentication-am`

Isso depende de topologia de rede e de validação no BFF, não do componente.

### Cabeçalho restrito da plataforma

Quando `service-credentials.host-header` está preenchido, a máquina virtual
precisa de:

```
-Djdk.httpclient.allowRestrictedHeaders=host
```

Sem isso o cabeçalho é descartado **sem erro e sem registro**: a credencial é
emitida pela identidade do endereço chamado, e o guardião a recusa com uma
mensagem que fala de credencial revogada.

O componente avisa na subida quando detecta essa combinação, e registra o
emissor da credencial obtida.

### O componente está no caminho crítico

Toda transação passa por ele duas vezes, e a segunda soma uma chamada ao
guardião. Latência e disponibilidade viram dependência direta da transação.

---

## Em aberto

| Item | Situação |
|---|---|
| Revoke | O diagrama prevê BFF → sidecar → guardião → provedor. O sidecar não expõe rota própria; falta definir por onde entra |
| Consumo da referência | Hoje só é invalidada no revoke. Se o Resource Server falhar após o introspect, a referência segue válida |
| Códigos de recusa | Só os documentados foram mapeados. Códigos não previstos caem em `UNKNOWN` e viram 403 — falta a lista completa de quem mantém as jornadas |
| Resiliência | Tempo limite configurado; nova tentativa e disjuntor ainda não |
| Spring Security | Ativo no contexto, herdado do arquétipo. Não deveria existir num componente que não valida token — investigar interferência e latência |
| Fluxo com desafio | Mecânica verificada de ponta a ponta. Não exercitado na jornada transacional, que exige o código no início |

### Ajustes sugeridos na jornada

- **Lista completa dos códigos de recusa.** O componente classifica a recusa em
  duas ações — o que um código novo resolve, e o que não resolve — a partir do
  `detail.errorCode`. Hoje só os códigos documentados estão mapeados
  (`002`, `003`, `006`, `014`, `015`); os demais caem em desconhecido e viram
  403.

  **Verificado em teste:** o `factor-onboarding` devolveu `007`, que não consta
  em nenhuma das documentações recebidas. Sem a lista completa, recusas com ação
  diferente chegam ao canal como a mesma coisa.

  Vale também que jornadas sem numeração passem a usá-la: algumas recusam com
  `message` apenas, e a mensagem é texto livre — não serve para decidir.

- **Campos obrigatórios explícitos.** O erro `014` diz que falta campo
  obrigatório, sem dizer qual. Devolver o nome no detalhe economizaria
  diagnóstico.
- **Código próprio para fator não cadastrado.** Hoje some no `Authentication
  Failed` genérico, junto de código errado e tentativas esgotadas — situações
  com ações diferentes.

---

## Configuração

| Bloco | Do que trata |
|---|---|
| `proxy` | destino, tempos limite, teto de corpo, rotas verificadas |
| `channel` | cabeçalhos do contrato com o canal |
| `identity` | provedor: endereço, realm, credenciais do cliente, cabeçalhos |
| `service-credentials` | credencial do componente para serviços internos |
| `token-handler` | endereço do guardião |

Endereços terminados em `.invalid` nos padrões não resolvem de propósito: uma
chamada acidental falha por indisponibilidade em vez de sair pela rede.

### Registro

Nenhum registro mostra token, código de autenticador ou corpo de transação. O
que aparece: identificação da regra, desfecho da jornada, código de recusa e
emissor da credencial.

`org.springframework.web.client` em DEBUG mostra cabeçalhos e corpo das chamadas
— **não deixar fora da estação de trabalho**.
