> ⚠️ **SUPERADA em 10/08/2026 por [`single-embedding-model.md`](single-embedding-model.md).**
> A decisão do dono mudou: um modelo de embedding só (`nomic-embed-text`), e a máquina de troca
> é apagada em vez de consertada. Este documento fica pelo histórico do diagnóstico.

# Fazer o índice por perfil de embedding realmente existir

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026, com o Redis de pé.** O que é suposição está marcado.

---

## 0. ⚠️ Atualização de 10/08, à noite — a v1 desta spec foi executada e NÃO consertou

O commit `67da45e` fez o **T1** (publicou `RedisVectorStoreClientConfiguration`) e o **T2**
(`RedisVectorStoreClientConfigurationTest`, com `ApplicationContextRunner`). Os dois passam.
**O defeito continua**, e agora está medido com a aplicação de pé, o que a v1 nunca teve.

### O que foi medido com a app rodando (10/08 ~21:50)

Reindex completo disparou no boot e terminou: **5.240 documentos**, ~31 docs/s, ~3 min.

```
FT._LIST  → avento_index          (só ele)
FT.INFO   → dim 768, num_docs 5240, prefixes avento:
```

E o banco diz que o modelo escolhido é outro:

```sql
select embedding_model, base_url from provider_settings;
--  bge-m3:latest | http://<tailnet-host>:11434
```

`bge-m3` tem **1024** dimensões. O índice nasceu com **768** — ou seja, o `nomic-embed-text` do YAML.
O nome que o próprio `VectorStoreResolverTest:26` espera, `avento_index_bge_m3_latest`, **não existe**.
O caminho de escrita passa pelo resolver (`RagService:230`), e o `EmbeddingProfile.isUsable()` só
checa nome e modelo não-nulos — **não testa alcançabilidade**, então o host offline não filtra o
perfil. Conclusão: `build()` caiu no fallback de novo.

### A causa real: `@ConditionalOnBean` fora de auto-configuração

**Medido, por leitura comparada do código de produção e do teste:**

`RedisVectorStoreClientConfiguration` é um `@Configuration` **component-scanned** com
`@ConditionalOnBean(JedisConnectionFactory.class)`. O `JedisConnectionFactory` vem do
`RedisAutoConfiguration`, e o Spring Boot processa auto-configuração **depois** de toda configuração
de usuário. Quando a condição é avaliada, o factory ainda não foi registrado → condição **false** →
**o bean nunca entra no contexto**. É a restrição documentada: `@ConditionalOnBean` só é confiável em
classe de auto-configuração.

**Por que o teste não pega:** o `ApplicationContextRunner` faz
`.withBean(JedisConnectionFactory.class, …)` **antes** do `@Import` da config. Nessa ordem a condição
vê o bean e passa. O teste prova a lógica sob uma ordem de registro que **produção não tem** — é a
seção 3.3 outra vez, uma camada mais fundo: não é mock, é ordenação.

### O que isso muda na spec

- **T1 e T2 estão feitos.** Não refaça. O trabalho agora é a **ordenação** e o **silêncio**.
- O fallback silencioso é o que escondeu tudo isso por dois dias. Ver a nova seção 3.5.
- O `activeIndexName()` promete no javadoc ser "for diagnostics and for the docs to be checkable",
  mas **nada em produção o expõe** — o único uso fora de teste é pedaço de chave de cache
  (`RagService:404`). Sem observabilidade, a próxima regressão também passa batida.

**Premissas minhas nesta atualização — o dono não confirmou, confira e reporte se divergir:**

1. O alvo real é o `bge-m3` na `dr` (`<tailnet-host>`, **offline há 1 dia** no Tailscale); o
   `nomic-embed-text` local é o fallback, não o padrão desejado.
2. Reindexar tudo a cada troca de modelo é aceitável, porque **medi hoje** que custa ~3 min.

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

### 3.5. O fallback silencioso é metade do defeito

Hoje, quando existe perfil configurado e o store dele não pode ser construído, o código escolhe
**servir o índice errado** e registrar um `warn`:

```java
logger.warn("No Redis client available; keeping the auto-configured vector store");
return autoConfiguredStore;
```

O resultado medido: **5.240 documentos embedados com o modelo errado**, sem nenhum sinal visível.
A busca respondia — respondia do índice que não corresponde ao modelo escolhido. Um defeito que
responde é mais caro que um que falha, porque ninguém vai olhar.

O mesmo vale para o `catch` em `activeProfile()`: ele degrada para o YAML sem dizer a quem escolheu o
modelo que a escolha foi ignorada.

**Não confunda com "estourar exceção em tudo".** Sem perfil configurado, o fallback está certo — é o
caminho normal. O que não pode é **perfil configurado + falha em construir** virar silêncio.

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
