> ⚠️ **SUPERADA em 10/08/2026 por [`single-embedding-model.md`](single-embedding-model.md).**
> A decisão do dono mudou: um modelo de embedding só (`nomic-embed-text`), e a máquina de troca
> é apagada em vez de consertada. Este documento fica pelo histórico do diagnóstico.

# Fazer a troca de modelo de embedding reindexar de verdade

> **Estado: PROBLEMA LEVANTADO, SOLUÇÃO EM CONSULTA.** Não execute ainda.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido em 10/08/2026 com Redis e aplicação de pé.** O que é suposição está marcado.

Irmã de [`revive-vector-store-resolver.md`](revive-vector-store-resolver.md). Aquela faz o resolver
**rodar**; esta faz os **dados estarem certos** quando ele roda. São separáveis: os defeitos abaixo
existem independentemente, mas só ficam observáveis depois que o resolver sai do fallback.

---

## 1. O defeito

Trocar `provider_settings.embedding_model` **não reindexa**. E é pior que não reindexar: cria um
índice novo vazio enquanto o manifesto jura que está tudo indexado.

O rastro, todo verificado no código:

| Passo | O que acontece |
|---|---|
| `VectorStoreResolver.active()` | Monta store novo → `avento_index_<perfil>`, vazio (`initializeSchema(true)`) |
| `RagService:174` lê o manifesto | `projectKey = sha256(caminho absoluto)` (`RagService:422`) — **sem o modelo** |
| `RagService:183` compara | `contentHash = sha256(strategy + ":" + content)` (`RagService:435`) — **sem o modelo** |
| Resultado | Hash bate em todos os arquivos → `continue` → `documentsToAdd` vazio |
| Log | `N arquivos lidos, 0 chunks atualizados, 0 removidos` |
| Busca | Índice novo vazio → **zero resultado, em silêncio** |

**Isto é o mesmo defeito que o projeto já consertou uma vez.** O javadoc em `RagService:426`
documenta o sintoma idêntico para o chunker, com o log real: *"94 arquivos lidos, 0 chunks
atualizados"*. O conserto colocou a **estratégia de corte** no hash e **não** o modelo de embedding.
Mesma armadilha, uma variável ao lado.

### 1.1. E nada dispara

O reindex só acorda em `WorkspaceRootRegisteredEvent` (`WorkspaceIndexingService:75`) ou mudança de
arquivo. Trocar o modelo na tela de provedores não notifica nada — o efeito aparece no próximo boot,
ou nunca.

### 1.2. O índice por perfil não isola nada

`build()` dá **nome** de índice por perfil, mas passa o **mesmo `prefix`** para todos —
`avento:`, de `VectorStoreResolver:46`. Confirmado no `FT.INFO`: `prefixes: avento:`. E o ID do chunk
também é agnóstico ao modelo (`RagService:298`):

```java
"avento-rag-" + sha256(projectKey + ":" + relativePath + ":" + file.fileHash() + ":" + index)
```

Índice no RediSearch é definido **sobre prefixo de chave**. Dois índices com o mesmo prefixo e os
mesmos IDs de documento leem **o mesmo keyspace** — um esperando 768, o outro 1024. O javadoc da
classe promete que *"switching cannot mix vectors of different widths"*; o nome do índice muda, a
chave dos dados não.

**NÃO VERIFICADO:** não criei um segundo índice para medir `hash_indexing_failures`. O mecanismo do
RediSearch é conhecido, o número é previsão. Provar isso é uma das tarefas.

---

## 2. O que NÃO está quebrado — não encoste

- A busca do modelo atual funciona: 5.240 documentos, `avento_index` respondendo.
- O `contentHash` levar a estratégia de corte está **certo** — é conserto anterior, mantenha.
- O `deleteChunks` por manifesto está correto para o caso de mesmo modelo.
- `CodeAwareSplitter`, `CodeSearchService`, `WorkspaceDocumentRetriever`: fora de escopo.

---

## 3. As quatro coisas que a solução precisa cobrir

1. **Modelo na identidade do índice** — no `projectKey` ou no `contentHash`, senão trocar o modelo
   continua não reindexando.
2. **Prefixo por perfil**, não só nome de índice — senão os dois índices disputam as mesmas chaves.
3. **Reagir à troca** — alguma coisa tem que disparar reindex quando o `embedding_model` muda.
4. **Falhar alto** em vez de servir índice vazio em silêncio (ver 3.5 da spec irmã).

---

## 4. Saídas baratas proibidas

- **Não "resolva" apagando o manifesto a cada boot.** Isso força reindex total sempre, joga fora o
  incremental que já funciona e transforma um boot de 3 min em rotina.
- **Não faça o `isUsable()` pingar o host.** Alcançabilidade não é propriedade do perfil, e um
  `select` na tela de provedores não pode depender de rede.
- **Não migre vetor de 768 para 1024.** Não existe conversão; tem que reembedar.
- **Não afrouxe assert nem `@Disabled`.**

---

## 5. Perguntas de projeto abertas — em consulta com o Codex

1. O modelo entra no `projectKey` (um manifesto por par projeto+modelo, mantém histórico de cada
   índice) ou no `contentHash` (um manifesto só, e a troca invalida tudo)? A primeira gasta mais
   Redis e permite voltar sem reembedar; a segunda é mais simples.
2. Prefixo por perfil resolve o isolamento — mas o que fazer com as **5.240 chaves** que já estão sob
   `avento:` sem sufixo? Migrar chave, ou aceitar reindex e apagar?
3. O disparo do reindex na troca: evento de domínio no `provider_settings`, ou o
   `WorkspaceIndexingService` compara o perfil ativo com o do manifesto e decide sozinho? A segunda
   é auto-corretiva no boot e não depende de quem escreveu a configuração lembrar de emitir evento.

---

## 6. Validação (quando a solução estiver definida)

- **≥810 testes, 0 falhas** — `cd back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'`
- Teste que prove: com o manifesto cheio e o modelo trocado, `documentsToAdd` **não** é vazio
- Teste que prove: dois perfis não compartilham keyspace
