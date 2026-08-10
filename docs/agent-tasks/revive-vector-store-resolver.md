# Fazer o índice por perfil de embedding realmente existir

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026, com o Redis de pé.** O que é suposição está marcado.

---

## 1. O problema

`VectorStoreResolver` existe para dar **um índice por modelo de embedding** —
`avento_index_<perfil>` — e nunca funcionou. Nasceu em `827b7b7` (08/08) já inerte.

```java
RedisClient jedis = jedisProvider.getIfAvailable();
if (jedis == null) {
    logger.warn("No Redis client available; keeping the auto-configured vector store");
    return autoConfiguredStore;      // <- cai aqui SEMPRE
}
```

**Medido:** a autoconfiguração do Spring AI constrói o cliente Jedis num método **privado**
(`RedisVectorStoreAutoConfiguration.jedisClient(JedisConnectionFactory)`, verificado com `javap` no
jar 2.0.0). **Não existe bean desse tipo no contexto**, então `getIfAvailable()` devolve sempre
`null`.

Verificado também no **1.1.8**: lá o método privado é `jedisPooled(...)`. Ou seja, **nunca houve bean
nenhum** — a migração não quebrou isto, só herdou.

### A prova está no Redis, não no código

```
docker exec avento-redis-stack redis-cli FT._LIST
→ avento_index
```

Se o resolver funcionasse, existiria `avento_index_<sufixo>`. Existe **um índice só**, o do
autoconfigure, com 5.037 documentos e `dim 768`.

### Por que isso importa agora

O modelo atual é `nomic-embed-text` (**768 dimensões**). O `HANDOFF.md` registra a intenção de medir
o **`bge-m3`**, que tem **1024**.

Com o resolver inerte, trocar o modelo aponta os dois para o mesmo `avento_index`. **O Redis recusa**
— o schema fixa `dim 768`. Não é degradação: é parada.

### Por que consertar e não remover

A alternativa honesta era apagar `VectorStoreResolver`, `EmbeddingProfile`, `EmbeddingProfileSource` e
`ProviderEmbeddingProfileSource`, e viver com um índice só. **Decisão do dono: consertar**, porque o
`bge-m3` está no caminho e a tela de provedores já deixa trocar o modelo de embedding.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 810 testes, 0 falhas.**
- **A busca vetorial funciona hoje** — 5.037 documentos indexados, `avento_index` respondendo. Esta
  tarefa não muda o caminho feliz do modelo atual.
- **`VectorStoreResolverTest` existe** e passa. Ele mocka o provider, e é por isso que nunca pegou o
  defeito: o mock devolve um cliente que a aplicação real nunca tem.
- `RagService`, `CodeSearchService` e `WorkspaceDocumentRetriever` estão corretos. **Não encoste.**

---

## 3. Fatos que restringem a solução

**Leia antes — duas das saídas óbvias trocam o defeito por um pior.**

### 3.1. O conserto é derivar do `JedisConnectionFactory`, que É bean

Medido:

- `JedisConnectionFactory` vem do `spring-boot-starter-data-redis`, já declarado em `avento-app` e
  `avento-execution`, e expõe `getHostName()`, `getPort()`, `getPassword()`, `getDatabase()`,
  `isUseSsl()`.
- `redis.clients.jedis.RedisClient` (Jedis 7.4.1) tem
  `create(String host, int port)` e `create(String host, int port, String user, String password)`.

**É exatamente o que a autoconfiguração faz em privado.** Publique um bean equivalente — num
`@Configuration` do `avento-rag` ou do `avento-app` — e o `VectorStoreResolver` passa a encontrá-lo
sem mudar uma linha da lógica dele.

**Suposição minha, confira:** não verifiquei se a senha/SSL estão em uso nesta instalação. Leia do
factory em vez de assumir vazio — se `getPassword()` vier preenchido e você ignorar, o cliente
conecta em desenvolvimento e falha onde tiver senha.

### 3.2. Não desligue a autoconfiguração

A tentação é `@ConditionalOnMissingBean` ou excluir a `RedisVectorStoreAutoConfiguration` para
"assumir o controle". **Não.** O `autoConfiguredStore` é o fallback legítimo do resolver quando não há
perfil resolvido, e o `RagService` depende dele. Publique um bean **a mais**, não um a menos.

