# Provar que a mudança do nome do índice reindexa (o T5 que ficou faltando)

> Spec de execução. Escopo fechado: **só** o que está aqui. **Só testes** — não mude código de
> produção.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.

📖 **O `AGENTS.md` da raiz manda.**

**Estado da árvore:** suja, com **duas** tarefas anteriores não commitadas —
`single-embedding-model.md` (backend RAG + front) e `kill-localstorage.md` (avatar no banco +
cookies). As duas passaram na suíte. Isso é esperado, **não pare por causa disso**.

⚠️ **O `npm test` no seu sandbox pode falhar com `TypeError: localStorage.getItem is not a
function`** — é um `--localstorage-file` inválido no ambiente do sandbox, não do projeto. Medido: o
mesmo teste passa fora. **Não é regressão, não pare nela.** Esta tarefa é backend; se o front falhar
só com essa mensagem, registre e siga.

---

## 1. Por que esta tarefa existe

A tarefa `single-embedding-model.md` entregou T1–T4 e **não entregou o T5**. Verificado:
`grep -rn "indexName\|projectKey" --include="*Test.java"` volta **vazio**. O
`RagServiceBatchingTest` só foi ajustado para o construtor novo.

Isso deixa **sem prova** justamente a proteção central da mudança. E a proteção existe porque a
armadilha é real e já aconteceu neste projeto uma vez.

### A armadilha, medida

O javadoc em [`RagService.java:436`](../../back/avento/avento-rag/src/main/java/com/avento/service/rag/RagService.java:436) registra o caso
histórico, com o log de uma subida real: trocar o chunker **não reindexava nada** — o manifesto
comparava hash de arquivo, os arquivos não tinham mudado, e o índice seguia servindo chunks do método
antigo. O log dizia *"94 arquivos lidos, 0 chunks atualizados"*.

O mesmo mecanismo se aplica ao **nome do índice**: quando ele muda, o índice novo nasce vazio, e um
manifesto que não conheça o nome do índice faz **todos** os arquivos serem pulados. Resultado: busca
respondendo **zero, em silêncio**.

O conserto já está no código — `projectKey` inclui o nome do índice
([`RagService.java:431-433`](../../back/avento/avento-rag/src/main/java/com/avento/service/rag/RagService.java:431)):

```java
private String projectKey(Path root) {
    return sha256(root.toAbsolutePath().normalize() + ":" + indexName);
}
```

**Falta o teste que prova que isso funciona.** Sem ele, alguém "simplifica" o `projectKey` daqui a
seis meses e a suíte continua verde.

---

## 2. Os três testes

O construtor hoje é
`RagService(VectorStore, StringRedisTemplate, ObjectMapper, String indexName, double, int, int, int)`.
O `RagServiceBatchingTest` já mostra o padrão de montar um `RagService` com mock de Redis, e a
observação dele vale: *"A bare Redis mock is enough: every manifest and cache access in RagService
already degrades to 'no manifest' when Redis does not answer"*.

### T5.1 — O `projectKey` muda com o nome do índice

Duas instâncias, **mesma raiz de projeto**, nomes de índice diferentes, e provar que a chave de
manifesto usada é **diferente**. Como o `projectKey` é privado, prove pelo **efeito observável**: a
chave que o `StringRedisTemplate` recebe em `avento:rag:manifest:<...>`.

Nomes que **não** devem colidir: use pares realistas, por exemplo
`avento_index_nomic_embed_text` e `avento_index_bge_m3`.

### T5.2 — Manifesto cheio + nome novo = reindexa

**É o teste que importa.** Monte a situação exata da armadilha:

1. Um projeto com alguns arquivos
2. Um manifesto no Redis mockado, **cheio**, com os `contentHash` **corretos** para aqueles arquivos
   — gravado sob a chave do índice **antigo**
3. Indexe com o nome de índice **novo**

Afirme que o `VectorStore` **recebeu documentos** — que `documentsToAdd` não foi vazio. Se este teste
passar com a implementação atual e **falhar** ao remover o `indexName` do `projectKey`, ele está
provando a coisa certa.

**Confirme isso explicitamente**: inverta a implementação em memória enquanto desenvolve, veja o teste
ficar vermelho, e volte. Diga no relato que fez essa checagem. Um teste que passa nos dois casos não
serve para nada, e é assim que o teste anterior desta linha nasceu inútil.

### T5.3 — O contexto entrega o `VectorStore` autoconfigurado

Que o `RagService` receba o `VectorStore` do contexto, **sem** `.withBean(...)` plantando a dependência
numa ordem que produção não tem.

**É a lição do defeito que originou tudo isto.** O `RedisVectorStoreClientConfigurationTest`, agora
apagado, fazia `.withBean(JedisConnectionFactory.class, …)` **antes** do `@Import` da configuração, e
por isso passava enquanto produção falhava — o `@ConditionalOnBean` era avaliado numa ordem que só
existia no teste. **Não repita a forma.** Use `AutoConfigurations.of(...)` para que a
auto-configuração entre pelo caminho dela.

Se você concluir que este teste não é possível sem Redis vivo, **diga isso no relato com o motivo** em
vez de escrever uma versão que finge.

---

## 3. Fora de escopo — não faça

- **Não mude código de produção.** Se um teste honesto não passar, o achado é informação: **pare e
  reporte**, não ajuste a implementação para caber no teste.
- **Não toque no `contentHash`.** Ele responde "conteúdo ou corte mudaram?" e está certo.
- **Não mude os limiares** 0.45 e 0.72.
- **Não use `.withBean(...)` para plantar dependência que produção recebe por auto-configuração.** É
  exatamente o defeito que esta linha de trabalho existe para não repetir.
- **Não anote `@Disabled`, não afrouxe assert, não escreva teste que passe com a implementação
  invertida.**
- **Não mexa no front** nem no trabalho não commitado das outras duas tarefas.
- **Não commite.** Nem `src/main/resources/agent/policies/`.

---

## 4. Validação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

- suíte verde, com **três testes a mais** que antes
- `grep -rn "projectKey\|indexName" back/avento --include="*Test.java"` **não** volta vazio
- o relato diz que o T5.2 foi visto **falhando** com a implementação invertida

⚠️ **Sobre a suíte:** duas rodadas dela nesta máquina hoje **não** escreveram no Postgres nem no Redis
reais — `scheduled_tasks` seguiu vazia e as chaves de RAG ficaram em exatamente 954. A suposição é que
o seu sandbox não alcança os containers, e os testes degradam. **Se você notar sinal do contrário,
reporte** — muda o que sabemos sobre o isolamento dos testes.

---

## 5. Entrega

Conventional commit, **em inglês**:

```
test(rag): prove a new index name forces a reindex instead of trusting a stale manifest
```

No relato: como provou o T5.1 sem acessar método privado, a confirmação da inversão no T5.2, e se o
T5.3 foi possível sem Redis vivo.
