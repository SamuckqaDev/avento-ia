# A limpeza do RAG passa a ser do backend

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

⚠️ **Depende de `isolate-tests-from-real-infra.md` estar feita.** Sem aquilo, qualquer contagem no
Redis vem contaminada por lixo de teste e você não consegue provar que esta tarefa funcionou.

---

## 1. O problema

Quando um projeto sai da lista, quem manda limpar o RAG é **o navegador**. Em
`front/src/pages/Home/index.tsx:1289`:

```js
const removidos = anteriores.filter(p => !atuais.includes(p));
if (removidos.length > 0) {
  api.post('/api/rag/clear', { projectPaths: removidos })
    .catch(error => console.error('Erro ao limpar contexto RAG', error));
}
```

Duas fragilidades, e as duas são de desenho:

**Só funciona com a página aberta.** É um `useEffect` comparando com um `ref` da sessão do navegador.
Projeto que sai da lista sem a página montada não dispara nada.

**Desiste em silêncio.** `catch(console.error)` — fire-and-forget. Backend fora do ar naquele instante,
o RAG fica sujo, o `ref` já avançou, e **nunca mais tenta**. Só se descobre olhando o Redis.

**Decisão do dono, tomada: a limpeza é responsabilidade do backend.** O front pode continuar avisando,
mas não pode ser o único guardião.

---

## 2. O que NÃO está quebrado

- **`RagService.clearProjects(List<String>)` está correto** (`:114-128`): lê o manifesto, apaga os
  chunks listados, apaga manifesto e versão. **Reaproveite; não reescreva.**
- **O endpoint `/api/rag/clear` funciona** e continua útil — inclusive para limpeza manual.
- **A suíte passa: 811 testes, 0 falhas.**

---

## 3. Fatos que restringem a solução

### 3.1. Existe evento de registro, mas NÃO de remoção

Medido:

```
WorkspaceAccessService:67 → publishEvent(new WorkspaceRootRegisteredEvent(root, userId))
WorkspaceIndexingService:76 → @EventListener onWorkspaceRegistered(...)
```

Esse é o par que **aquece** o índice. **Não há equivalente para saída.** O
`WorkspaceAccessService` guarda as raízes num `Map<String, Set<Path>>` (`:26`) e só tem
`clearUser(UUID)` (`:125`), que limpa o usuário inteiro.

**A peça que falta é o evento de remoção**, publicado onde a raiz sai — e um ouvinte no `avento-rag`
que chame o `clearProjects` que já existe. Simétrico ao que já funciona para registro.

### 3.2. Cuidado: "não está na lista agora" ≠ "foi removido"

A tentação é varrer periodicamente e apagar todo manifesto cujo projeto não esteja registrado.
**Isso apaga índice de projeto que a pessoa só não abriu hoje** — e reindexar um repo custa minutos.

Remoção tem de ser **evento explícito**, disparado quando a raiz de fato sai. Nada de varredura por
ausência.

### 3.3. Não faça a limpeza dentro da requisição

Apagar chunks de um projeto grande percorre o manifesto inteiro. Se isso rodar na thread da
requisição, quem removeu o projeto espera olhando a tela.

Siga o padrão que o `WorkspaceIndexingService` já usa para a indexação: **fora da requisição**, em
thread própria. Ele é o modelo.

### 3.4. Limpeza tem de ser idempotente

O evento pode chegar duas vezes — o front ainda chama o endpoint, e o backend passa a chamar sozinho.
Apagar o que já não existe **não pode** virar erro nem log de alarme.

**Suposição minha, confira:** o `clearProjects` parece tolerar manifesto ausente (`readManifest`
devolve algo vazio), mas eu não verifiquei. **Leia antes de confiar**, e se não tolerar, faça tolerar.

---

## 4. Decisões de projeto — não renegociar

1. **Evento de remoção**, simétrico ao `WorkspaceRootRegisteredEvent`. (3.1)
2. **Ouvinte no `avento-rag`** chamando o `clearProjects` existente. Não duplique a lógica.
3. **Fora da requisição.** (3.3)
4. **Idempotente.** (3.4)
5. **Nada de varredura por ausência.** (3.2)
6. **O front continua como está.** Removê-lo é outra tarefa; dois caminhos chamando limpeza
   idempotente não fazem mal.

---

## 5. Tarefas, em ordem

### T1 — Achar onde a raiz de fato sai, e reportar

Leia `WorkspaceAccessService` e diga **todos** os pontos em que uma raiz deixa de estar autorizada:
`clearUser`, expiração de sessão, troca de escopo, o que houver.

Se **não existir** ponto de remoção individual — só o `clearUser` do usuário inteiro — **isso é o
achado principal** e muda a tarefa: seria preciso criar a operação antes do evento. **Reporte antes
de escrever código.**

Commit próprio.

### T2 — Publicar o evento

`WorkspaceRootRemovedEvent(Path root, UUID userId)`, publicado onde a T1 apontou. Espelhe o
`WorkspaceRootRegisteredEvent` — mesmo pacote, mesma forma.

### T3 — Ouvir e limpar

`@EventListener` no `avento-rag`, fora da requisição (3.3), chamando `clearProjects`. Idempotente
(3.4).

### T4 — Teste

Que o evento dispara a limpeza, e que **disparar duas vezes não quebra**.

Se precisar de Redis real, siga o padrão da spec de isolamento: apague no `@AfterEach` o que criar.

### T5 — Verificação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

---

## 6. Validação

- **≥811 testes, 0 falhas**
- `RagService.clearProjects` **não foi reescrito** — só passou a ter outro chamador
- a limpeza não roda na thread da requisição
- disparar o evento duas vezes não gera erro

---

## 7. Fora de escopo — não faça

- **Não varra o Redis apagando manifesto órfão.** (3.2) Projeto fechado não é projeto removido, e
  reindexar custa minutos.
- **Não reescreva `clearProjects`.** Ele está certo.
- **Não remova a chamada do front.** Outra tarefa; limpeza idempotente aguenta os dois.
- **Não apague `avento:rag:*` em bloco** por nenhum motivo.
- **Não anote `@Disabled` nem afrouxe assert.**
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

```
docs(agent-tasks): map where a workspace root actually stops being authorised
feat(workspace): announce when a root is removed, not only when it is registered
feat(rag): clear a project's index when its root goes away
```

No relato:

- **onde a raiz sai de fato** — e se existia ponto individual ou só `clearUser` (T1)
- se o `clearProjects` já era idempotente ou precisou virar
- como a limpeza saiu da thread da requisição
