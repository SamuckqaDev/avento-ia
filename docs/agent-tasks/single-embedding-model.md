# Um modelo de embedding só, e apagar a máquina de troca

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026, com Redis, Postgres e a aplicação de pé.** O que é suposição
está marcado.

**Estado da árvore ao despachar:** suja, **só em `docs/agent-tasks/`** — esta spec é nova, e as duas
substituídas ganharam um aviso no topo. Nenhum código-fonte foi alterado, e a suíte estava verde
(811 testes) antes. Isso é esperado; não pare por causa disso.

**Substitui** `revive-vector-store-resolver.md` e `reindex-on-embedding-model-switch.md`. As duas
partiam de "consertar a troca de modelo"; **a decisão do dono mudou: não há troca.** Leia as duas se
quiser o histórico do diagnóstico, mas o que vale é esta.

---

## 1. A decisão

**Um único modelo de embedding: `nomic-embed-text`.** A capacidade de escolher o modelo de embedding
em runtime **sai** — código, tela e leitura de configuração.

Por que, medido hoje:

| | `nomic-embed-text` | `bge-m3` (o que o banco pede) |
|---|---|---|
| Instalado e alcançável | **sim**, único embedding no Ollama local | **não**, e a `dr` está offline há 1 dia |
| Dimensão | 768 | 1024 |
| Tamanho | 0,3 GB | ~2 GB |
| Os 5.240 docs indexados | válidos | reembedar tudo |
| Limiares 0.45 e 0.72 | calibrados nele | recalibrar às cegas |

A máquina tem 16 GB e roda outros projetos.

**Por que apagar em vez de consertar:** a máquina de troca nunca funcionou um dia. Nasceu inerte em
`827b7b7`, e o `67da45e` tentou consertar e não pegou. Cada defeito abaixo existe **porque** o sistema
tenta suportar troca. Sem troca, todos evaporam de uma vez:

- `@ConditionalOnBean` avaliado antes da auto-configuração registrar o `JedisConnectionFactory`
- fallback silencioso servindo índice que não corresponde ao modelo escolhido
- prefixo Redis compartilhado entre índices "isolados"
- manifesto e ID de chunk agnósticos ao modelo
- limiar de similaridade global para modelos com distribuição de score diferente
- store resolvido por lote, permitindo troca no meio de um reindex

**Suposição minha, confira e reporte:** que "`avento-rag` sem Redis" **não** é modo suportado. O
`avento-app` já declara Redis e Spring AI Redis. Se for suportado, diga — muda o T2.

---

## 2. O que está medido, e vale como base

Com a aplicação de pé em 10/08 ~21:50, reindex completo: **5.240 documentos, ~31 docs/s, ~3 min.**

```
FT._LIST → avento_index          (só ele)
FT.INFO  → dim 768, num_docs 5240, prefixes avento:
provider_settings.embedding_model → bge-m3:latest   (1024 dim, host offline)
```

O índice nasceu 768 com `bge-m3` configurado: o `build()` caiu no fallback. **Causa confirmada por
segunda leitura independente:** `RedisVectorStoreClientConfiguration` é `@Configuration`
component-scanned com `@ConditionalOnBean(JedisConnectionFactory.class)`; o factory vem da
`DataRedisAutoConfiguration` (Boot 4.1 — **não** `RedisAutoConfiguration`), processada depois da
configuração de usuário; a condição dá `false` e o bean nunca entra.

**Descartada, com motivo:** "o host offline fez `activeProfile()` cair no catch". Construir
`OllamaEmbeddingModel` não faz chamada HTTP — a indisponibilidade apareceria no primeiro `embed`, não
na construção.

---

## 3. O que NÃO está quebrado — não encoste

- **A suíte passa: 811 testes, 0 falhas** (`mvn clean test -Dtest='!DockerMcpGatewayLiveTest'`).
- **A busca funciona hoje**, com 5.240 documentos. Esta tarefa não pode piorar o caminho felizatual.
- O `contentHash` levar a **estratégia de corte** junto do conteúdo (`RagService:435`) é conserto
  anterior e está certo. **Mantenha.**
- O `deleteChunks` por manifesto está correto.
- `CodeAwareSplitter`, `WorkspaceDocumentRetriever`, `DocumentReaderService`: fora de escopo.
- Os limiares **0.45** (`RagService:82`) e **0.72** (`IntentEmbeddingClassifier:55`) continuam válidos,
  porque são calibrados para o `nomic` e o `nomic` fica. **Não mexa nos valores.**

---

## 4. A armadilha central desta tarefa

**Leia antes de escrever qualquer linha.**

O nome do índice vai passar a conter o modelo. Isso significa que o índice novo
**nasce vazio** — e o manifesto atual, que é `sha256(caminho absoluto)` e **não sabe do modelo**
(`RagService:422`), vai jurar que os 723 arquivos já estão indexados.

O resultado, se você não tratar: `RagService:183` compara o `contentHash`, que não mudou, pula todos
os arquivos, `documentsToAdd` fica vazio, o log diz **"723 arquivos lidos, 0 chunks atualizados"** e a
busca responde **zero, em silêncio**.

**Este é o mesmo defeito que o projeto já consertou uma vez.** O javadoc em `RagService:426` documenta
o sintoma idêntico para o chunker, com o log real de uma subida: *"94 arquivos lidos, 0 chunks
atualizados"*. O conserto de então colocou a estratégia de corte no hash e não o modelo.

