import { useState } from 'react';
import { WarningOctagon, CheckCircle, XCircle, Spinner } from '@phosphor-icons/react';
import {
  Bar,
  Header,
  ToolBadge,
  Title,
  ArgumentsBox,
  ArgumentRow,
  ArgKey,
  ArgValue,
  VoiceHint,
  Actions,
  ApproveButton,
  RejectButton,
  CommentInput,
} from './styles';

interface ApprovalCardProps {
  toolName: string;
  toolArguments: Record<string, unknown>;
  isLoading?: boolean;
  onApprove: (comment: string) => void;
  onReject: (comment: string) => void;
}

function formatArgValue(value: unknown): string {
  if (typeof value === 'string') {
    return value.length > 120 ? value.slice(0, 117) + '…' : value;
  }
  const str = JSON.stringify(value);
  return str.length > 120 ? str.slice(0, 117) + '…' : str;
}

const TOOL_LABELS: Record<string, string> = {
  write_file: 'Escrever arquivo',
  edit_file: 'Editar trecho do arquivo',
  delete_file: 'Apagar arquivo',
  delete_directory: 'Apagar pasta inteira',
  create_directory: 'Criar diretório',
  create_vite_project: 'Criar projeto Vite',
  open_app: 'Abrir aplicativo',
  close_app: 'Fechar aplicativo',
  open_browser_tab: 'Abrir nova aba',
  close_browser_tab: 'Fechar aba',
  open_url: 'Abrir URL',
  open_path: 'Abrir arquivo ou pasta',
  reveal_in_finder: 'Mostrar no Finder',
  run_shortcut: 'Rodar atalho',
  capture_screen: 'Capturar tela',
  terminal_run: 'Rodar comando',
  terminal_start: 'Iniciar processo',
  terminal_stop: 'Parar processo',

  // Git MCP (@cyanheads/git-mcp-server)
  git_add: 'Adicionar arquivos ao stage (git add)',
  git_blame: 'Ver autoria linha a linha (git blame)',
  git_branch: 'Gerenciar branches',
  git_changelog_analyze: 'Analisar changelog',
  git_checkout: 'Trocar de branch (git checkout)',
  git_cherry_pick: 'Aplicar commit de outra branch (cherry-pick)',
  git_clean: 'Remover arquivos não rastreados (git clean)',
  git_clear_working_dir: 'Limpar diretório de trabalho da sessão',
  git_clone: 'Clonar repositório',
  git_commit: 'Criar commit',
  git_diff: 'Ver diferenças (git diff)',
  git_fetch: 'Buscar atualizações do remoto (git fetch)',
  git_init: 'Inicializar repositório git',
  git_log: 'Ver histórico de commits',
  git_merge: 'Mesclar branches (git merge)',
  git_pull: 'Puxar do remoto (git pull)',
  git_push: 'Enviar para o remoto (git push)',
  git_rebase: 'Rebase de commits',
  git_reflog: 'Ver reflog',
  git_remote: 'Gerenciar remotos',
  git_reset: 'Resetar HEAD (git reset)',
  git_set_working_dir: 'Definir diretório de trabalho da sessão',
  git_show: 'Ver detalhes de um objeto git',
  git_stash: 'Gerenciar stash',
  git_status: 'Ver status do repositório',
  git_tag: 'Gerenciar tags',
  git_worktree: 'Gerenciar worktrees',
  git_wrapup_instructions: 'Checklist de encerramento de sessão',

  // Postgres MCP (@modelcontextprotocol/server-postgres)
  query: 'Consultar banco de dados (somente leitura)',

  // Apple MCP (apple-mcp)
  contacts: 'Buscar contatos',
  notes: 'Notas (buscar/criar)',
  messages: 'Mensagens (ler/enviar/agendar)',
  mail: 'E-mail (ler/buscar/enviar)',
  reminders: 'Lembretes',
  calendar: 'Calendário',
  maps: 'Mapas',

  // Chrome DevTools MCP (chrome-devtools-mcp)
  click: 'Clicar em elemento',
  close_page: 'Fechar aba do Chrome',
  drag: 'Arrastar elemento',
  emulate: 'Emular recurso no navegador',
  evaluate_script: 'Executar JavaScript na página',
  fill: 'Preencher campo',
  fill_form: 'Preencher formulário',
  get_console_message: 'Ver mensagem do console',
  get_network_request: 'Ver requisição de rede',
  handle_dialog: 'Responder diálogo do navegador',
  hover: 'Passar mouse sobre elemento',
  lighthouse_audit: 'Auditoria Lighthouse',
  list_console_messages: 'Listar mensagens do console',
  list_network_requests: 'Listar requisições de rede',
  list_pages: 'Listar abas abertas',
  navigate_page: 'Navegar para URL',
  new_page: 'Abrir nova aba no Chrome',
  performance_analyze_insight: 'Analisar insight de performance',
  performance_start_trace: 'Iniciar gravação de performance',
  performance_stop_trace: 'Parar gravação de performance',
  press_key: 'Pressionar tecla',
  resize_page: 'Redimensionar janela',
  select_page: 'Selecionar aba',
  take_heapsnapshot: 'Capturar heap snapshot',
  take_screenshot: 'Tirar screenshot da página',
  take_snapshot: 'Capturar snapshot da página (acessibilidade)',
  type_text: 'Digitar texto',
  upload_file: 'Enviar arquivo',
  wait_for: 'Esperar texto aparecer',
};

export function ApprovalCard({ toolName, toolArguments, isLoading = false, onApprove, onReject }: ApprovalCardProps) {
  const [comment, setComment] = useState('');
  const label = TOOL_LABELS[toolName] || toolName;
  const argEntries = Object.entries(toolArguments).filter(([, v]) => v !== undefined && v !== null && v !== '');

  return (
    <Bar>
      <Header>
        <WarningOctagon size={20} weight="fill" />
        <Title>
          Ação pendente de aprovação
        </Title>
        <ToolBadge>{label}</ToolBadge>
      </Header>

      {argEntries.length > 0 && (
        <ArgumentsBox>
          {argEntries.slice(0, 6).map(([key, value]) => (
            <ArgumentRow key={key}>
              <ArgKey>{key}</ArgKey>
              <ArgValue>{formatArgValue(value)}</ArgValue>
            </ArgumentRow>
          ))}
        </ArgumentsBox>
      )}

      <VoiceHint>
        Aprovar libera também as próximas ações deste mesmo plano, sem pedir de novo — exceto apagar arquivo, parar
        processo ou fechar aplicativo, que sempre confirmam à parte.
      </VoiceHint>
      <VoiceHint>
        Por voz: “aprovo”, “aprovo por 1 hora”, “aprovo por 24 horas”, “sempre neste projeto” ou “cancela”.
      </VoiceHint>

      <CommentInput
        value={comment}
        onChange={(event) => setComment(event.target.value)}
        disabled={isLoading}
        placeholder="Comentário opcional antes de aprovar ou cancelar"
      />

      <Actions>
        <ApproveButton onClick={() => onApprove(comment)} disabled={isLoading}>
          {isLoading ? (
            <Spinner size={18} />
          ) : (
            <CheckCircle size={18} weight="fill" />
          )}
          {isLoading ? 'Executando…' : 'Aprovar e executar'}
        </ApproveButton>
        <RejectButton onClick={() => onReject(comment)} disabled={isLoading}>
          <XCircle size={18} />
          Cancelar
        </RejectButton>
      </Actions>
    </Bar>
  );
}
