# Especificações de tarefas arquivadas

Esta pasta guarda briefs, investigações e critérios de aceite usados durante mudanças anteriores.
Ela preserva contexto técnico e decisões tomadas naquele momento, mas não descreve necessariamente
o comportamento atual do Avento.

Use estes arquivos para entender **por que** uma mudança foi proposta ou executada. Para saber
**como o sistema funciona hoje**, consulte primeiro os documentos canônicos:

- `../../README.pt-BR.md` ou `../../README.md`;
- `../ARCHITECTURE.md`;
- `../FEATURES.pt-BR.md` ou `../FEATURES.md`;
- `../REDIS_EXECUTION.md`, para jobs, contexto e Streams;
- o código e os testes do módulo correspondente.

Em especial, specs que discutem troca de modelo de embedding, índices por perfil ou
`bge-m3` são históricas. O estado atual usa exclusivamente `nomic-embed-text` e o índice Redis
`avento_index_nomic_embed_text`.
