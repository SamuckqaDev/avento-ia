package com.avento.service.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Declara os schemas das ferramentas locais; a execução continua no {@link ToolProvider}. */
public final class LocalToolDefinitions {

    public static final List<String> NAMES = List.of(
            "directory_tree",
            "read_file",
            "read_document",
            "list_mcp_servers",
            "connect_mcp_server",
            "disconnect_mcp_server",
            "write_file",
            "edit_file",
            "delete_file",
            "delete_directory",
            "create_directory",
            "search_files",
            "find_symbol",
            "remember",
            "create_skill",
            "list_skills",
            "delete_skill",
            "create_vite_project",
            "list_macos_apps",
            "open_app",
            "close_app",
            "open_browser_tab",
            "close_browser_tab",
            "open_url",
            "open_path",
            "reveal_in_finder",
            "run_shortcut",
            "capture_screen",
            "generate_pdf",
            "generate_image",
            "generate_video",
            "terminal_run",
            "terminal_start",
            "terminal_list",
            "schedule_task",
            "terminal_logs",
            "terminal_stop",
            "verify_project",
            "revert_changes",
            "search_capabilities",
            "activate_tools",
            "search_code");

    private final ToolProvider tools;

    public LocalToolDefinitions(ToolProvider tools) {
        this.tools = tools;
    }

    @Tool(name = "directory_tree", description = "Lista a arvore de arquivos e pastas dentro de um workspace autorizado. Use antes de criar ou editar quando precisar entender a estrutura.")
    public String directoryTree(@ToolParam(required = false, description = "Profundidade maxima opcional, padrao 4.") Double maxDepth, @ToolParam(description = "Diretorio absoluto dentro de [Workspace Roots].") String path) throws Exception {
        return execute("directory_tree", "maxDepth", maxDepth, "path", path);
    }

    @Tool(name = "read_file", description = "Le o conteudo de um arquivo dentro de um workspace autorizado.")
    public String readFile(@ToolParam(description = "Caminho absoluto do arquivo autorizado.") String path) throws Exception {
        return execute("read_file", "path", path);
    }

    @Tool(name = "read_document", description = "Le documentos locais dentro de um workspace autorizado. Converte PDF, Word, Excel, PowerPoint, EPUB, ZIP, imagens com OCR, audio e formatos de texto para Markdown usando MarkItDown local.")
    public String readDocument(@ToolParam(description = "Caminho absoluto do documento dentro de um workspace autorizado.") String path) throws Exception {
        return execute("read_document", "path", path);
    }

    @Tool(name = "list_mcp_servers", description = "Lista servidores MCP locais disponiveis, configuracao ausente e estado da conexao. Use para descobrir capacidades antes de conectar.")
    public String listMcpServers(@ToolParam(required = false, description = "Workspaces absolutos opcionais para validar servidores de arquivos e Git.") List<String> projectPaths) throws Exception {
        return execute("list_mcp_servers", "projectPaths", projectPaths);
    }

    @Tool(name = "connect_mcp_server", description = "Conecta um servidor do catalogo sob demanda. IDs: filesystem, markitdown, memory, sequential-thinking, time, desktop-commander, macos-automator, apple, playwright, chrome-devtools, puppeteer, fetch, searxng, git, dbhub, docker-gateway.")
    public String connectMcpServer(@ToolParam(description = "ID exato do servidor no catalogo.") String serverId, @ToolParam(required = false, description = "Workspaces absolutos necessarios para filesystem e git.") List<String> projectPaths) throws Exception {
        return execute("connect_mcp_server", "serverId", serverId, "projectPaths", projectPaths);
    }

    @Tool(name = "disconnect_mcp_server", description = "Desconecta um servidor MCP do catalogo pelo ID.")
    public String disconnectMcpServer(@ToolParam(description = "ID exato do servidor conectado.") String serverId) throws Exception {
        return execute("disconnect_mcp_server", "serverId", serverId);
    }

