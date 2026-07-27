# Mantém o frontend React responsivo, moderno e isolado por conversa
Gatilhos: corrigir frontend avento, ajustar front avento, header responsivo avento, chat responsivo avento

1. Inspecione componente, styled-component, hook, contrato da API e comportamento responsivo atuais.
2. Deixe estado remoto e transporte em hooks/services; componentes cuidam de renderização e interação.
3. Use o cliente Axios compartilhado nas APIs comuns; use fetch apenas quando o streaming do navegador exigir.
4. Isole thinking, geração, aprovação, mídia e erro por `chatId` e `runId`.
5. Use flex/grid, dimensões estáveis, overflow e popovers limitados ao viewport.
6. Preserve ícones, tokens, teclado, labels, foco, contraste e movimento reduzido.
7. Mostre estados loading, vazio, erro, offline, cancelado e concluído; nenhum erro terminal pode deixar spinner infinito.
8. Rode TypeScript/build e valide desktop e tela estreita quando o layout mudar.

Não esconda erro de contrato do backend apenas zerando estado visual.
