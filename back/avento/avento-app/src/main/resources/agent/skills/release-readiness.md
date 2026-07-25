# Valida o Avento por completo antes de publicar uma versão
Gatilhos: validar release avento, preparar release avento, publicar versao avento, publicar versão avento

1. Defina o escopo pelos commits e identifique migrations e compatibilidade necessária.
2. Rode testes backend, lint/build frontend, validação de scripts, Docker e smoke autenticado.
3. Em estado local limpo, valide PostgreSQL, Redis, Ollama, ComfyUI, MCP, auth, uma resposta de chat e upload de documento.
4. Revise migrations, defaults, envs, portas, paths, retenção e instruções de upgrade.
5. Procure segredos, dados pessoais, modelos, mídias, caches e marca antiga em arquivos rastreados.
6. Confirme README e documentação HTML para arquitetura, setup, opcionais, troubleshooting e limites atuais.
7. Revise licenças e vulnerabilidades relevantes para distribuição pública.
8. Exija diff focado e commits semânticos. Não faça tag ou push sem aprovação explícita.

Falha de startup, auth, dados ou chat principal bloqueia a release.