    @Tool(name = "write_file", description = "Cria ou sobrescreve um arquivo dentro de um workspace autorizado. Cria diretorios pais quando necessario e gera backup antes de sobrescrever.")
    public String writeFile(@ToolParam(description = "Caminho absoluto do arquivo a criar ou sobrescrever.") String path, @ToolParam(description = "Conteudo completo que deve ser salvo no arquivo.") String content) throws Exception {
        return execute("write_file", "path", path, "content", content);
    }

    @Tool(name = "edit_file", description = "Substitui um trecho exato dentro de um arquivo existente, sem reescrever o arquivo inteiro. Prefira esta ferramenta a write_file quando o arquivo ja existe e a mudanca e pontual. old_string precisa aparecer exatamente uma vez no arquivo (inclua linhas de contexto ao redor para garantir isso), a menos que replace_all seja true. Gera backup antes de aplicar.")
    public String editFile(@ToolParam(description = "Caminho absoluto do arquivo existente a editar.") String path, @ToolParam(description = "Trecho exato do conteudo atual do arquivo a ser substituido, incluindo indentacao e contexto suficiente para ser unico.") String old_string, @ToolParam(description = "Trecho que deve substituir old_string. Pode ser vazio para apagar o trecho.") String new_string, @ToolParam(required = false, description = "Se true, substitui todas as ocorrencias de old_string em vez de exigir ocorrencia unica. Padrao false.") Boolean replace_all) throws Exception {
        return execute("edit_file", "path", path, "old_string", old_string, "new_string", new_string, "replace_all", replace_all);
    }

    @Tool(name = "delete_file", description = "Remove um arquivo dentro de um workspace autorizado. Gera backup antes de apagar e exige aprovacao do usuario.")
    public String deleteFile(@ToolParam(description = "Caminho absoluto do arquivo autorizado a remover.") String path) throws Exception {
        return execute("delete_file", "path", path);
    }

    @Tool(name = "delete_directory", description = "Remove uma pasta inteira e todo o seu conteudo dentro de um workspace autorizado. Gera backup antes de apagar quando a pasta tem ate 5000 arquivos; acima disso a exclusao roda sem backup (ex.: pastas com node_modules). Sempre exige aprovacao do usuario, mesmo com um plano ja aprovado. Recusa apagar a raiz de um workspace inteiro.")
    public String deleteDirectory(@ToolParam(description = "Caminho absoluto da pasta autorizada a remover, com todo o conteudo.") String path) throws Exception {
        return execute("delete_directory", "path", path);
    }

    @Tool(name = "create_directory", description = "Cria um diretorio dentro de um workspace autorizado, incluindo pais ausentes.")
    public String createDirectory(@ToolParam(description = "Caminho absoluto do diretorio a criar.") String path) throws Exception {
        return execute("create_directory", "path", path);
    }

    @Tool(name = "search_files", description = "Procura arquivos por nome dentro de um workspace autorizado, ignorando pastas pesadas como node_modules, .git, build e target.")
    public String searchFiles(@ToolParam(description = "Diretorio absoluto dentro de [Workspace Roots].") String path, @ToolParam(description = "Texto a procurar no nome do arquivo ou pasta.") String pattern, @ToolParam(required = false, description = "Quantidade maxima opcional de resultados, padrao 50.") Double maxResults) throws Exception {
        return execute("search_files", "path", path, "pattern", pattern, "maxResults", maxResults);
    }

    @Tool(name = "find_symbol", description = "Acha ONDE um simbolo e DEFINIDO no projeto (classe, interface, record, enum, funcao, metodo, type, const) — busca a definicao, nao toda mencao. Use para entender o codigo e navegar antes de editar. Retorna arquivo, linha e o texto da definicao.")
    public String findSymbol(@ToolParam(description = "Diretorio absoluto do projeto dentro de [Workspace Roots].") String path, @ToolParam(description = "Nome exato do simbolo a localizar (ex.: AgentService, generate).") String symbol) throws Exception {
        return execute("find_symbol", "path", path, "symbol", symbol);
    }

