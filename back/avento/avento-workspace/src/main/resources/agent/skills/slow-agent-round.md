# Diagnostica rodada do agente lenta demais
Gatilhos: avento esta lento, avento está lento, demora para responder, resposta demorando muito, rodada lenta, esta muito devagar, está muito devagar

Separe avaliacao de prompt de geracao antes de mexer em qualquer coisa:

1. No log do backend, leia `Agent round N finished in Xms: contentChars=Y`. Muito tempo com `contentChars` baixo e custo de prompt, nao de geracao.
2. No log do Ollama, compare `prompt eval time` com `eval time`. Prompt alto e prefixo nao cacheado ou contexto grande demais.
3. Se `prompt eval` repete o custo cheio a cada mensagem, o prefixo do prompt esta instavel. Rode `PromptPrefixStabilityTest`: nada volatil pode entrar no comeco do prompt.
4. Confira `avento.agent.num-ctx`. Numa maquina de 16 GB, acima de 8192 o modelo disputa memoria com Postgres, Redis e ComfyUI.
5. Meça a pressao de memoria: `vm_stat` e `sysctl vm.swapusage`. Com swap em uso, o gargalo e paginacao, e liberar RAM acelera mais que qualquer ajuste de prompt.
6. Conte as ferramentas da rodada em `Agent round toolset:`. O custo de `prompt_eval` cresce com o numero de schemas.
7. Considere o modelo: um executor menor responde em segundos onde um modelo maior leva dezenas.

Um benchmark isolado esconde problema de cache de prompt, porque manda sempre o mesmo texto e sempre acerta.
