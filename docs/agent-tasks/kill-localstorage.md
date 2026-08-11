# Tirar o localStorage: dado no banco, preferência em cookie, só o tema fica local

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Levantado no código em 10/08/2026.** Todo `arquivo:linha` aqui foi lido nesta sessão.

**Estado da árvore ao despachar:** suja. Existe trabalho anterior não commitado da tarefa
`single-embedding-model.md` (backend RAG + `SettingsModal`), e a suíte Maven passava depois dele.
Isso é esperado, **não pare por causa disso**. Você vai encostar no mesmo `SettingsModal/index.tsx`
— leia o estado atual do arquivo, não presuma.

⚠️ **O `npm test` no seu sandbox mente.** Ele falha com
`TypeError: localStorage.getItem is not a function`, por causa de um `--localstorage-file` com caminho
inválido **no ambiente do sandbox** — não vem da configuração do projeto. Medido: o mesmo teste passa
fora do sandbox (`npx vitest run src/modules/layout/SettingsModal/SettingsModal.test.tsx` → 2 passed).
**Não trate essa falha como regressão e não pare nela.** Se aparecer, registre no relato e siga. Erro
de teste com qualquer outra mensagem é falha real: aí sim, pare e reporte.

---

## 1. A regra do dono

Decidida em 10/08/2026, sem margem:

- **Imagem de perfil e qualquer outro dado da pessoa: no banco**, no perfil de quem está logado.
- **Só o tema fica local.**
- **Todo o resto: cookie.**

---

## 2. O inventário completo, e para onde cada um vai

São **19 ocorrências** de `localStorage` em `front/src`. Nenhuma foi introduzida hoje.

