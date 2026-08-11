# CONSULTA DE PROJETO — não implemente nada

> **Isto não é spec de execução.** Modo somente leitura. Quero a sua **proposta**, escrita, para eu
> comparar com a minha antes de decidir. Não escreva código, não edite arquivo, não rode teste.

📖 **O `AGENTS.md` da raiz manda.**

Leia estes dois documentos antes de responder:

- `docs/agent-tasks/revive-vector-store-resolver.md` — em especial a **seção 0**, que é a atualização
  de hoje à noite, e a **3.5**
- `docs/agent-tasks/reindex-on-embedding-model-switch.md` — em especial a **seção 5**, as perguntas

E estes arquivos, que são o objeto:

- `back/avento/avento-rag/src/main/java/com/avento/service/rag/VectorStoreResolver.java`
- `back/avento/avento-rag/src/main/java/com/avento/service/rag/RedisVectorStoreClientConfiguration.java`
- `back/avento/avento-rag/src/test/java/com/avento/service/rag/RedisVectorStoreClientConfigurationTest.java`
- `back/avento/avento-rag/src/main/java/com/avento/service/rag/RagService.java` (linhas 170-215, 271-300, 414-440)
- `back/avento/avento-rag/src/main/java/com/avento/service/rag/WorkspaceIndexingService.java`

---

## O contexto medido

Com a aplicação de pé em 10/08 às ~21:50, o reindex completo rodou: **5.240 documentos**, ~31 docs/s.
O índice resultante é `avento_index`, **dim 768** — `nomic-embed-text`, o do YAML. Mas o
`provider_settings` diz `embedding_model = bge-m3:latest`, que tem **1024**. O índice
`avento_index_bge_m3_latest` que o `VectorStoreResolverTest:26` espera **não existe**.

O caminho de escrita passa pelo resolver (`RagService:230`), e o `isUsable()` não testa
alcançabilidade — então o host offline não filtra o perfil. O `build()` caiu no fallback.

---

## O que eu já concluí — quero que você confirme ou derrube

**Minha hipótese sobre a causa:** `RedisVectorStoreClientConfiguration` é `@Configuration`
component-scanned com `@ConditionalOnBean(JedisConnectionFactory.class)`. O factory vem do
`RedisAutoConfiguration`, processada **depois** da configuração de usuário, então a condição avalia
`false` e o bean nunca é registrado. O teste passa porque o `ApplicationContextRunner` faz
`.withBean(JedisConnectionFactory.class, …)` antes do `@Import`, criando uma ordem que produção não
tem.

**Não confirmei isso com log da aplicação** — é leitura comparada de código. Se você discordar, ou se
houver uma segunda causa possível (por exemplo o `catch` de `activeProfile()` engolindo exceção ao
construir o `OllamaEmbeddingModel` contra host offline), diga qual e como distinguir uma da outra sem
subir a aplicação de novo.

---

## O que eu quero de volta

**1. A causa.** Confirma minha hipótese, derruba, ou aponta uma segunda? Como se distingue?

**2. O conserto da ordenação.** Vejo três saídas e não decidi:
   - `@AutoConfiguration(after = RedisAutoConfiguration.class)` registrada no
     `AutoConfiguration.imports`
   - tirar o `@ConditionalOnBean` e deixar só o `@ConditionalOnClass`, já que parâmetro de `@Bean` é
     resolvido na criação, depois de todo registro
   - `ObjectProvider<JedisConnectionFactory>` e decidir em runtime

   Qual você escolhe e **por quê**? E qual teste prova o conserto **na ordem de produção** — porque o
   que existe hoje prova na ordem errada, e esse é o ponto todo.

**3. As três perguntas da seção 5** da outra spec: modelo no `projectKey` ou no `contentHash`;
o que fazer com as 5.240 chaves já sob `avento:` se o prefixo passar a ser por perfil; e onde mora o
disparo do reindex na troca de modelo.

**4. Onde eu estou errado.** Se alguma coisa nas duas specs estiver factualmente errada, ou se algum
"defeito" que eu descrevi não for defeito, diga. Prefiro descobrir agora.

**5. O que você mediria** antes de implementar, e o que dá para provar sem subir a aplicação.

---

## Regras

- **Não implemente.** Proposta escrita, com o raciocínio. Se quiser mostrar forma, trecho curto
  ilustrativo no relato — não em arquivo.
- **Somente leitura**: não rode build nem teste, o sandbox recusa a escrita em `target/`.
- Se discordar de uma "decisão do dono" registrada nas specs, **diga** — elas foram tomadas com menos
  informação do que temos agora.
- Seja específico com `arquivo:linha`.