    @Tool(name = "remember", description = "Guarda na memoria de longo prazo um fato ou preferencia DURAVEL do usuario, para lembrar em conversas futuras (ex.: 'prefere styled-components', 'chama o projeto de monicare', 'gosta de respostas em PT-BR informal'). Use SO para coisas que valem alem desta conversa — nao use para pedidos pontuais nem para o que ja esta no historico. A memoria fica PENDENTE ate o usuario confirmar, entao nao anuncie que ja lembrou em definitivo.")
    public String remember(@ToolParam(required = false, description = "Rotulo opcional: preferencia, projeto, fato ou referencia. Padrao: fato.") String category, @ToolParam(description = "O fato/preferencia em uma frase curta e objetiva, na terceira pessoa (ex.: 'Prefere TypeScript a JavaScript').") String content) throws Exception {
        return execute("remember", "category", category, "content", content);
    }

    @Tool(name = "create_skill", description = "Cria uma SKILL nova e reutilizavel quando o usuario pede uma capacidade que ele quer repetir depois (ex.: 'cria uma skill de cotacao que busca no fetch e mostra em tabela'). A skill e um procedimento em texto que o Avento passa a seguir automaticamente sempre que a mensagem casar com um dos gatilhos — nao e codigo novo. Use quando o pedido for claramente 'crie/salve uma skill/capacidade/atalho'. Depois de criar, confirme ao usuario o nome e os gatilhos.")
    public String createSkill(@ToolParam(description = "Identificador em kebab-case, so minusculas/numeros/hifen (ex.: 'cotacao-moedas').") String name, @ToolParam(description = "Uma frase dizendo o que a skill faz.") String description, @ToolParam(description = "O procedimento que o Avento deve seguir quando a skill dispara, em passos claros (quais ferramentas usar e como formatar a resposta).") String instructions, @ToolParam(required = false, description = "Frases-gatilho que ativam a skill (ex.: ['cotacao', 'cotacao do dolar', 'preco do euro']).") List<String> triggers) throws Exception {
        return execute("create_skill", "name", name, "description", description, "instructions", instructions, "triggers", triggers);
    }

    @Tool(name = "list_skills", description = "Lista as skills disponiveis (embutidas e criadas pelo usuario), com nome, descricao e gatilhos.")
    public String listSkills() throws Exception {
        return execute("list_skills");
    }

    @Tool(name = "delete_skill", description = "Apaga uma skill CRIADA pelo usuario pelo nome. Skills embutidas do sistema nao podem ser apagadas.")
    public String deleteSkill(@ToolParam(description = "Nome exato da skill a apagar.") String name) throws Exception {
        return execute("delete_skill", "name", name);
    }

    @Tool(name = "create_vite_project", description = "Cria um projeto novo com Vite dentro de um workspace autorizado. Use quando o usuario pedir para criar projeto React/Vite, Vue/Vite etc. Para React com TypeScript, use template react-ts.")
    public String createViteProject(@ToolParam(required = false, description = "Se true, roda npm install apos criar o projeto. Padrao false para evitar travar maquinas lentas.") Boolean installDependencies, @ToolParam(description = "Diretorio absoluto autorizado onde o projeto sera criado. Use a raiz do workspace quando o usuario pedir para criar dentro dela.") String path, @ToolParam(description = "Nome da pasta do projeto. Use . apenas quando a pasta path ja for o diretorio vazio do projeto.") String projectName, @ToolParam(description = "Template Vite. Exemplos: react-ts, react, react-swc-ts, vue-ts, vanilla-ts.") String template) throws Exception {
        return execute("create_vite_project", "installDependencies", installDependencies, "path", path, "projectName", projectName, "template", template);
    }

