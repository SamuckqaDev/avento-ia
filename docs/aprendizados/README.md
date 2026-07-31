# Aprendizados

Documentos **explicativos e visuais** sobre o Avento — pensados pra quem chega no projeto entender o
terreno, e pra registrar as coisas que **deram trabalho pra resolver** (pra ninguém sofrer duas
vezes com o mesmo problema).

Cada arquivo `.html` é autocontido — abra direto no navegador (duplo clique) ou publique como página.
Segue o OS no claro/escuro.

## Comece por aqui

- **[00 — Visão geral: IA, LLM e contexto](00-visao-geral.html)** — porta de entrada. O que é um LLM,
  token, contexto (a janela de 8192), "thinking", ferramentas e o loop do agente, por que o modelo
  alucina, o trade-off local vs nuvem, as camadas do Avento e como pensar/diagnosticar junto. Não é
  sobre um bug específico — é o mapa geral pra colaborar no projeto.

## Casos

- **[01 — Apagar um chat apagou a mídia de todos](01-exclusao-de-chat-apagou-midia-de-todos.html)** —
  a exclusão decidia de quem era cada arquivo lendo o *texto* da conversa, não a tabela de posse.
  Como a pasta de mídia é compartilhada e a IA escreve o caminho do arquivo nas respostas, apagar um
  chat destruía imagens que outros chats ainda usavam. Inclui o agravante de apagar arquivo dentro
  da transação (banco tem rollback, disco não tem).

- **[02 — Dois modelos: um pensa, o outro executa](02-dois-modelos-planejador-executor.html)** —
  o padrão planejador/executor. Por que o Avento estava travado (num_ctx no dobro, um modelo só),
  como os dois modelos trabalham em sequência (planeja 1x com qwen → troca → executa com granite,
  nunca em paralelo), as quatro mudanças exatas, e como a triagem de ferramentas por intenção
  evita alucinação — incluindo o bug que deixa o PDF invisível pro modelo.

- **[03 — Escrever arquivo escapava do workspace](03-escrita-escapando-do-workspace.html)** —
  ler funcionava e escrever era recusado no mesmo diretório. A raiz é registrada com link resolvido,
  mas um arquivo que ainda não existe só pode ser normalizado — e no macOS `/tmp` e `/var` são
  links. O lado grave é o inverso: um link dentro do workspace apontando para fora era aceito, e a
  escrita caía fora da raiz autorizada.

- **[04 — A busca no código nunca funcionou](04-busca-no-codigo-nunca-funcionou.html)** —
  `computeIfAbsent` chamando uma função que grava no próprio mapa. O `ConcurrentHashMap` lança
  `Recursive update`, e como isso impedia o índice de encher, toda chamada era a primeira. Quebrada
  desde que foi ligada.

- **[05 — A ferramenta existia, o despachante não a conhecia](05-classe-duplicada-entre-modulos.html)** —
  duas classes `LocalToolNames` em módulos diferentes, divergindo por uma entrada. Quem vence é a
  ordem do classpath, e venceu a defasada: `schedule_task` virou "Tool not found". Mais 22
  duplicatas foram encontradas na varredura.

- **[06 — O gateway rodava uma flag que não existe](06-gateway-mcp-com-flag-inexistente.html)** —
  `--profile` não é flag do Docker MCP Toolkit, e o perfil nunca configurado escondia o erro real
  atrás de um erro de configuração. O teste ao lado já usava a flag certa, mas estava desligado por
  variável de ambiente.

- **[07 — Trocar o modelo no seletor não trocava nada](07-seletor-de-modelo-ignorado.html)** —
  um literal `"qwen3.5:9b"` chumbado num serviço, contradizendo a configuração, mais uma heurística
  que lia "pediu o default" como "não pediu nada". Sozinho nenhum dos dois causaria o sintoma.

### O que os cinco tinham em comum

Todos sobreviveram a uma suíte de 589 testes verdes. Três causas de raiz se repetem:

- **Ferramenta que ninguém executa.** Eram 41 ferramentas e nenhuma exercitada de ponta a ponta.
- **Teste que existe mas não roda.** O do gateway sabia a flag certa e estava atrás de uma variável.
- **Configuração contradita por um literal no código.** O modelo padrão dizia uma coisa, um `String`
  escondido num serviço dizia outra.

Suíte verde não é o mesmo que comportamento verificado.

## Convenção

- **Visão geral / onboarding:** numeração baixa, explicação de conceito amplo (ex.: `00-visao-geral`).
- **Problema que deu trabalho ("passamos mal pra resolver"):** um arquivo por caso, nomeado pelo
  problema (ex.: `01-mockup-cortado-pelo-contexto.html`, `02-...`). Cada um conta: o sintoma que
  apareceu, a causa raiz de verdade, e como foi resolvido — no mesmo estilo visual e didático da
  visão geral.

Sempre que um problema custar caro pra achar, ele vira um doc aqui. O objetivo é que o conhecimento
fique no projeto, não só na cabeça de quem debugou.
