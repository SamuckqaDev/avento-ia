# Plano de Implementação — Hub de Conta/Config no rodapé + Métricas de Token (nível júnior)

> **Como ler:** você é o dev júnior. Faça **exatamente** o que está aqui, na ordem. Blocos de código
> são para copiar e adaptar só o indicado. **⚠️ QUEBRA O BUILD** = ponto que não compila se esquecer.
> Se algo divergir do código real, **pare e pergunte** — não invente nome de método, rota ou pasta.
>
> **Baseline:** `mvn -f back/avento/pom.xml test` = 332 testes, 0 falhas. Não pode baixar disso.

---

## 0. Regras que valem para TUDO (leia antes de tocar em qualquer arquivo)

1. **Formatação:** `mvn -f back/avento/pom.xml spotless:apply` antes de cada commit (formato palantir).
2. **Resposta REST:** todo endpoint JSON retorna `BaseResponse<T>` via `ApiResponses.ok(...)`. O front
   consome pelo client compartilhado `front/src/services/apiClient.ts` (`import { api }`), que
   **desembrulha `data` sozinho**. **NUNCA** use `axios` cru nem `responseType: 'text'` numa chamada
   de envelope (já quebrou antes). Binário usa `responseType: 'blob'`.
3. **DTOs:** record/classe novo em `com.avento.api.dto` (API) ou `com.avento.service.dto` (interno).
   Nunca record dentro de controller/service.
4. **NÃO TOQUE:** `back/avento/src/main/resources/agent/policies/*.md` e nada em `~/.avento/`.
5. **npm:** pacote novo só com `--save-exact --ignore-scripts`. Prefira reusar o que já existe. Esta
   feature **não precisa de pacote novo** (gráfico é SVG feito à mão).
6. **Doc:** atualize `README.md` (as duas seções, EN e PT), o `docs/*.md` relevante e o
   `back/avento/src/main/resources/static/docs.html` (com um selo de status novo).
7. **Testes:** `mvn test` e `npm --prefix front run validate` verdes. Toda entrega adiciona teste.
8. **Commit:** um por entrega, mensagem em inglês (conventional commits), terminando com
   `Co-Authored-By`. **NÃO faça push.** O usuário aprova.

**Decisões já tomadas (não reabra):**
- Métrica de token grava **uma linha por rodada do Ollama** (agrega depois por dia/modelo/chat).
- O schema usa `ddl-auto: update` (já configurado) → a entidade nova cria a tabela sozinha, **sem
  migration**.
- Avatar da conta é de **iniciais** (do `displayName`), sem upload de foto nesta versão.
- Token aqui é métrica de custo **computacional** (local, Ollama), não dinheiro.

---

## Entrega A — Backend: métricas de uso de token

### Contexto
Hoje o `AgentService` (linha ~1790) já lê o `eval_count` (saída) de cada rodada do Ollama e emite um
evento efêmero `agent.tokens.usage` — mas **não grava nada** e **ignora o `prompt_eval_count`**
(entrada, que é o que mais pesa em tarefas de agente). Vamos capturar tudo e persistir.

> ⚠️ Não confunda com `/api/auth/token-history` — esse é de **sessão/auth** (tokens de refresh), nada
> a ver com tokens de LLM. O endpoint novo é `/api/usage/...`.

### A.1 — Entidade `TokenUsage` (sem migration)
Crie `back/avento/src/main/java/com/avento/model/TokenUsage.java`, espelhando o padrão de
`GeneratedMediaAsset` (mesma pasta):
```java
package com.avento.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "token_usage")
public class TokenUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "run_id")
    private String runId;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // getters/setters de todos os campos (siga GeneratedMediaAsset)
}
```

### A.2 — Repositório com agregações
Crie `back/avento/src/main/java/com/avento/repository/TokenUsageRepository.java`:
```java
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    @Query("select coalesce(sum(t.totalTokens), 0) from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since")
    long sumTotalSince(UUID userId, LocalDateTime since);

    @Query("select t.model as model, sum(t.totalTokens) as total from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since group by t.model order by total desc")
    List<ModelTotal> sumByModelSince(UUID userId, LocalDateTime since);

    @Query("select cast(t.createdAt as date) as day, sum(t.totalTokens) as total from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since group by cast(t.createdAt as date) order by day")
    List<DayTotal> sumByDaySince(UUID userId, LocalDateTime since);
}
```
`ModelTotal` e `DayTotal` são **interfaces de projeção** em `com.avento.service.dto`:
```java
public interface ModelTotal { String getModel(); long getTotal(); }
public interface DayTotal { java.time.LocalDate getDay(); long getTotal(); }
```

