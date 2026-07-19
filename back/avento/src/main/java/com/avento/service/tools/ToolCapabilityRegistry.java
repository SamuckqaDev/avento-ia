package com.avento.service.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ToolCapabilityRegistry {

    private final Map<String, ToolCapability> tools;

    public ToolCapabilityRegistry() {
        Map<String, ToolCapability> definitions = new LinkedHashMap<>();

        register(
                definitions,
                "directory_tree",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Lista a arvore de arquivos autorizada.");
        register(
                definitions,
                "read_file",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Le arquivo autorizado.");
        register(
                definitions,
                "read_document",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Converte e le documento autorizado com MarkItDown local.");
        register(
                definitions,
                "list_mcp_servers",
                ToolCategory.MCP_EXTERNAL,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Lista servidores MCP locais e sua disponibilidade.");
        register(
                definitions,
                "connect_mcp_server",
                ToolCategory.MCP_EXTERNAL,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Inicia um servidor MCP local sob demanda.");
        register(
                definitions,
                "disconnect_mcp_server",
                ToolCategory.MCP_EXTERNAL,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Encerra um servidor MCP conectado.");
        register(
                definitions,
                "search_files",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Busca arquivos por nome dentro de workspace autorizado.");
        register(
                definitions,
                "write_file",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Cria ou sobrescreve arquivo com backup.");
        register(
                definitions,
                "edit_file",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Substitui um trecho exato de um arquivo existente com backup.");
        register(
                definitions,
                "delete_file",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.DESTRUCTIVE,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Remove arquivo com backup.");
        register(
                definitions,
                "delete_directory",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.DESTRUCTIVE,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Remove pasta inteira com backup quando viavel.");
        register(
                definitions,
                "create_directory",
                ToolCategory.FILESYSTEM,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Cria diretorio dentro do workspace.");
        register(
                definitions,
                "create_vite_project",
                ToolCategory.PROJECT_SCAFFOLD,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Cria projeto Vite dentro de workspace autorizado.");

        register(
                definitions,
                "list_macos_apps",
                ToolCategory.MACOS_APP,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                true,
                "Lista aplicativos instalados no macOS.");
        register(
                definitions,
                "open_app",
                ToolCategory.MACOS_APP,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Abre aplicativo local.");
        register(
                definitions,
                "close_app",
                ToolCategory.MACOS_APP,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Fecha aplicativo local inteiro.");
        register(
                definitions,
                "open_browser_tab",
                ToolCategory.BROWSER,
                ToolRiskLevel.LOW,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                true,
                "Abre nova aba no navegador.");
        register(
                definitions,
                "close_browser_tab",
                ToolCategory.BROWSER,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Fecha somente a aba ativa do navegador.");
        register(
                definitions,
                "open_url",
                ToolCategory.URL,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Abre URL no navegador padrao.");
        register(
                definitions,
                "open_path",
                ToolCategory.FINDER,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Abre arquivo ou pasta autorizada.");
        register(
                definitions,
                "reveal_in_finder",
                ToolCategory.FINDER,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Mostra arquivo ou pasta no Finder.");
        register(
                definitions,
                "run_shortcut",
                ToolCategory.SHORTCUT,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Executa atalho do macOS Shortcuts.");
        register(
                definitions,
                "capture_screen",
                ToolCategory.SCREEN,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Captura screenshot da tela.");
        register(
                definitions,
                "generate_image",
                ToolCategory.IMAGE,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Gera imagem local com modelo de imagem do Ollama.");
        register(
                definitions,
                "generate_video",
                ToolCategory.IMAGE,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Gera vídeo curto local via ComfyUI a partir de prompt.");
        register(
                definitions,
                "generate_pdf",
                ToolCategory.DOCUMENT,
                ToolRiskLevel.MEDIUM,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Gera documento PDF a partir de Markdown ou HTML.");

        register(
                definitions,
                "terminal_run",
                ToolCategory.TERMINAL,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Executa comando curto permitido.");
        register(
                definitions,
                "terminal_start",
                ToolCategory.TERMINAL,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Inicia processo longo gerenciado.");
        register(
                definitions,
                "terminal_stop",
                ToolCategory.TERMINAL,
                ToolRiskLevel.HIGH,
                ToolApprovalPolicy.APPROVAL_REQUIRED,
                false,
                "Para processo gerenciado.");
        register(
                definitions,
                "terminal_list",
                ToolCategory.TERMINAL,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Lista processos gerenciados.");
        register(
                definitions,
                "terminal_logs",
                ToolCategory.TERMINAL,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                "Le logs de processo gerenciado.");

        registerReadOnlyMcp(definitions, "sequentialthinking", "Estrutura o raciocinio de uma tarefa complexa.");
        registerReadOnlyMcp(definitions, "get_current_time", "Consulta horario atual por fuso.");
        registerReadOnlyMcp(definitions, "convert_time", "Converte horario entre fusos.");
        registerReadOnlyMcp(definitions, "read_graph", "Le a memoria persistente local.");
        registerReadOnlyMcp(definitions, "search_nodes", "Pesquisa a memoria persistente local.");
        registerReadOnlyMcp(definitions, "open_nodes", "Abre nos especificos da memoria persistente local.");

        this.tools = Map.copyOf(definitions);
    }

    public Optional<ToolCapability> find(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    public Collection<ToolCapability> all() {
        return tools.values();
    }

    // Ferramentas fora deste registro (servidores MCP externos como Desktop
    // Commander, Automator, Git, Docker etc.) nao tem risco classificado, entao
    // por seguranca exigem aprovacao por padrao em vez de rodar livres.
    public boolean requiresApproval(String toolName) {
        return find(toolName).map(ToolCapability::requiresApproval).orElse(true);
    }

    public boolean canExecuteDirectly(String toolName) {
        return find(toolName).map(ToolCapability::directAutoExecutable).orElse(false);
    }

    private void register(
            Map<String, ToolCapability> definitions,
            String name,
            ToolCategory category,
            ToolRiskLevel riskLevel,
            ToolApprovalPolicy approvalPolicy,
            boolean directAutoExecutable,
            String summary) {
        definitions.put(
                name, new ToolCapability(name, category, riskLevel, approvalPolicy, directAutoExecutable, summary));
    }

    private void registerReadOnlyMcp(Map<String, ToolCapability> definitions, String name, String summary) {
        register(
                definitions,
                name,
                ToolCategory.MCP_EXTERNAL,
                ToolRiskLevel.READ_ONLY,
                ToolApprovalPolicy.AUTO,
                false,
                summary);
    }
}
