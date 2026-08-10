# Fixar o runtime de teste antes que o JDK o quebre sozinho

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde esta spec e o padrão do repo divergirem, o padrão vence e
você reporta.

**Tudo marcado como medido foi verificado nesta máquina em 10/08/2026.** O que é suposição está
marcado como tal — e onde estiver, meça antes de aplicar.

---

## 1. O problema

A suíte passa hoje **por coincidência de ambiente, não por decisão registrada**. Medido:

| | |
|---|---|
| `java` no PATH | **21.0.9** |
| Java que o Maven de fato usa | **25.0.1** (`/opt/homebrew/Cellar/openjdk/25.0.1`) |
| `JAVA_HOME` | **vazio** |
| `<java.version>` no `pom.xml` | 21 |
| `maven.compiler.release` efetivo | **21** ✅ |

O bytecode está certo — o `release=21` garante isso independente do JDK que roda o Maven. **O
problema não é compilação, é o runtime dos testes.**

### O aviso que é uma conta a pagar

Saída literal da suíte hoje:

```
Mockito is currently self-attaching to enable the inline-mock-maker.
This will no longer work in future releases of the JDK.
Please add Mockito as an agent to your build ...

WARNING: A Java agent has been loaded dynamically (byte-buddy-agent-1.18.10.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading
```

Hoje é aviso. O texto diz, com todas as letras, que **vai deixar de funcionar**. Quando deixar, a
suíte inteira cai de uma vez, numa atualização de JDK que ninguém associou à causa.

**Já aconteceu uma vez, em ambiente mais restrito:** ao rodar a suíte dentro do sandbox do Codex, o
`ApiExceptionHandlerSseTest` falhou com erro de anexação de agente — não de asserção. Fora do
sandbox o mesmo teste passa. Ou seja, o defeito já existe; só ainda não é fatal aqui.

### Por que isso importa mais que um aviso feio

É a mesma família dos defeitos que este projeto passou a semana caçando: **algo funciona por acidente
do ambiente e não por decisão escrita** — como a chave `index` em vez de `index-name`, e o `num_ctx`
descartado na tradução. O padrão é sempre o mesmo: funciona até o dia em que não funciona, e a causa
está longe do sintoma.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 809 testes, 0 falhas** fora do sandbox
  (`mvn clean test -Dtest='!DockerMcpGatewayLiveTest'`). Esta tarefa não conserta teste; ela impede
  que eles caiam todos juntos depois.
- **A compilação está portável.** `maven.compiler.release=21` já vem do `spring-boot-starter-parent`
  via `<java.version>`. **Não mexa nisso.**
- O aviso do Lombok sobre `sun.misc.Unsafe` **não é seu para consertar** — é do Lombok, e sai quando
  eles atualizarem. Não tente silenciá-lo.

---

## 3. Fatos que restringem a solução

**Leia antes — duas das saídas óbvias trocam o problema por um pior.**

### 3.1. Não silencie o aviso, resolva a causa

A tentação é acrescentar `-XX:+EnableDynamicAgentLoading` ao `argLine` e ver o aviso sumir. **Isso
esconde o problema em vez de resolvê-lo:** a flag só cala o aviso do JDK sobre carregamento dinâmico;
o Mockito continua se auto-anexando, e quando o JDK proibir de vez, a suíte cai igual — só que sem
aviso prévio, porque você o silenciou.

O caminho certo é o que a própria mensagem indica: **declarar o Mockito como agente** no `argLine` do
surefire, com `-javaagent:`. Aí não há auto-anexação para proibir.

Documentação: <https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3>

### 3.2. Não fixe o JDK por `JAVA_HOME` no repositório

Fixar caminho de JDK em arquivo versionado quebra na máquina de qualquer outra pessoa — o caminho do
Homebrew deste Mac não existe em lugar nenhum além dele.

O que se pode versionar é uma **exigência**, não um caminho: o `maven-enforcer-plugin` com
`requireJavaVersion` faz o build **falhar dizendo o que está errado** em vez de rodar em JDK
inesperado e falhar longe da causa.