    @Tool(name = "list_macos_apps", description = "Lista os aplicativos instalados no macOS em /Applications, ~/Applications e pastas de sistema. Use quando o usuario pedir todos os apps do Mac, lista de aplicativos, ou procurar um app instalado pelo nome.")
    public String listMacosApps(@ToolParam(required = false, description = "Texto opcional para filtrar por nome do aplicativo, por exemplo Antigravity.") String query) throws Exception {
        return execute("list_macos_apps", "query", query);
    }

    @Tool(name = "open_app", description = "Abre um aplicativo instalado no macOS pelo nome. Use para pedidos como abrir VS Code, Finder, Terminal, navegador ou outro app local.")
    public String openApp(@ToolParam(description = "Nome do aplicativo macOS, por exemplo Visual Studio Code, Finder, Terminal ou Safari.") String appName) throws Exception {
        return execute("open_app", "appName", appName);
    }

    @Tool(name = "close_app", description = "Fecha um aplicativo aberto no macOS pelo nome usando AppleScript. Use para pedidos como fechar VS Code, Finder, Terminal, Safari ou Chrome. Nao use terminal_stop para apps abertos por open_app.")
    public String closeApp(@ToolParam(description = "Nome do aplicativo macOS, por exemplo Visual Studio Code, Finder, Terminal ou Safari.") String appName) throws Exception {
        return execute("close_app", "appName", appName);
    }

    @Tool(name = "open_browser_tab", description = "Abre uma nova aba em um navegador macOS, como Brave Browser, Google Chrome ou Safari. Use para pedidos como nova aba no Brave ou abrir nova guia no navegador.")
    public String openBrowserTab(@ToolParam(description = "Nome do navegador macOS, por exemplo Brave Browser, Google Chrome ou Safari.") String browserName, @ToolParam(required = false, description = "URL http/https opcional para abrir na nova aba. Se ausente, abre aba em branco/pagina inicial.") String url) throws Exception {
        return execute("open_browser_tab", "browserName", browserName, "url", url);
    }

    @Tool(name = "close_browser_tab", description = "Fecha somente a aba ativa de um navegador macOS, sem encerrar o aplicativo. Use para pedidos como fechar aba, fechar guia ou fechar a aba da pesquisa. Nao use close_app para fechar abas.")
    public String closeBrowserTab(@ToolParam(description = "Nome do navegador macOS, por exemplo Brave Browser, Google Chrome ou Safari.") String browserName) throws Exception {
        return execute("close_browser_tab", "browserName", browserName);
    }

    @Tool(name = "open_url", description = "Abre uma URL http ou https no navegador padrao do sistema.")
    public String openUrl(@ToolParam(description = "URL absoluta começando com http:// ou https://.") String url) throws Exception {
        return execute("open_url", "url", url);
    }

    @Tool(name = "open_path", description = "Abre um arquivo ou pasta existente dentro de um workspace autorizado usando o app padrao do sistema.")
    public String openPath(@ToolParam(description = "Caminho absoluto existente dentro de um workspace autorizado.") String path) throws Exception {
        return execute("open_path", "path", path);
    }

    @Tool(name = "reveal_in_finder", description = "Mostra um arquivo ou pasta existente dentro de um workspace autorizado no Finder.")
    public String revealInFinder(@ToolParam(description = "Caminho absoluto existente dentro de um workspace autorizado.") String path) throws Exception {
        return execute("reveal_in_finder", "path", path);
    }

    @Tool(name = "run_shortcut", description = "Executa um atalho do app Shortcuts do macOS pelo nome. Use apenas quando o usuario pedir explicitamente um atalho existente.")
    public String runShortcut(@ToolParam(description = "Nome exato do atalho no app Shortcuts.") String shortcutName) throws Exception {
        return execute("run_shortcut", "shortcutName", shortcutName);
    }

