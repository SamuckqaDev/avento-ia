# Roadmap — Tornar o Avento um agente de código forte, 100% local

> **Objetivo:** aproximar o Avento de um agente de código de ponta (estilo Claude Code) **sem nada
> na nuvem**. Tudo roda na máquina do usuário.
>
> **Explicitamente adiado (precisa de nuvem):** provider híbrido de fronteira (Anthropic/OpenAI API)
> e janela de contexto gigante. É o maior salto de qualidade que existe, mas depende de API externa —
> fica para um futuro distante, por decisão do usuário. **Não implementar agora.**
>
> Este documento é o mapa do que falta e é local. Cada item traz: o que é, por que importa, como
> fazer ancorado no código atual, e esforço. O item 1 está pronto para virar tarefa; os demais são
> desenho para escolher a ordem.

---

## O diagnóstico honesto

O Avento **não está sem features** — tem mais que muito agente (mídia, voz, RAG, artefatos). O que
separa ele de um bom agente de código são capacidades do **loop agêntico**, não funcionalidades. Tudo
abaixo é local.

---

## 1. Loop de verificar-e-corrigir (MAIOR impacto, faça primeiro)

**O que é:** depois de editar arquivos, o agente **roda o teste/build do projeto, lê o erro, corrige
e repete** até passar — sozinho. É o que transforma "gera código" em "entrega código que compila".

**Por que importa:** é a diferença nº1 no dia a dia. Hoje o Avento edita e para; o usuário é quem
descobre que quebrou.

**O que já existe (reaproveitar):**
- `ProjectCommandService.run(...)` — já roda `npm run {test|build|typecheck|lint|validate}` e
  `mvn {comando}` com allowlist e captura saída (`ProjectCommandResult`).
- `ProjectAnalysisService.analyze(...)` — já detecta a stack (pom.xml, package.json, gradle).
- Loop multi-rodada do `AgentService` (o modelo pode chamar ferramenta, ver resultado, chamar de
  novo) — a base do loop já está lá.

**Como fazer:**
1. **Detecção do comando de verificação** (novo método em `ProjectCommandService` ou
   `ProjectAnalysisService`): dado um workspace, retorna o comando canônico de verificação —
   `package.json` com script `validate` → `npm run validate`; senão `build`/`test`/`typecheck`;
   `pom.xml` → `mvn -q test-compile` (rápido) ou `mvn test`; `build.gradle` → `gradle build`.
2. **Nova ferramenta `verify_project`** (schema em `McpController`, dispatch para
   `ProjectCommandService`): roda o comando detectado no workspace autorizado e devolve
   `{ ok, command, exitCode, errors }` — com a saída de erro **truncada e focada** (só as linhas de
   erro/falha, não o log inteiro, pra caber no contexto de 8k).
3. **Skill `verify-and-fix.md`** com `Gatilhos:` de correção e um procedimento explícito: *depois de
   qualquer edição de código, chame `verify_project`; se falhar, leia os erros, corrija os arquivos e
   chame de novo; repita até passar ou até 3 tentativas; só então anuncie sucesso.* O loop acontece
   naturalmente nas rodadas do agente.
4. **Instrução base** em `agent/instructions/execution.md`: "nunca declare uma mudança de código
   pronta sem `verify_project` verde".

**Cuidados:** cap de tentativas (evitar loop infinito — o teto de rodadas já existe); truncar a saída
de erro; rodar só no workspace autorizado (o `WorkspaceAccessService` já garante isso).

**Esforço:** médio. É o melhor retorno por esforço e **100% local**.

---

## 2. Navegação de código por símbolo (não por texto)

**O que é:** entender "onde este método é definido", "quem chama isto", "onde este tipo é usado" —
em vez de só `grep`. Hoje o Avento só tem `search_files` (busca textual).

**Por que importa:** é como o agente constrói o modelo mental de um repo grande sem ler tudo. Sem
isso, ele relê arquivos e erra referência.

**Como fazer (local, sem nuvem):**
- Opção A (mais simples): **universal-ctags** instalado localmente. Uma ferramenta `find_symbol`
  gera/consulta um índice de tags do workspace e devolve definição + arquivo:linha. Barato, rápido,
  sem servidor.
- Opção B (mais rico): **tree-sitter** para parse por linguagem — permite "find references" real e
  estrutura (funções, classes). Mais poderoso, mais trabalho.
- Recomendo começar pela **A (ctags)** com uma ferramenta `find_symbol name` e, se valer, evoluir
  para tree-sitter.

**Esforço:** médio (A) / alto (B).

---

## 3. Checkpoint e rollback de uma sessão de mudanças

**O que é:** "desfazer" um conjunto de edições que o agente fez numa tarefa, de uma vez.

**O que já existe:** `FileBackupService` (backup/restore por arquivo). Falta o nível de **sessão**:
agrupar todos os backups de uma run sob um `runId` e um comando "reverter esta run".

**Como fazer:** ao editar/apagar arquivo dentro de uma run, o `FileBackupService` já guarda o backup;
adicionar um índice por `runId` e uma ferramenta/endpoint `revert_run(runId)` que restaura todos.

**Esforço:** baixo-médio (a base de backup já existe).

---

## 4. Confiabilidade do tool-calling