**O conserto: o nome do índice entra no `projectKey`**, não no `contentHash`. O `contentHash` continua
respondendo "o conteúdo ou o corte mudaram?"; a identidade do índice pertence ao manifesto. Assim a
mudança do nome do índice troca a chave do manifesto, o reindex acontece naturalmente, e se alguém um
dia mudar o modelo no YAML **o mesmo mecanismo protege de graça**.

---

## 5. Decisões de projeto — não renegociar

1. **Apagar** `VectorStoreResolver`, `EmbeddingProfile`, `EmbeddingProfileSource`,
   `ProviderEmbeddingProfileSource`, `RedisVectorStoreClientConfiguration` e seus testes.
2. Quem consumia o resolver passa a injetar o **`VectorStore` e o `EmbeddingModel` autoconfigurados**
   direto. São `RagService`, `CodeSearchService` e o `IntentEmbeddingClassifier`.
3. **O nome do índice contém o modelo**, e vem de configuração — não fixado no código.
4. **`projectKey` inclui o nome do índice** (seção 4).
5. **A coluna `provider_settings.embedding_model` NÃO é apagada** nesta tarefa. Migração destrutiva de
   schema é decisão separada. Ela só deixa de ser lida.
6. Os valores dos dois limiares **não mudam**.

---

## 6. Tarefas, em ordem

### T1 — Corrigir a chave de configuração, que hoje está errada

O resolver lê `spring.ai.vectorstore.redis.index`; Spring AI e o `application.yml` usam
**`index-name`**. O default mascarava a divergência, e o `application-local.yml` está com a chave
errada. Ao apagar o resolver, garanta que **a chave correta** é a que vale, nos dois YAMLs.

Defina o nome do índice contendo o modelo, algo como `avento_index_nomic_embed_text`. Sufixo legível;
não invente hash aqui.

### T2 — Apagar a máquina de troca

As cinco classes da decisão 1 e seus testes. Aponte os três consumidores para os beans
autoconfigurados.

Se ao remover o `@ConditionalOnBean` alguma coisa exigir o `RedisClient`, **pare e reporte** — a
intenção é que ninguém mais precise dele; se alguém precisa, eu errei o desenho.

### T3 — `projectKey` com o nome do índice

`projectKey = sha256(raiz canônica + ":" + nomeDoIndice)`. **Nada de fingerprint elaborada** — é uma
constante de configuração agora, não perfil de runtime.

Documente no javadoc **por que**, referenciando o mesmo raciocínio do `contentHash` em
`RagService:426`. Quem ler daqui a seis meses tem que entender que isso é proteção, não capricho.

### T4 — Tirar o seletor da tela

`front/src/modules/layout/SettingsModal/index.tsx` — o campo de modelo de embedding sai
(`index.tsx:1036` é a entrada dele). Ajuste `SettingsModal.test.tsx`, que hoje fixa
`embeddingModel: 'bge-m3:latest'`.

O back para de ler a coluna; a coluna fica (decisão 5).

### T5 — Testes

- que o `projectKey` **muda** quando o nome do índice muda — é a prova da seção 4
- que trocar o nome do índice com manifesto cheio produz `documentsToAdd` **não vazio**
- que o contexto sobe e o `RagService` recebe o `VectorStore` autoconfigurado, **sem**
  `.withBean(...)` entregando dependência numa ordem que produção não tem — foi assim que o teste
  anterior passou sem provar nada

### T6 — Suíte completa

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Espere **menos** testes que 811, porque testes de classes apagadas somem. Diga quantos, e quais
sumiram por remoção — não por quebra.

---

## 7. Fora de escopo — não faça

- **Não apague a coluna `embedding_model`** nem escreva migração de schema. Ver decisão 5.
- **Não apague as 5.240 chaves nem o índice `avento_index` antigo.** Depois do reindex ele fica
  órfão, e aposentar é operação dirigida, do dono, com o índice novo já conferido. Cuidado: o prefixo
  `avento:` também guarda manifestos, versões e cache — **`SCAN avento:*` não é sinônimo de "os
  vetores"**.
- **Não toque nas 954 chaves de `@TempDir`** vazadas pela suíte. É a spec
  `isolate-tests-from-real-infra.md`, e a evidência ainda serve.
- **Não mexa nos valores dos limiares.** Mudar 0.45 ou 0.72 aqui torna impossível saber se uma
  mudança de qualidade veio desta tarefa ou da calibragem.
- **Não "resolva" apagando manifesto no boot.** Isso força reindex total sempre e joga fora o
  incremental que funciona.
- **Não instale nem baixe modelo.** Máquina de 16 GB com outros projetos rodando.
- **Não anote `@Disabled` nem afrouxe assert.** Se um teste que passava quebrar, **pare e reporte** —
  esta tarefa apaga código, e teste que quebra ao apagar é informação, não obstáculo.
- **Não commite.** Nem `src/main/resources/agent/policies/`.

---

## 8. Validação

- suíte verde, com a contagem explicada
- `grep -r VectorStoreResolver back/avento --include="*.java"` volta **vazio**
- `projectKey` inclui o nome do índice, com teste
- os dois YAMLs usam `index-name`
- os limiares 0.45 e 0.72 intactos

---

## 9. Entrega

Conventional commits, **em inglês**:

```
refactor(rag): commit to a single embedding model and drop the switching machinery
fix(rag): read the index name from the property Spring AI actually uses
fix(rag): put the index name in the project key so a model change reindexes
```

No relato:

- a contagem de testes e o que sumiu por remoção
- se algo ainda exigia o `RedisClient` (T2)
- se "avento-rag sem Redis" se mostrou modo suportado, contra a minha suposição da seção 1