    @Tool(name = "capture_screen", description = "Captura um screenshot da tela atual no macOS e salva em Pictures/Avento Screenshots. Use apenas quando o usuario pedir explicitamente para tirar print/screenshot da tela.")
    public String captureScreen() throws Exception {
        return execute("capture_screen");
    }

    @Tool(name = "generate_pdf", description = "Gera um documento PDF a partir de conteúdo Markdown ou HTML e salva na pasta de media. Use para relatorios e exportacoes de texto/tabela. NAO use para mockup, tela, wireframe ou prototipo de interface — esses vao para um bloco ui-preview, nunca PDF.")
    public String generatePdf(@ToolParam(required = false, description = "Conteúdo HTML direto se não usar markdown.") String html, @ToolParam(required = false, description = "Conteúdo em Markdown para converter.") String markdown, @ToolParam(description = "Título do documento PDF.") String title) throws Exception {
        return execute("generate_pdf", "html", html, "markdown", markdown, "title", title);
    }

    @Tool(name = "generate_image", description = "Gera uma imagem local usando o modelo de imagem selecionado no header. Pode usar ComfyUI ou Ollama e salva em Pictures/Avento Generated Images. Use quando o usuario pedir para criar/gerar uma imagem, arte, foto, ilustração, mockup visual ou algo parecido.")
    public String generateImage(@ToolParam(required = false, description = "Qualidade: draft, balanced ou quality.") String qualityPreset, @ToolParam(required = false, description = "Imagem geral opcional para preservar composição e objetos via img2img.") String referenceImageDataUrl, @ToolParam(required = false, description = "Proporção: square, portrait ou landscape.") String aspectRatio, @ToolParam(required = false, description = "Fidelidade à imagem geral de referência, entre 0.1 e 0.9.") Double referenceStrength, @ToolParam(description = "Prompt detalhado da imagem a gerar. Preserve o idioma e descreva estilo, assunto, composição e detalhes visuais.") String prompt, @ToolParam(required = false, description = "CFG opcional entre 1 e 12.") Double cfgScale, @ToolParam(required = false, description = "Tamanho opcional em pixels.") String size, @ToolParam(required = false, description = "Força da referência de pose, entre 0.2 e 1.5.") Double poseStrength, @ToolParam(required = false, description = "Denoise do segundo passe, entre 0.15 e 0.55.") Double refinementStrength, @ToolParam(required = false, description = "Melhora determinística de composição e anatomia.") Boolean enhancePrompt, @ToolParam(required = false, description = "Quantidade exata de sujeitos; zero detecta pelo prompt.") Double subjectCount, @ToolParam(required = false, description = "Seed opcional para repetir uma composição.") Double seed, @ToolParam(required = false, description = "Ativa segundo passe de refinamento em resolução maior.") Boolean refinementEnabled, @ToolParam(required = false, description = "Tipo principal escolhido na interface: auto, person, object, environment, vehicle ou animal.") String subjectType, @ToolParam(required = false, description = "Modelo opcional. Use um nome Ollama ou um checkpoint ComfyUI com prefixo comfyui:.") String model, @ToolParam(required = false, description = "Detalhamento opcional: none, face ou face-hands.") String detailMode, @ToolParam(required = false, description = "Imagem de pose opcional em data URL.") String poseReferenceDataUrl) throws Exception {
        return execute("generate_image", "qualityPreset", qualityPreset, "referenceImageDataUrl", referenceImageDataUrl, "aspectRatio", aspectRatio, "referenceStrength", referenceStrength, "prompt", prompt, "cfgScale", cfgScale, "size", size, "poseStrength", poseStrength, "refinementStrength", refinementStrength, "enhancePrompt", enhancePrompt, "subjectCount", subjectCount, "seed", seed, "refinementEnabled", refinementEnabled, "subjectType", subjectType, "model", model, "detailMode", detailMode, "poseReferenceDataUrl", poseReferenceDataUrl);
    }