### A.3 — Serviço
Crie `back/avento/src/main/java/com/avento/service/TokenUsageService.java` (`@Service`,
`@RequiredArgsConstructor`, injeta `TokenUsageRepository`):
- `record(UUID userId, Long chatId, String runId, String model, int promptTokens, int completionTokens)`:
  monta um `TokenUsage`, seta `totalTokens = promptTokens + completionTokens`, `createdAt = now()`,
  salva. **Envolva em try/catch** e só logue em caso de erro — métrica **nunca** pode derrubar uma
  resposta do agente.
- `summary(UUID userId, String range)`: converte `range` (`today|7d|30d`) num `LocalDateTime since`
  (início do dia de hoje / -7d / -30d) e devolve um `UsageSummary` (record em `com.avento.api.dto`)
  com `long total`, `List<ModelTotal> byModel`, `List<DayTotal> byDay`.

### A.4 — Capturar no AgentService
No ponto onde hoje lê o `eval_count` (linha ~1790):
```java
if (node.path("done").asBoolean(false) && node.has("eval_count")) {
    sink.next(tokenUsageEventChunk(node.path("eval_count").asInt(0)));
}
```
**troque** por:
```java
if (node.path("done").asBoolean(false) && node.has("eval_count")) {
    int promptTokens = node.path("prompt_eval_count").asInt(0);
    int completionTokens = node.path("eval_count").asInt(0);
    sink.next(tokenUsageEventChunk(completionTokens));
    tokenUsageService.record(
            state.userId, state.chatId, state.runId,
            node.path("model").asText(model), promptTokens, completionTokens);
}
```
> ⚠️ **Contexto necessário:** esse trecho precisa do `AgentRunState state` e do `model` em escopo. Se
> o método que faz o parse da linha do Ollama **não** recebe `state` hoje, adicione-o na assinatura e
> propague a partir de quem chama (é uma mudança de assinatura que ripla para 1–2 chamadas —
> compile e siga os erros). Injete `TokenUsageService tokenUsageService` no construtor do
> `AgentService` (padrão dos outros services já injetados ali).

### A.5 — Endpoint
Crie `back/avento/src/main/java/com/avento/controller/UsageController.java`:
```java
@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final TokenUsageService tokenUsageService;
    // construtor

    @GetMapping("/summary")
    public ResponseEntity<BaseResponse<UsageSummary>> summary(
            @RequestParam(defaultValue = "7d") String range,
            @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = principal == null ? null : principal.userId();
        return ApiResponses.ok(tokenUsageService.summary(userId, range));
    }
}
```
(Veja `AgentTimelineController` para o padrão de `@AuthenticationPrincipal AuthPrincipal`.)

### A.6 — Teste
`TokenUsageServiceTest` (mock do repositório): `record(...)` monta o `TokenUsage` com
`totalTokens = prompt + completion` e chama `save`; `summary("today")` calcula o `since` certo e
repassa os agregados. Alvo: **≥ 334 testes** no total.

---

## Entrega B — Frontend: chip de conta no rodapé + painel com abas

### Contexto
A Sidebar já tem um `Footer` com um **botão de engrenagem com estilo inline** (fora do padrão) que
abre o `SettingsModal`. Vamos **remover esse botão**, pôr um **chip de conta no padrão dos botões da
sidebar** e transformar o modal num painel com abas. Os dados do usuário e o logout **já existem** no
`AuthProvider` (`useAuth`) — reuse, não crie fetch novo.

### B.1 — Remover o botão antigo (Sidebar/index.tsx, ~linha 320)
Apague este bloco inteiro do `<Footer>`:
```tsx
<button
  type="button"
  onClick={() => setIsSettingsOpen(true)}
  title="Configurações Locais"
  style={{ background: 'transparent', border: 'none', color: '#9FB8B1', cursor: 'pointer', display: 'flex' }}
>
  <Gear size={20} />
</button>
```
(Mantenha o `<p>Avento Model Context Protocol</p>` e o `{isSettingsOpen && <SettingsModal .../>}`.)

### B.2 — `AccountBtn` no MESMO padrão dos botões de cima (Sidebar/styles.ts)
Os botões da sidebar usam o styled `ActionBtn` (mesmo arquivo, ~linha 256). Crie um `AccountBtn`
reaproveitando **os mesmos tokens** (altura, borda, radius, hover, cores do tema) e um layout de
avatar + nome + caret:
```ts
export const AccountBtn = styled.button`
  width: 100%;
  min-height: 40px;
  height: var(--sidebar-control-height);
  padding: 8px 10px;
  background: ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 26%, ${({ theme }) => theme.colors.primary});
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease, border-color 0.2s ease;
  display: flex;
  align-items: center;
  gap: 8px;

  &:hover {
    background: ${({ theme }) => theme.colors.accent};
    border-color: ${({ theme }) => theme.colors.accent};
    transform: translateY(-1px);
  }

  ${Container}[data-minimized='true'] & {
    width: var(--sidebar-control-width);
    justify-content: center;
    padding: 0;
  }
