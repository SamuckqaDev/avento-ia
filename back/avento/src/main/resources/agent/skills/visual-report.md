# Monta um relatório ou tabela visual autocontido no chat
Gatilhos: monta um relatorio, montar relatorio, relatorio visual, dashboard disso, monta um dashboard, tabela visual, resumo visual

Quando o usuário pedir um relatório/tabela/dashboard visual, produza um único bloco ```ui-preview
com HTML autocontido:
- Todo o CSS inline dentro do próprio HTML. Nada de rede: sem fetch, sem CDN, sem <script src>, sem
  <img src="http...">.
- Estruture com seções, uma tabela estilizada e, quando houver números, um gráfico em SVG inline.
- Use uma paleta sóbria e legível. Não invente dados: use só o que o usuário forneceu ou o que você
  pesquisou nesta conversa.
- Se o dado for pequeno (poucas linhas), uma tabela Markdown já basta — não force ui-preview.
- Para gráfico de barras/linhas, desenhe SVG inline: um <svg> com eixos, <rect> para barras ou
  <polyline> para linha, rótulos com <text>. Escale os valores para caber na viewBox. Sem libs.
