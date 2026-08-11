# Version the app as v2 and show it in the UI

> Execution spec. Closed scope: **only** what is here.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), base branch: **`spike/spring-boot-4-spring-ai-2`**.
> Run everything from `back/avento`.

📖 **The root `AGENTS.md` wins.** Where it and this spec disagree, it wins and you report it.

**All new code, comments, log messages and documentation in ENGLISH.** Owner's call, restated on
10/08/2026. UI strings stay pt-BR, as they always were.

**Tree state:** dirty, with four uncommitted tasks from tonight (single embedding model, localStorage
removal, reindex tests, manifest owns the index name). The Maven suite was green for the classes those
touched. This is expected — **do not stop because of it.**

⚠️ **`npm test` in your sandbox may fail with `TypeError: localStorage.getItem is not a function`** —
that is an invalid `--localstorage-file` in the sandbox environment, not in the project. Measured: the
same test passes outside. **Not a regression, do not stop on it.** Any other failure is real: stop and
report.

---

## 1. Why, and what the number means

There is no version anywhere today. Measured:

| Where | Today |
|---|---|
| `back/avento/pom.xml` | `1.0.0-SNAPSHOT` |
| `front/package.json` | `0.0.0` |
| Actuator / build-info | **absent** — no `BuildProperties`, no version endpoint |
| UI | shows nothing |

**The version is a release milestone, not a build counter.** Owner's definition: **v1 was the first
version published on LinkedIn.** What is in the tree now is the second such milestone — Spring Boot 4 +
Spring AI 2, the structural refactor, and a single embedding model. So this becomes **`2.0.0`**, and
the major bump is justified by the platform migration alone.

Question it answers day to day: *"which build am I actually running?"* Right now nothing can answer
that.

---

## 2. Decisions — do not renegotiate

1. **The Maven version is the single source of truth.** The front reads it from the backend; it does
   **not** keep its own number. Two numbers means two truths and they drift.
2. **`front/package.json` stays at `0.0.0`** — deliberately, so nobody mistakes it for the product
   version. Say so in a comment where the front renders the version.
3. **Display only.** The screen shows the version. It does **not** compare versions, warn, or block on
   mismatch. Owner chose this explicitly over the enforcing variants.
4. **Root pom goes to `2.0.0`**, and every module that pins the parent version follows.

---

## 3. Backend

### 3.1. Build metadata without a new dependency

`spring-boot-maven-plugin` has a **`build-info`** goal that writes `META-INF/build-info.properties`
and makes a `BuildProperties` bean available. Prefer it: no new dependency, and it is the idiomatic
path.

That gives you **version** and **build time**.

**The git commit sha is optional.** It needs a separate plugin, and the value here is much smaller than
version + build time. If adding it is more than a few lines, **leave it out and say so in the report** —
do not drag a plugin in for it.

### 3.2. The endpoint

A read-only endpoint returning the version and the build time. Keep the payload minimal.

⚠️ **Verify whether it needs to be reachable without authentication.** The front may want to render the
version on the login screen, before any token exists. Check the security configuration and **report what
you found** rather than assuming — if the version needs an entry in the permitted paths, add it, and say
which file you changed.

**Do not expose anything else through it.** No environment, no active profiles, no dependency list, no
paths. A version endpoint that leaks the stack is a gift to whoever is scanning.

**Do not add Spring Boot Actuator** for this. It brings a whole surface area — health, metrics, env —
that nobody asked for and that then has to be secured.

---

## 4. Frontend

Render the version in a **discreet** spot — it is provenance information, not a feature. The settings
dialog (`front/src/modules/layout/SettingsModal/index.tsx`) already has an account/profile area and is
the natural home. Do **not** add a new nav item, badge, or banner for it.

Fetch from the endpoint; **do not hardcode**, and **do not read `package.json`**.

If the request fails, render nothing or a neutral dash. **The version must never be able to break the
screen** — it is the least important thing on it.

---

## 5. Out of scope — do not do

- **Do not compare front and back versions**, warn, or block. Decision 3.
- **Do not bump `front/package.json`.** Decision 2.
- **Do not add Actuator**, and do not expose environment, profiles, or config through the endpoint.
- **Do not create git tags** or touch git history.
- **Do not translate the existing pt-BR docs, comments or logs.** There are 11 learning files and
  several docs in pt-BR; sweeping them is a separate decision the owner has not made. Only what you
  write here is English.
- **Do not touch** the RAG, avatar, or cookie work sitting uncommitted in the tree.
- **Do not annotate `@Disabled` nor loosen an assert.** If a test that passed before fails, stop and
  report.
- **Do not commit.** And never `src/main/resources/agent/policies/`.

---

## 6. Tests

- the endpoint answers with the version from the build metadata, not a hardcoded string
- the front renders what the endpoint returned
- the front renders without breaking when the request fails

## 7. Validation

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

- suite green
- `grep -rn "1.0.0-SNAPSHOT" back/avento --include="pom.xml"` returns **nothing**
- `front/package.json` still says `0.0.0`

## 8. Delivery

Conventional commits, in English:

```
build: release the second published milestone as 2.0.0
feat(api): expose the running version and build time
feat(front): show which build is running, discreetly
```

In the report: whether the endpoint needed to be unauthenticated and which file you changed for it,
whether you included the git sha and why, and where in the settings dialog the version landed.
