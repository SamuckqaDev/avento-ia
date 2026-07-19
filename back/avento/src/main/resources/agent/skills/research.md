# Pesquisa na internet e sintetiza o resultado de forma visual

Gatilhos: pesquisa na internet, pesquise sobre, busca online, buscar na internet, procura na web, pesquisa web, pesquisar online, pesquise na web
Ferramentas: fetch, web_url_read, browser_navigate, browser_snapshot
MaxRodadas: 12

Procedimento para pesquisar e apresentar de forma organizada:
1. Faça UMA busca inicial com `browser_navigate` (ou `fetch` para uma URL direta). Não repita a mesma busca em loop.
2. Abra os 2 a 4 melhores resultados e extraia o conteúdo com `browser_snapshot`, `web_url_read` ou `fetch`.
3. Sintetize o que encontrou: use uma tabela Markdown quando for comparação ou lista; use um bloco `ui-preview` (ver a skill `visual-report`) quando for um relatório maior.
4. Cite as fontes ao final (título + URL de cada página realmente aberta).
5. Ofereça exportar o resultado em PDF com a ferramenta `generate_pdf` se o usuário quiser guardar.
Não invente dados: use somente o que foi realmente encontrado nas páginas abertas.
