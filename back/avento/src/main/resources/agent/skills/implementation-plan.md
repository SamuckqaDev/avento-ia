# Plano de implementação antes de codar
Gatilhos: plano de implementacao, faz um plano, faca um plano, planeja a implementacao, planeje a implementacao, monta um plano, antes de codar, como voce implementaria, plano antes de implementar, plano de implementacao de, planeja antes
Ferramentas: directory_tree, read_file, read_document, search_files

Você está em MODO PLANO. Só pode LER e explorar o código — NÃO edite, NÃO crie, NÃO apague, NÃO rode nada. As ferramentas de escrita e terminal estão indisponíveis de propósito nesta etapa.

Antes de propor, PENSE e explore o que for necessário (`directory_tree`, `read_file`, `search_files`) para o plano citar arquivos, métodos e nomes REAIS que você confirmou no repositório — nada inventado.

Depois, produza UM único bloco `impl-plan` como o CONTEÚDO PRINCIPAL DA RESPOSTA — solto no nível raiz do Markdown. NUNCA coloque este bloco dentro de tags `<think>...</think>` nem no seu raciocínio: o raciocínio/exploração fica no thinking, mas o bloco `impl-plan` TEM que sair na resposta visível, senão o card de aprovação não aparece para o usuário. Estrutura exata:

```impl-plan
## Objetivo
<o que a mudança entrega, em uma frase>

## Arquivos afetados
- `caminho/real/confirmado.ext` — o que muda aqui
- ...

## Passos
1. <passo na ordem de execução>
2. ...

## Riscos
- <o que pode quebrar / pontos de atenção>

## Como verificar
- <comando que prova que funcionou, ex.: verify_project no caminho do projeto>
```

Regras:
- Fora do bloco, escreva no máximo uma frase curta e pergunte se pode executar.
- NÃO comece a implementar nesta resposta. Espere o usuário aprovar.
- Quando o usuário aprovar ("aprovado", "pode fazer", "executa"), aí sim implemente seguindo o plano passo a passo e rode `verify_project` ao final, corrigindo até passar.
- Se faltar informação essencial para planejar, pergunte antes — não invente requisito.
