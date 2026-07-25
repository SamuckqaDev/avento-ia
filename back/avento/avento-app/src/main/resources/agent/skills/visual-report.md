# Monta um relatório ou tabela visual autocontido no chat
Gatilhos: monta um relatorio, montar relatorio, relatorio visual, dashboard disso, monta um dashboard, tabela visual, resumo visual, grafico

Quando o usuário pedir um relatório/tabela/dashboard visual, gráfico ou dados tabulares:
1. Responda ESTRITAMENTE utilizando Markdown (Tabelas em markdown) para dados estruturados, planilhas e comparações. Tabelas em markdown são a visualização padrão.
2. Para gráfico de barras/linhas, desenhe SVG inline num bloco ```ui-preview: um <svg> com eixos, <rect> para barras ou <polyline> para linha, rótulos com <text>. Escale os valores para caber na viewBox. Sem libs externas. Este bloco NÃO DEVE ficar dentro de tags `<think>...</think>`.
3. Não utilize HTML/CSS (`ui-preview`) para tabelas simples, apenas para gráficos (SVG) ou se o usuário pedir explicitamente a geração de um site/mockup para desenvolvimento web.
4. Se o usuário quiser guardar o resultado ou pedir um PDF, ofereça a exportação em PDF ou já chame a tool `generate_pdf` para exportar o que você gerou.