    @Tool(name = "generate_video", description = "Gera um vídeo curto local via ComfyUI. No modo auto, usa a imagem mais recente do chat como quadro inicial quando houver uma; use mode=text somente quando o vídeo deve ser criado do zero. Salva em Pictures/Avento Generated Images e pode levar vários minutos.")
    public String generateVideo(@ToolParam(required = false, description = "Duração opcional em segundos, entre 1 e 5. Padrao 2.") Double seconds, @ToolParam(description = "Prompt detalhado do vídeo a gerar: assunto, movimento/ação, estilo, câmera.") String prompt, @ToolParam(required = false, description = "Modo: auto usa a última imagem do chat se existir; image exige essa imagem; text cria do zero.") String mode, @ToolParam(required = false, description = "Tamanho opcional em pixels, por exemplo 832x480. O padrão auto preserva a proporção da imagem.") String size) throws Exception {
        return execute("generate_video", "seconds", seconds, "prompt", prompt, "mode", mode, "size", size);
    }

    @Tool(name = "terminal_run", description = "Executa um comando de terminal permitido e curto dentro de um workspace autorizado. Use para npm create vite@latest, npm install, npm run build/test/lint/typecheck/validate, mvn test/package/verify, git status/diff/log, docker compose ps/down/logs, mkdir -p <caminho relativo> para criar pasta e rm -rf <caminho relativo> para apagar arquivo ou pasta (o alvo desses dois tem que ser relativo ao path informado, sem .. e sem comecar com / ou ~; para apagar uma pasta inteira com backup e confirmacao sempre exigida, prefira a ferramenta delete_directory).")
    public String terminalRun(@ToolParam(description = "Diretorio absoluto autorizado onde o comando deve rodar.") String path, @ToolParam(description = "Comando exato permitido a executar.") String command, @ToolParam(required = false, description = "Timeout opcional em segundos, maximo 300. Padrao 240 para comandos npm/npx (instalam dependencias e podem demorar), 120 para os demais. Em scaffolds pesados (ex.: npx @nestjs/cli new, npm create), passe 300 explicitamente.") Double timeoutSeconds) throws Exception {
        return execute("terminal_run", "path", path, "command", command, "timeoutSeconds", timeoutSeconds);
    }

    @Tool(name = "terminal_start", description = "Inicia um processo longo permitido dentro de um workspace autorizado e retorna um processId. Use para npm run dev ou mvn spring-boot:run.")
    public String terminalStart(@ToolParam(description = "Diretorio absoluto autorizado onde o processo deve rodar.") String path, @ToolParam(description = "Comando longo permitido a iniciar. Exemplos: npm run dev, mvn spring-boot:run.") String command) throws Exception {
        return execute("terminal_start", "path", path, "command", command);
    }

    @Tool(name = "terminal_list", description = "Lista processos longos iniciados pelo Avento.")
    public String terminalList() throws Exception {
        return execute("terminal_list");
    }

    @Tool(name = "schedule_task", description = "Agenda uma nova atividade autônoma ou lembrete para a IA rodar na agenda do Cowork (Cron ou horário específico).")
    public String scheduleTask(@ToolParam(description = "Nome amigável da tarefa ou lembrete (ex: Backup Diário das 03:57 AM).") String name, @ToolParam(description = "Expressão Cron no formato de 5 partes (ex: '57 3 * * *' para todos os dias às 03:57).") String cronExpression, @ToolParam(description = "Instrução completa e detalhada que a IA executará de forma autônoma.") String prompt, @ToolParam(required = false, description = "Descrição adicional opcional.") String description) throws Exception {
        return execute("schedule_task", "name", name, "cronExpression", cronExpression, "prompt", prompt, "description", description);
    }

    @Tool(name = "terminal_logs", description = "Retorna logs recentes de um processo iniciado pelo Avento.")
    public String terminalLogs(@ToolParam(description = "ID retornado por terminal_start.") String processId, @ToolParam(required = false, description = "Quantidade maxima opcional de caracteres, padrao 8000.") Double maxChars) throws Exception {
        return execute("terminal_logs", "processId", processId, "maxChars", maxChars);
    }

