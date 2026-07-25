# Analisa um projeto autorizado com evidências dos arquivos reais
Gatilhos: analisar projeto, analise projeto, revisar projeto, revise projeto, diagnostico projeto, diagnóstico projeto

Procedimento:
1. Use directory_tree na raiz autorizada com maxDepth entre 3 e 4.
2. Localize manifests, arquivos de configuração, entrypoints e testes com search_files.
3. Leia somente os arquivos relevantes com read_file. Nunca descreva arquivo que não foi lido.
4. Se o pedido exigir validação, execute apenas scripts já encontrados no projeto com terminal_run.
5. Responda com riscos reais em ordem de impacto, referências de arquivo e próximos passos práticos.

Não altere arquivos nesta skill. Se a pasta estiver vazia, diga isso claramente e encerre.