`;

export const AccountAvatar = styled.span`
  width: 26px; height: 26px; flex: 0 0 26px;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: ${({ theme }) => theme.colors.accent};
  color: ${({ theme }) => theme.colors.white};
  font-weight: 750; font-size: 0.72rem;
`;

export const AccountName = styled.span`
  flex: 1; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-weight: 700; font-size: 0.85rem;

  ${Container}[data-minimized='true'] & { display: none; }
`;
```

### B.3 — Renderizar o chip no Footer (Sidebar/index.tsx)
Importe `AccountBtn, AccountAvatar, AccountName` dos styles e o `CaretUp` de `@phosphor-icons/react`,
e use o usuário do contexto de auth existente:
```tsx
import { useAuth } from '../../auth/AuthProvider'; // confira o caminho/nome exato do hook exportado
...
const { user } = useAuth();
const initials = (user?.displayName || user?.email || '?')
  .split(' ').map(p => p[0]).slice(0, 2).join('').toUpperCase();
...
<Footer className="hide-on-minimized">
  <AccountBtn type="button" onClick={() => setIsSettingsOpen(true)} title="Conta e configurações">
    <AccountAvatar>{initials}</AccountAvatar>
    <AccountName>{user?.displayName || user?.email || 'Conta'}</AccountName>
    <CaretUp size={16} />
  </AccountBtn>
</Footer>
```
> ⚠️ Confira em `front/src/modules/auth/AuthProvider.tsx` o nome exato do hook (`useAuth`) e o formato
> de `user` (tem `displayName`, `email`, `role` — do `UserResponse`). Não invente campos.

### B.4 — `SettingsModal` vira painel com abas
No `SettingsModal`, adicione um estado `activeTab` (`'conta' | 'uso' | 'prefs'`) e uma barra de abas
no topo do `Body`. Conteúdo de cada aba:

- **Conta:** o `AccountAvatar` grande com as iniciais, `displayName`, `email`, um badge com `role`, e
  um botão **Sair** que chama `useAuth().logout()` (já existe; faz `POST /api/auth/logout`).
- **Uso:** ao abrir a aba, `const { data } = await api.get('/api/usage/summary?range=7d')`. Mostre:
  - o total do dia em destaque;
  - um **gráfico de barras em SVG feito à mão** a partir de `data.byDay` (uma `<rect>` por dia,
    altura proporcional ao maior valor; sem biblioteca);
  - uma tabela simples `data.byModel` (modelo × tokens).
- **Preferências:** mantenha o toggle de TTS que já existe; adicione idioma de voz (pt/en/es) e,
  se quiser, os modelos padrão de chat/imagem. Tudo via client `api`.

> ⚠️ Use **sempre** o client `api` (import de `../../../services/apiClient`), **nunca** `axios` cru —
> foi o erro da versão anterior do modal.

### Teste / Aceite
- `npm --prefix front run validate` passa.
- O rodapé mostra o chip de conta no mesmo visual dos botões da sidebar; o botão de engrenagem antigo
  sumiu; minimizar a sidebar mostra só o avatar (padrão `data-minimized`).
- Abrir a aba **Uso** mostra o total de hoje, o gráfico de barras e a tabela por modelo com dados
  reais de `/api/usage/summary`.
- **Conta** mostra nome/email/role e o botão Sair desloga.

---

## Ordem de execução
1. **Entrega A** (backend de métricas) — precisa existir antes da aba Uso ter o que consumir.
2. **Entrega B** (chip + painel) — consome a A.

## Definition of Done (por entrega)
- [ ] `spotless:apply` rodado; DTOs/projeções nos pacotes `*.dto`; nada de record em controller.
- [ ] Endpoints em `BaseResponse`; front usa o client `api` (sem `axios` cru, sem `responseType:'text'`).
- [ ] Políticas e `~/.avento/` intactos; nenhum pacote npm novo.
- [ ] `mvn test` (≥334) e `npm run validate` verdes; teste novo incluído.
- [ ] Doc atualizada: README.md (EN+PT), docs/*.md, docs.html.
- [ ] Commit coeso, em inglês, **sem push** — aguardar o usuário.