### 3.3. O teste que existe não prova nada sobre este defeito

`VectorStoreResolverTest` mocka o `ObjectProvider` e devolve um cliente. Isso testa a lógica **dado
que o bean existe** — e o bean nunca existiu. É o defeito clássico do mock: valida a intenção, não a
realidade.

**Por isso esta tarefa exige um teste que suba o contexto Spring** e verifique que o bean está lá.
Sem ele, o conserto pode voltar a apodrecer em silêncio do mesmo jeito.

### 3.4. Índice novo nasce vazio

Ao passar a resolver `avento_index_<perfil>`, o índice do perfil atual **não existe ainda** — os 5.037
documentos estão em `avento_index`. A primeira busca depois do conserto volta vazia até reindexar.

Isso é esperado e **não é regressão**, mas precisa estar no relato para ninguém interpretar como
quebra. **Não migre dados** nesta tarefa: reindexar é operação do usuário, não do refactor.

---

## 4. Decisões de projeto — não renegociar

1. **Publicar bean de `RedisClient`** derivado do `JedisConnectionFactory` (3.1).
2. **A lógica do `VectorStoreResolver` não muda.** Se você editou o `build()`, saiu do escopo.
3. **A autoconfiguração continua** (3.2).
4. **Teste com contexto Spring** provando que o bean existe (3.3).
5. **Sem migração de dados** (3.4).

---

## 5. Tarefas, em ordem

### T1 — Publicar o bean

`@Configuration` novo, no `avento-rag`, com um `@Bean RedisClient` construído a partir do
`JedisConnectionFactory` — host, porta, e credenciais **lidas do factory**, não fixadas.

Use `@ConditionalOnClass` / `@ConditionalOnBean(JedisConnectionFactory.class)` para não quebrar
contexto onde não há Redis.

### T2 — O teste que o mock não faz

Um teste que **suba o contexto** e afirme que existe bean de `RedisClient` e que
`VectorStoreResolver` devolve um store **diferente** do `autoConfiguredStore` quando há perfil.

Se o projeto não tiver teste de contexto no `avento-rag`, diga no relato como resolveu — `@SpringBootTest`
com configuração mínima, ou `ApplicationContextRunner`. **`ApplicationContextRunner` é preferível**:
não exige Redis de pé para provar que o bean é publicado.

### T3 — Verificar contra o Redis real, e reportar

Com o Redis de pé, confirme:

```bash
docker exec avento-redis-stack redis-cli FT._LIST
```

Diga no relato **o que apareceu**. Se surgir `avento_index_<algo>`, o resolver está vivo. Se
continuar só `avento_index`, o conserto não pegou — **reporte, não force**.

### T4 — Suíte completa

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

---

## 6. Validação

- **≥810 testes, 0 falhas**
- existe bean de `RedisClient` no contexto, provado por teste
- `VectorStoreResolver.build()` **não foi editado**
- o relato diz o que o `FT._LIST` mostrou

---

## 7. Fora de escopo — não faça

- **Não edite a lógica do `VectorStoreResolver`.** O defeito é ausência de bean, não a lógica.
- **Não desligue nem exclua a autoconfiguração do Spring AI.** Ver 3.2.
- **Não migre os 5.037 documentos** para um índice novo. Ver 3.4: reindexar é decisão do usuário.
- **Não apague o `VectorStoreResolverTest`.** Ele testa a lógica e continua valendo; o que falta é
  outro teste, não a remoção deste.
- **Não fixe host/porta/senha no código.** Leia do `JedisConnectionFactory` — o valor certo já está
  configurado e duplicá-lo cria duas verdades.
- **Não anote `@Disabled` nem afrouxe assert.**
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

Conventional commits, **em inglês**:

```
fix(rag): publish the Redis client the vector store resolver needs
test(rag): prove the client bean exists, which a mock never could
```

No relato:

- o que o `FT._LIST` mostrou antes e depois
- se havia senha/SSL configurados e como você os leu
- confirmação de que o `build()` do resolver não foi tocado
