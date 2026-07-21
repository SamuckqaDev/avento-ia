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

## Convenção

- **Visão geral / onboarding:** numeração baixa, explicação de conceito amplo (ex.: `00-visao-geral`).
- **Problema que deu trabalho ("passamos mal pra resolver"):** um arquivo por caso, nomeado pelo
  problema (ex.: `01-mockup-cortado-pelo-contexto.html`, `02-...`). Cada um conta: o sintoma que
  apareceu, a causa raiz de verdade, e como foi resolvido — no mesmo estilo visual e didático da
  visão geral.

Sempre que um problema custar caro pra achar, ele vira um doc aqui. O objetivo é que o conhecimento
fique no projeto, não só na cabeça de quem debugou.
