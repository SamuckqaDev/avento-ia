# Bring the docs up to v2, in English

> Execution spec. Closed scope: **only** what is here. **Documentation only — do not change code.**
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), base branch: **`spike/spring-boot-4-spring-ai-2`**.

📖 **The root `AGENTS.md` wins.**

**Everything you write here is in ENGLISH.** Owner's call, 10/08/2026.

**Tree state:** dirty, five uncommitted tasks from tonight. Expected — do not stop because of it. Run
this only after `expose-app-version.md`, because the version number it sets is what the docs must state.

---

## 1. What changed tonight, and is not written down anywhere

All of this is **measured**, with the app, Postgres and Redis up. It is the raw material for the docs.

### 1.1. One embedding model, switching machinery deleted

`VectorStoreResolver`, `EmbeddingProfile`, `EmbeddingProfileSource`, `ProviderEmbeddingProfileSource`
and `RedisVectorStoreClientConfiguration` are **gone** — 5 classes and 2 tests, 476 deletions. The app
commits to **`nomic-embed-text`**.

Why deletion instead of repair: the machinery never worked a single day. It was born inert, one attempt
to fix it did not take, and every defect below existed *because* the system tried to support switching.

Measured comparison that drove the choice:

| | `nomic-embed-text` | `bge-m3` |
|---|---|---|
| Installed and reachable | yes, the only embedding in the local Ollama | no, and its host was offline |
| Dimensions | 768 | 1024 |
| Model size | 0.3 GB | ~2 GB |
| The 5.240 indexed docs | valid | re-embed everything |
| Tuned thresholds 0.45 / 0.72 | calibrated on it | recalibrate blind |

The index is now named after the model: **`avento_index_nomic_embed_text`**, from
`spring.ai.vectorstore.redis.index-name`.

### 1.2. The config key nobody read

The old code read `spring.ai.vectorstore.redis.index`, while Spring AI and `application.yml` use
**`index-name`**. The default masked the divergence and `application-local.yml` carried the wrong key.

### 1.3. The manifest owns the index name

`Manifest` gained an `indexName` field, and the manifest key went back to being **only the project
root**. When the index changes, the indexing pass deletes the chunks the previous manifest lists,
instead of abandoning them.

### 1.4. localStorage is out

Owner's rule: **profile picture and any user data in the database, on the logged-in user's profile;
only the theme stays local; everything else is a cookie.**

- avatar → `UserAccount` as bytes + media type, **512 KiB** cap, PNG/JPEG/WebP/GIF validated
- routes: `POST /api/auth/me/avatar`, `GET /api/auth/me/avatar`; `GET /api/auth/me` only reports
  `hasAvatar` and never carries base64
- selected model, voice, image preferences → cookies, `SameSite=Lax`, `Path=/`, one year, `Secure`
  derived from the protocol, deliberately not `HttpOnly` because the UI reads them
- `autoApproveAll` → the local mirror was **removed**; the server already owned it via `/api/settings`

### 1.5. Measurements worth keeping in the docs

- full reindex: **730 files, 5.280 chunks, ~31 docs/s, ~3 minutes**
- `nomic-embed-text`: 768 dimensions, **F16** (not quantized)
- chat models: `granite4.1:8b` and `qwen3.5:9b`, both **Q4_K_M** — what makes 8.8B fit in 5.3 GB on a
  16 GB machine
- chunk sizes in the live index: median **535** chars, p90 **1.752**, max **6.000** (the splitter
  ceiling), only **1,5%** above 5.000
- the embedding model does **not** truncate at 6.000 chars: replacing the last 1.200 / 2.400 / 3.600
  chars moved cosine to 0,986 / 0,963 / 0,923. Truncation would have left it at 1,000000

---

## 2. Files to update

**Read each one before editing.** Do not rewrite what is still true — add and correct.

1. **`docs/ARCHITECTURE.md`** — the RAG section: one embedding model, index named after the model, the
   manifest owning the index name. Remove any description of per-profile switching; it no longer exists.
2. **`docs/index.html`** — the public page. State the version, and correct anything that describes
   embedding-model switching as a feature.
3. **`docs/FEATURES.md`** — same correction. `docs/FEATURES.pt-BR.md` is the pt-BR twin: keep it
   consistent, and **it stays in pt-BR** (it is the translated variant, that is its whole purpose).
4. **`docs/HANDOFF.md`** — current state, and the open items in section 4 below.

---

## 3. Three new learning pages

`docs/aprendizados/` runs `00`–`10`. Add **`11`, `12`, `13`**, following the existing pages' shape and
styling — read two of them first. Structure is **symptom → cause → fix**, visual and didactic.

Content in English. Filenames: the existing ones are pt-BR, so **keep the numeric prefix and use short
English slugs** for the new ones; note the mixed naming in the report rather than renaming old files.

