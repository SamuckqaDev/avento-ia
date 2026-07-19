# Inspeciona e executa um fluxo Git controlado no workspace atual
Gatilhos: status git, revisar git, revise git, preparar commit, fazer commit, criar commit, ver diff git

Procedimento:
1. Use terminal_run com git status, git diff ou git log --oneline para inspeção rápida.
2. Para stage, commit, branch ou operações além da allowlist local, use list_mcp_servers e conecte git com o workspace autorizado.
3. Revise o diff real antes de preparar qualquer commit e não inclua arquivos alheios ao pedido.
4. Gere uma mensagem semântica curta baseada nas mudanças verificadas.
5. Só anuncie hash, branch ou push quando a ferramenta retornar confirmação real.

Nunca descarte alterações locais e nunca use operações destrutivas sem pedido explícito e aprovação.
