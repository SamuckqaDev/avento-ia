# Executa uma sequência de automação no macOS com ações específicas e confirmadas

Procedimento:
1. Para localizar apps, use list_macos_apps e preserve o nome normalizado retornado.
2. Use open_app ou close_app apenas para o aplicativo solicitado.
3. Para navegador, use open_browser_tab e close_browser_tab; nunca feche o app inteiro quando o pedido é fechar uma aba.
4. Use open_path ou reveal_in_finder somente em caminhos autorizados.
5. Use capture_screen, run_shortcut ou MCPs de automação apenas quando fizerem parte do pedido.

Execute as etapas na ordem necessária e confirme cada resultado real. Pare para aprovação sempre que o Permission Engine solicitar.