### 11 — A test that passed in an order production never has

**Symptom:** `FT._LIST` showed one index while the settings asked for another model. The suite was
green, including a test written specifically for that bean.

**Cause:** a component-scanned `@Configuration` carrying
`@ConditionalOnBean(JedisConnectionFactory.class)`. The factory comes from `DataRedisAutoConfiguration`
(Boot 4.1 — **not** `RedisAutoConfiguration`), which Spring Boot processes *after* user configuration,
so the condition evaluated false and the bean never entered the context. The test passed because
`ApplicationContextRunner.withBean(...)` registered the factory *before* importing the configuration —
an order production does not have.

**Fix:** `@ConditionalOnBean` is only reliable in auto-configuration classes. And a context test must
let auto-configuration in through `AutoConfigurations.of(...)` instead of planting the dependency by
hand.

**The lesson worth the page:** the first version of this defect hid behind a mock, the second behind
bean ordering. Both times the test asserted the intention rather than the reality.

### 12 — Putting the index name in the key stranded 5.240 chunks

**Symptom:** after naming the index after the model, Redis held **10.517** vector keys where 5.280 were
live, and **two** indexes reported the same count, climbing in lockstep.

**Cause:** two compounding mistakes. The manifest key included the index name, so the previous manifest
sat at an address nobody computed anymore — and with it every chunk it listed. And chunk ids derive from
the project key, so the new pass wrote *new* keys instead of overwriting. Meanwhile both indexes were
defined over the same `avento:` key prefix, so the retired index kept indexing everything the new one
wrote. Effect: the same content retrievable twice, quietly halving the diversity of the top-k.

**Fix:** the index name belongs *inside* the manifest, not in its key. One root, one manifest — so the
indexing pass can always see what the old index held and delete it.

**The lesson worth the page:** in RediSearch an index is defined over a **key prefix**. Renaming the
index isolates nothing while the data keys are shared.

### 13 — A green suite that proved less than it looked

**Symptom:** the delegated agent reported `mvn clean test` green, twice. Yet the leak the suite is known
to cause — scheduled Cowork tasks in Postgres, `@TempDir` keys in Redis — did not reappear:
`scheduled_tasks` stayed empty and the RAG keys stayed at exactly 954.

**Cause:** the agent's sandbox does not reach the containers, so the tests that write to real
infrastructure degraded instead of writing.

**Fix / the lesson:** the same suite means different things in different environments. Green in the
sandbox does not prove the paths that need real infrastructure. State *where* a suite ran when you
report it green.

---

## 4. Open items to record in `HANDOFF.md`

Do not solve these. Write them down, in this order:

1. **Nothing from tonight is committed** — five tasks in the working tree.
2. **The full suite has never run locally.** Only 23 tests across 4 classes ran on the real machine; the
   full green came from the sandbox. See learning 13.
3. **`isolate-tests-from-real-infra.md`** — still open, and now with a sharper edge: the leak only shows
   up when the suite runs on the owner's machine.
4. **`backend-owns-rag-cleanup.md`** — still open. Symptom found tonight: a `matsutech-sti` manifest
   declaring **1.887** chunks that no longer exist in Redis.
5. **Unbounded Redis streams** — `avento:jobs:agent` and `avento:dead-letter` are never trimmed, and the
   `avento-agent-workers` group has accumulated **83 consumers**, one per boot. Not dangerous: the
   worker acknowledges and drops a job whose row is gone. Growth without a ceiling, no spec written yet.
6. **pt-BR left in older code and docs** — log messages and comments predating tonight, plus 11 learning
   pages. Sweeping them is a decision the owner has not made.

---

## 5. Out of scope — do not do

- **Do not change code.** Not one line. This is a documentation task; if a doc and the code disagree,
  the doc is wrong — fix the doc and report the disagreement.
- **Do not translate existing pt-BR files**, and do not rename the old learning pages.
- **Do not touch `docs/FEATURES.pt-BR.md` into English** — it exists to be the pt-BR variant.
- **Do not delete `docs/HANDOFF-SESSAO-10-08.md`** nor the superseded specs in `docs/agent-tasks/`. They
  are the record of how the diagnosis went.
- **Do not invent measurements.** Every number in these docs is in section 1. If you want to state one
  that is not there, leave it out and say so.
- **Do not commit.** And never `src/main/resources/agent/policies/`.

## 6. Validation

- the four files in section 2 mention no embedding-model switching as a live feature
- `docs/aprendizados/` has three new pages, matching the existing pages' structure and style
- `HANDOFF.md` lists the six open items
- every number traces back to section 1

## 7. Delivery

```
docs: bring the architecture and feature docs up to v2
docs(aprendizados): three cases from the night the RAG index changed name
docs: hand off what v2 left open
```

In the report: which doc claims you found disagreeing with the code, and anything in section 1 you could
not place.