    @Tool(name = "terminal_stop", description = "Para um processo iniciado pelo Avento usando o processId interno.")
    public String terminalStop(@ToolParam(description = "ID retornado por terminal_start.") String processId) throws Exception {
        return execute("terminal_stop", "processId", processId);
    }

    @Tool(name = "verify_project", description = "Roda a verificacao do projeto (teste/build) no workspace e retorna se passou e os erros resumidos. Detecta o comando sozinho: package.json (validate/build/typecheck/test/lint) ou pom.xml (mvn test). USE SEMPRE depois de editar codigo: se ok=false, leia o errorSummary, corrija os arquivos e chame de novo, ate ok=true.")
    public String verifyProject(@ToolParam(description = "Diretorio absoluto autorizado do projeto (com package.json ou pom.xml).") String path) throws Exception {
        return execute("verify_project", "path", path);
    }

    @Tool(name = "revert_changes", description = "Desfaz (reverte) as alteracoes de arquivo da ultima resposta que editou o projeto — restaura os arquivos ao estado anterior aquelas edicoes. Use quando o usuario pedir para desfazer, reverter ou voltar o que voce mudou. Chamar de novo desfaz a resposta anterior a essa.")
    public String revertChanges() throws Exception {
        return execute("revert_changes");
    }

    @Tool(name = "search_capabilities", description = "Pesquisa FERRAMENTAS no catalogo interno do Avento (locais, MCP conectadas e servidores disponiveis). NAO pesquisa a internet e NAO busca dados/noticias — para conteudo da web use a ferramenta fetch. Use SOMENTE quando precisar descobrir qual ferramenta ativar para uma capacidade que nao esta na sua lista atual (ex.: query 'pdf', 'planilha', 'web'). Depois ative com activate_tools.")
    public String searchCapabilities(@ToolParam(description = "Palavras-chave da CAPACIDADE procurada (ex.: 'gerar pdf'), nunca o assunto da pesquisa do usuario.") String query) throws Exception {
        return execute("search_capabilities", "query", query);
    }

    @Tool(name = "activate_tools", description = "Ativa ferramentas pelo nome para o restante DESTA execucao: os schemas completos delas passam a ser enviados ao modelo nas proximas rodadas. Se a ferramenta pertencer a um servidor MCP disponivel mas desconectado, o servidor e conectado automaticamente. Ative somente o minimo necessario.")
    public String activateTools(@ToolParam(description = "Nomes exatos das ferramentas a ativar.") List<String> tools) throws Exception {
        return execute("activate_tools", "tools", tools);
    }

    @Tool(name = "search_code", description = "Procura trechos dentro do codigo do projeto conectado. Quando o indice do projeto ja esta pronto, casa por SIGNIFICADO e aceita pergunta em linguagem natural; enquanto o indice esta sendo montado, cai para casamento LITERAL de termo. O campo 'matching' da resposta diz qual dos dois respondeu: se veio 'literal', prefira nome exato de metodo, classe, constante ou mensagem de erro. Para achar a DEFINICAO de um simbolo use find_symbol; para achar pelo NOME do arquivo use search_files.")
    public String searchCode(@ToolParam(required = false, description = "Maximo de trechos retornados, padrao 5.") Double maxResults, @ToolParam(description = "Diretorio raiz autorizado do projeto.") String path, @ToolParam(description = "Pergunta ou termos do que procurar no codigo.") String query) throws Exception {
        return execute("search_code", "maxResults", maxResults, "path", path, "query", query);
    }

    private String execute(String toolName, Object... keyValues) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                arguments.put((String) keyValues[index], value);
            }
        }
        return String.valueOf(tools.execute(toolName, arguments));
    }
}
