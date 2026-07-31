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

- **[03 — Cinco defeitos que nenhum teste pegava](03-cinco-defeitos-que-nenhum-teste-pegava.html)** —
  o projeto tinha 589 testes verdes e 41 ferramentas, e nenhuma delas jamais fora executada de ponta
  a ponta. Exercitá-las revelou cinco defeitos em sequência: escrita de arquivo escapando do
  workspace por link simbólico, a busca no código quebrada desde que foi ligada, uma ferramenta
  registrada que o despachante não reconhecia por classe duplicada entre módulos, o gateway MCP
  rodando uma flag inexistente, e o seletor de modelo sendo ignorado por um literal escondido num
  serviço. Inclui o padrão comum aos cinco e os testes que ficaram de guarda.

## Convenção

- **Visão geral / onboarding:** numeração baixa, explicação de conceito amplo (ex.: `00-visao-geral`).
- **Problema que deu trabalho ("passamos mal pra resolver"):** um arquivo por caso, nomeado pelo
  problema (ex.: `01-mockup-cortado-pelo-contexto.html`, `02-...`). Cada um conta: o sintoma que
  apareceu, a causa raiz de verdade, e como foi resolvido — no mesmo estilo visual e didático da
  visão geral.

Sempre que um problema custar caro pra achar, ele vira um doc aqui. O objetivo é que o conhecimento
fique no projeto, não só na cabeça de quem debugou.
