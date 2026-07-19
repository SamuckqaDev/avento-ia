# Mantém ingestão, vetores e recuperação de conhecimento do Avento
Gatilhos: corrigir rag avento, indexar documento conhecimento, busca vetorial avento, ia alucina documento

1. Confirme existência, autorização, leitura e conversão do arquivo antes de indexar.
2. Preserve headings, páginas, origem e identidade estável ao normalizar texto.
3. Divida por limites semânticos com overlap controlado; descarte chunks vazios e duplicados.
4. Gere embeddings com o modelo configurado e grave vetores reconstruíveis com ownership no Redis.
5. Filtre por usuário, chat, workspace e documento antes do ranking.
6. Ajuste top-K e similaridade com perguntas reais; resultado vazio não pode virar contexto inventado.
7. Exija resposta apoiada nos trechos e origem quando RAG for usado.
8. Teste ingestão, reingestão, no-match, isolamento entre usuários e exclusão com chat/documento.

Documento original e metadados duráveis ficam sob responsabilidade do backend.
