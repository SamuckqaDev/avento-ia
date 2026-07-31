# Diagnostica modelo escolhido na tela que nao e o que roda
Gatilhos: troquei o modelo e nao mudou, troquei o modelo e não mudou, modelo errado rodando, seletor de modelo nao funciona, seletor de modelo não funciona, escolhi outro modelo mas continua o mesmo

A escolha do seletor chega ao backend e some no caminho:

1. Compare no log as duas linhas seguidas: `run starting for chat N with model X` e `Agent round 1 starting build for model Y`. X diferente de Y prova o descarte.
2. Confirme o que o front enviou: o corpo do `POST /api/ai/runs` traz `model`; string vazia significa "nao escolhi", qualquer nome significa escolha explicita.
3. Consulte `provider_settings`: um `cloud_model` gravado vence quando o pedido chega em branco.
4. Procure literal de modelo no codigo Java: `grep -rn '"[a-z0-9.]*:[0-9]*b"' --include='*.java'`. Um nome chumbado contradiz `avento.agent.default-model` e nunca deixa a configuracao devolver vazio.
5. Verifique se a conversa tem imagem: sem modelo de visao, a resolucao troca para `vision-model` de proposito.
6. Cubra a decisao em `ModelNames.chooseChatModel`, que e pura e nao exige subir o servico.

Escolher justamente o modelo padrao ja foi lido como ausencia de escolha; teste esse caso.