| Chave | Onde está | Vai para | Por quê |
|---|---|---|---|
| `avento-theme` | [`App.tsx`](../../front/src/App.tsx#L22), `:27` | **fica em localStorage** | única exceção autorizada |
| `avento_avatar_url` | [`SettingsModal/index.tsx`](../../front/src/modules/layout/SettingsModal/index.tsx#L129), `:507`; [`Sidebar/index.tsx`](../../front/src/modules/layout/Sidebar/index.tsx#L139), `:170` | **banco**, em `UserAccount` | é dado da pessoa |
| `SELECTED_MODEL_KEY` | [`pages/Home/index.tsx`](../../front/src/pages/Home/index.tsx#L106), `:776` | **cookie** | preferência de navegador |
| `VOICE_ENABLED_KEY` | `pages/Home/index.tsx:114`, `:1006` | **cookie** | idem |
| `IMAGE_PREFERENCES_KEY` | `pages/Home/index.tsx:193`, `:897` | **cookie** | idem |
| `avento_auto_approve_all` | `pages/Home/index.tsx:812`, `:819`, `:832` | **nada — apagar o espelho** | ver 2.1 |
| — | [`LoginScreen.tsx`](../../front/src/modules/auth/LoginScreen.tsx#L111) | **não encoste** | é texto de UI, não armazenamento |

### 2.1. O `auto_approve_all` não vai para cookie

**Medido:** ele já persiste no servidor. `handleToggleAutoApproveAll` faz
`api.put('/api/settings', { autoApproveAll: next })` (`:824`), e um `useEffect` relê
`api.get('/api/settings')` e sobrescreve o estado (`:826-834`). O `localStorage` ali é **espelho de
pré-hidratação**, não fonte de verdade.

Pela regra "dado fica no banco", ele **já está no lugar certo**. Então some com as três linhas de
`localStorage` e deixe o servidor mandar. Não invente cookie para o que o banco já guarda.

Aceite que existe um piscar até o `GET /api/settings` responder. Se quiser evitá-lo, o caminho é o
valor vir no payload inicial da tela — **não** um cache local.

---

## 3. Avatar no banco

`UserAccount` ([`avento-auth/model/UserAccount.java`](../../back/avento/avento-auth/src/main/java/com/avento/model/UserAccount.java)) hoje tem
`id, email, displayName, passwordHash, role, active, createdAt, updatedAt`. **Não tem avatar.**

**`ddl-auto: update`** (`application.yml:289`) — a coluna nasce do campo na entidade. **Não escreva
arquivo de migração**, o projeto não usa Flyway nem Liquibase.

### 3.1. Guarde bytes, não data URL

O front hoje guarda `data:image/...;base64,...` inteiro. **Não replique isso no banco como texto.**
Guarde:

- `byte[]` anotado para large object, **nullable**
- o media type em coluna própria, **nullable**

Base64 infla ~33% e um data URL em coluna de texto acaba viajando em todo payload que serializar o
usuário.

### 3.2. Teto de tamanho, obrigatório

**Rejeite upload acima de um teto explícito** (proponha o valor no relato; algo na casa de centenas de
KB é sensato para avatar) e valide o media type contra uma lista de tipos de imagem.

Sem teto, uma foto de celular de 8 MB entra no banco e passa a ser arrastada em toda leitura de
perfil. Reduza no cliente antes de enviar.

### 3.3. Endpoints

`AuthController` já expõe `@RequestMapping("/api/auth")` com `GET /me` (`:81`). O usuário logado se
resolve com `@AuthenticationPrincipal AuthPrincipal principal` → `principal.userId()`, padrão já usado
em [`UsageController.java`](../../back/avento/avento-agent/src/main/java/com/avento/controller/UsageController.java#L25).

- **upload**: rota nova sob `/api/auth/me`, recebendo o arquivo
- **leitura**: rota que devolve **os bytes com o content type certo**, para o `<img src>` apontar
  direto

**Não embuta o base64 na resposta do `GET /me`.** O `/me` é chamado com frequência; anexar imagem a
ele encarece toda navegação. O `/me` pode indicar apenas *se existe* avatar.

### 3.4. Sincronia entre telas

Hoje `SettingsModal` avisa a `Sidebar` com `window.dispatchEvent(new Event('avento:avatar-changed'))`
(`:509`), porque localStorage não é reativo. **Esse evento pode ficar** — com o avatar no servidor,
ele passa a significar "recarregue a imagem", o que continua legítimo. Não troque isso por
polling.

---

## 4. Cookies

Um **único helper**, num módulo só, com `get`/`set`/`remove`. Não espalhe manipulação de
`document.cookie` pelas telas.

Para estas três preferências:

- `SameSite=Lax`, `path=/`, validade longa
- **sem `HttpOnly`** — o front precisa ler, e não são segredos. Isso é deliberado, escreva o porquê
  no código.
- **sem `Secure` fixo no código**: a app roda em `http://localhost:8000` em desenvolvimento e o cookie
  seria descartado. Derive do protocolo corrente.

⚠️ **Orçamento de 4 KB por domínio, somando todos os cookies.** O `IMAGE_PREFERENCES_KEY` é JSON
(`:897` serializa `{ lockSeed, seed, ... }`) — mantenha curto e **não** deixe crescer sem limite. Se
em algum momento não couber, o lugar é o banco, não um cookie maior.

---

## 5. Fora de escopo — não faça

- **Não transforme o tema em cookie.** Decisão explícita do dono: o tema é a exceção que fica local.
- **Não ponha imagem em cookie.** Não cabe em 4 KB e viajaria em toda requisição.
- **Não deixe leitura do localStorage como "fallback"** para as chaves migradas. Fallback é duas
  fontes de verdade, que é o defeito que esta tarefa existe para apagar.
- **Não escreva migração de dado** puxando o avatar do localStorage para o banco. É produto
  local de um usuário; reenviar a foto uma vez é mais barato que código de migração que ninguém mais
  vai executar.
- **Não escreva arquivo de migração de schema.** Ver 3.
- **Não mexa em `LoginScreen.tsx:111`** — é rótulo de tela.
- **Não toque no backend do RAG** nem nas specs da outra tarefa.
- **Não anote `@Disabled` nem afrouxe assert.** Se um teste real quebrar, pare e reporte — lembrando
  do falso positivo de sandbox descrito no topo.
- **Não commite.** Nem `src/main/resources/agent/policies/`.

---

## 6. Testes

- helper de cookie: ida e volta, ausência, e remoção
- endpoint de avatar: upload aceito, tipo inválido recusado, acima do teto recusado, leitura devolve o
  content type
- `SettingsModal` e `Sidebar` leem o avatar **do servidor**, não do localStorage
- `pages/Home`: as três preferências sobrevivem via cookie

## 7. Validação

```bash
grep -rn "localStorage" front/src --include="*.ts" --include="*.tsx"
```

Deve sobrar **só** `App.tsx` (tema) e o rótulo em `LoginScreen.tsx:111`. Qualquer outra linha é
trabalho não terminado.

Suíte backend:

```bash
cd back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

## 8. Entrega

Conventional commits, **em inglês**:

```
feat(auth): keep the profile picture in the database, on the user it belongs to
refactor(front): move browser preferences from localStorage to cookies
refactor(front): drop the local mirror of a setting the server already owns
```

No relato: o teto de tamanho escolhido, os nomes das rotas novas, e o que o `grep` final devolveu.