**O que é:** garantir que o modelo local chame a ferramenta certa (o teu maior perrengue da sessão).

**O que já melhoramos:** skills declaram `Ferramenta:`/`Ferramentas:` forçadas na seleção; retry de
turno vazio; kit fixo em chat de projeto.

**O que falta:** afinar o corte de schemas (menos ferramentas por rodada = prompt_eval mais rápido no
modelo pequeno), e um "modo estrito" que rejeita resposta sem tool-call quando a intenção claramente
pede ação.

**Esforço:** baixo (iterativo).

---

## 5. Planejamento estruturado (todo de verdade)

**O que é:** o agente quebra a tarefa em passos e **marca cada um como feito** conforme avança —
não só um bloco de plano estático.

**O que já existe:** o bloco ` ```plan ``` + a aba "Tarefas e Contexto".

**O que falta:** tornar o plano **mutável pelo modelo** durante a run (marcar passo concluído, próximo
em andamento), como um todo-list vivo. Melhora foco em tarefa longa e a percepção de progresso.

**Esforço:** médio (backend do estado do plano + UI da aba já existe).

---

## 6. Modo "Plano de Implementação" antes de codar (igual ao Antigravity)

**O que é:** para uma tarefa de código não trivial, o agente **primeiro gera um plano de
implementação detalhado** — objetivo, arquivos que vai tocar, passos na ordem, riscos e como vai
verificar — **mostra para o usuário e espera aprovação** antes de encostar em qualquer arquivo. É
exatamente o tipo de plano que se escreve para um dev executar; a diferença é que o próprio Avento
gera e, depois de aprovado, executa. É o recurso que o Antigravity tem e que o usuário quer aqui.

**Por que importa:** dá controle antes da ação (você revisa o rumo antes do código mudar), reduz
retrabalho e é o par natural do loop de verificar-e-corrigir (item 1): planeja → executa → verifica.

**O que já existe (reaproveitar):**
- Bloco ` ```plan ``` renderizado na aba "Tarefas e Contexto" (item 5) — a superfície de exibição.
- Permission Engine com gate de aprovação (aprovar/rejeitar por UI e por voz) — a base do "aprovar
  antes de executar" já existe, só é usada hoje para ferramentas.
- `ProjectAnalysisService` (para o plano citar stack/arquivos reais) e `search_files`.

**Como fazer:**
1. **Modo plano** (toggle no header, ou automático para pedidos claramente de implementação): quando
   ligado, a resposta do agente para uma tarefa de código é **um plano estruturado**, não edição.
   Formato fixo em um bloco ` ```impl-plan ` (novo, análogo ao `ui-preview`/`plan`): **Objetivo**,
   **Arquivos afetados** (caminhos reais, checados com `search_files`/análise), **Passos** numerados,
   **Riscos/o que pode quebrar**, **Como verificar** (o comando do item 1).
2. **Card de plano no chat** (frontend) renderiza esse bloco com dois botões: **Aprovar e executar** /
   **Ajustar** — reutilizando o padrão visual do gate de aprovação que já existe.
3. **Aprovar** injeta o plano aprovado como contexto e libera o agente para executar seguindo os
   passos (com o loop de verificar-e-corrigir do item 1). **Ajustar** volta a conversa para o usuário
   refinar antes de executar.
4. **Skill/instrução** `implementation-plan.md`: para pedido de implementar/refatorar/corrigir código
   com escopo real, produza primeiro o `impl-plan` e só codifique após aprovação. Pedidos triviais
   (uma linha, um rename) pulam o plano.

**Diferença para o item 5:** o item 5 é o *todo vivo durante* a execução; o item 6 é o *plano
detalhado + aprovação antes* de começar. Juntos: planeja (6) → aprova → executa marcando o todo (5) →
verifica (1).

**Esforço:** médio. Zero nuvem — é geração de texto estruturado + um card com gate de aprovação, tudo
reaproveitando peças que já existem.

---

## Ordem recomendada (tudo local)

1. **Verificar-e-corrigir** (item 1) — maior impacto, reaproveita o que já existe.
2. **Plano de implementação + aprovação** (item 6) — controle antes da ação; par natural do item 1
   (planeja → aprova → executa → verifica). É o que você pediu ("igual ao Antigravity").
3. **Navegação por símbolo** (item 2, opção A/ctags) — o agente passa a entender o código.
4. **Checkpoint/rollback por run** (item 3) — segurança pra deixar o agente editar mais solto.
5. **Todo vivo** (item 5) — o plano aprovado vira lista que o modelo marca durante a execução.
6. **Confiabilidade do tool-calling** (item 4) — afinação contínua, em paralelo.

**Deixado para o futuro (nuvem):** provider híbrido de fronteira + contexto gigante. Quando/se o
usuário topar nuvem, é o que mais aproxima do teto de qualidade — mas hoje está fora de escopo.

---

## Regras para qualquer implementação destes itens
- Formato (spotless), envelope `BaseResponse`, DTOs em `*.dto`, client `api` compartilhado no front.
- Não tocar em `agent/policies/` nem `~/.avento/`.
- Sem dependência de nuvem/API externa — tudo roda local.
- Teste novo por item; `mvn test` e `npm run validate` verdes.
- Doc atualizada (README, docs/*, docs.html). Commit em inglês, sem push até aprovação.
