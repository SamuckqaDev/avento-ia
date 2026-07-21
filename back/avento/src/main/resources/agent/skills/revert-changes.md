# Desfaz as alterações de arquivo da última resposta
Gatilhos: desfaz, desfazer, reverte, reverter, desfaz o que voce fez, reverte as alteracoes, reverte o que voce fez, volta o que voce mudou, desfaz as mudancas, desfaz a alteracao, undo

Quando o usuário pedir para desfazer, reverter ou voltar o que você mudou nos arquivos, chame `revert_changes`.

- A ferramenta restaura os arquivos ao estado anterior às edições da última resposta que mexeu no projeto.
- Confirme ao usuário quantos arquivos foram restaurados (campo `filesRestored`).
- Se não houver o que desfazer (`reverted:false`), diga isso — não invente que reverteu.
- Chamar de novo desfaz a resposta anterior a essa. Só reverte de novo se o usuário pedir.
- Não use isto para "desfazer" uma mensagem de conversa — é só para alterações de arquivo.