**Suposição minha, meça antes:** não verifiquei se o `enforcer` já está no build. Se estiver,
acrescente a regra ao que existe em vez de declarar o plugin de novo.

### 3.3. O `argLine` do surefire tem uma armadilha conhecida

Se outro plugin (JaCoCo, por exemplo) já define `argLine`, sobrescrevê-lo desliga o outro em
silêncio. **Suposição minha, meça antes:** não verifiquei se há JaCoCo neste build. Se houver, o
`argLine` precisa preservar `@{argLine}` em vez de substituí-lo.

---

## 4. Decisões de projeto — não renegociar

1. **Resolver a causa, não o aviso.** Mockito declarado como agente; nada de silenciar com flag.
2. **Nada de caminho absoluto de JDK versionado.** Exigência sim, caminho não.
3. **A compilação não muda.** `release=21` fica como está.
4. **Um commit por mudança.** São duas coisas independentes (agente do Mockito; exigência de JDK) e
   uma pode dar errado sem a outra.

---

## 5. Tarefas, em ordem

### T1 — Medir o terreno antes de tocar

Reporte, sem alterar nada:

- se o `maven-enforcer-plugin` já existe no build (e onde)
- se algum plugin já define `argLine` no surefire (JaCoCo ou outro)
- a versão exata do `mockito-core` que o `spring-boot-dependencies` está gerenciando

Isso decide a forma das duas tarefas seguintes. **Reporte antes de continuar.**

### T2 — Declarar o Mockito como agente

Configure o surefire para carregar o Mockito via `-javaagent:` em vez de deixá-lo se auto-anexar.

O caminho do jar não pode ser escrito à mão: use o `maven-dependency-plugin` (goal `properties`) para
expor o artefato como propriedade e referencie-a no `argLine`.

Se a T1 mostrou que já existe `argLine`, **preserve o valor anterior** (ver 3.3).

**Critério de aceite:** a linha `Mockito is currently self-attaching` **desaparece** da saída, e a
suíte continua com 809 testes e 0 falhas.

Commit próprio.

### T3 — Exigir o JDK explicitamente

Acrescente a regra `requireJavaVersion` ao `maven-enforcer-plugin` — mínimo 21, e sem teto, a menos
que a T1 revele motivo para tê-lo.

**Critério de aceite:** rodar com um JDK abaixo de 21 falha com mensagem clara. Se você não tiver
como testar isso, **diga no relato** em vez de afirmar que funciona.

Commit próprio.

### T4 — Verificação final

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Relate a saída, incluindo os avisos que **sobraram** — o do Lombok deve continuar lá, e está certo
que continue.

---

## 6. Validação

Critério de aceite geral:

- **809 testes, 0 falhas, 0 erros**
- `Mockito is currently self-attaching` **não aparece mais**
- `WARNING: A Java agent has been loaded dynamically` **não aparece mais** (ele some como
  consequência de 3.1, não por silenciamento)
- o aviso do Lombok sobre `sun.misc.Unsafe` **continua** — não é seu

---

## 7. Fora de escopo — não faça

- **Não acrescente `-XX:+EnableDynamicAgentLoading`.** Ver 3.1: cala o aviso e mantém o defeito, que
  é a pior combinação possível — o dia em que quebrar, você não terá tido aviso.
- **Não versione `JAVA_HOME` nem caminho de JDK.** Ver 3.2.
- **Não altere `<java.version>` nem `maven.compiler.release`.** A compilação está correta.
- **Não tente silenciar o aviso do Lombok.** É deles.
- **Não anote `@Disabled` nem afrouxe assert** para fechar tarefa. Aqui isso seria especialmente
  perverso: a tarefa existe justamente para impedir uma quebra futura de testes.
- **Não atualize versão de dependência** "de passagem". Upgrade é outra tarefa, com outro risco.
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

Conventional commits, **em inglês**, um por tarefa:

```
build: load Mockito as an agent instead of letting it self-attach
build: require Java 21 explicitly instead of trusting whichever JDK Maven finds
```

No relato final:

- o que a T1 encontrou (enforcer? argLine? versão do Mockito?)
- os avisos que sobraram na saída
- se você conseguiu testar a regra do enforcer ou apenas a declarou
