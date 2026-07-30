import { useEffect, useMemo, useState } from 'react';
import {
  ArrowClockwise,
  CheckCircle,
  MagnifyingGlass,
  Plug,
  Power,
  PushPin,
  PushPinSlash,
  WarningCircle,
  X,
} from '@phosphor-icons/react';
import type { McpActionResult, McpProfile, McpServerDescriptor } from '../../../hooks/useMcpCatalog';
import { useMcpTools } from '../../../hooks/useMcpTools';
import {
  ActionButton,
  Backdrop,
  CloseButton,
  Controls,
  EmptyState,
  ErrorNotice,
  FilterBar,
  Header,
  List,
  Modal,
  PinButton,
  ProfileControl,
  SearchField,
  ServerMeta,
  ServerRow,
  ServerState,
  Summary,
  Toolbar,
  ToolMeta,
  ToolRow,
  ViewTab,
  ViewTabs,
} from './styles';

interface McpToolsManagerProps {
  servers: McpServerDescriptor[];
  isLoading: boolean;
  busyServerId: string | null;
  error: string | null;
  onClose: () => void;
  onRefresh: () => Promise<void>;
  onConnect: (serverId: string) => Promise<McpActionResult>;
  onDisconnect: (serverId: string) => Promise<McpActionResult>;
  onNotify: (message: string) => void;
}

type ProfileFilter = 'all' | McpProfile;
type View = 'servers' | 'tools';

const PROFILE_OPTIONS: Array<{ id: ProfileFilter; label: string }> = [
  { id: 'all', label: 'Todos' },
  { id: 'core', label: 'Núcleo' },
  { id: 'automation', label: 'Automação' },
  { id: 'web', label: 'Web' },
  { id: 'developer', label: 'Dev' },
  { id: 'data', label: 'Dados' },
  { id: 'advanced', label: 'Avançado' },
];

export function McpToolsManager({
  servers,
  isLoading,
  busyServerId,
  error,
  onClose,
  onRefresh,
  onConnect,
  onDisconnect,
  onNotify,
}: McpToolsManagerProps) {
  const [search, setSearch] = useState('');
  const [profile, setProfile] = useState<ProfileFilter>('all');
  const [view, setView] = useState<View>('servers');
  const {
    tools,
    pinned,
    isLoading: isLoadingTools,
    error: toolsError,
    loadTools,
    togglePinned,
  } = useMcpTools();

  const filteredServers = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('pt-BR');
    return servers
      .filter(server => profile === 'all' || server.profile === profile)
      .filter(server => !query || `${server.name} ${server.description} ${server.id}`.toLocaleLowerCase('pt-BR').includes(query))
      .sort((left, right) => Number(right.connected) - Number(left.connected) || left.name.localeCompare(right.name));
  }, [profile, search, servers]);

  // Fixadas primeiro: sao as que o usuario escolheu, e reve-las e o motivo de abrir esta aba.
  const filteredTools = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('pt-BR');
    return tools
      .filter(tool => !query || `${tool.name} ${tool.description} ${tool.mcpServer}`.toLocaleLowerCase('pt-BR').includes(query))
      .sort((left, right) =>
        Number(pinned.includes(right.name)) - Number(pinned.includes(left.name)) ||
        left.name.localeCompare(right.name));
  }, [pinned, search, tools]);

  const connectedCount = servers.filter(server => server.connected).length;
  const availableCount = servers.filter(server => server.available).length;

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  const runAction = async (action: () => Promise<McpActionResult>) => {
    const result = await action();
    if (result.ok) onNotify(result.message);
  };

  return (
    <Backdrop onClick={onClose}>
      <Modal onClick={event => event.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="mcp-tools-title">
        <Header>
          <div>
            <span>Model Context Protocol</span>
            <h2 id="mcp-tools-title">Ferramentas locais</h2>
          </div>
          <CloseButton type="button" onClick={onClose} title="Fechar" aria-label="Fechar">
            <X size={19} weight="bold" />
          </CloseButton>
        </Header>

        <Controls>
          <ViewTabs role="tablist" aria-label="Nível de configuração">
            <ViewTab type="button" role="tab" $active={view === 'servers'} aria-selected={view === 'servers'} onClick={() => setView('servers')}>
              Servidores
            </ViewTab>
            <ViewTab type="button" role="tab" $active={view === 'tools'} aria-selected={view === 'tools'} onClick={() => setView('tools')}>
              Ferramentas
            </ViewTab>
          </ViewTabs>

          <Summary>
            {view === 'servers' ? (
              <>
                <span><CheckCircle size={17} weight="fill" /> {connectedCount} conectadas</span>
                <span><Plug size={17} /> {availableCount} disponíveis</span>
              </>
            ) : (
              <>
                <span><PushPin size={17} weight="fill" /> {pinned.length} fixadas</span>
                <span><Plug size={17} /> {tools.length} disponíveis</span>
              </>
            )}
          </Summary>

          <Toolbar>
            <SearchField>
              <MagnifyingGlass size={17} />
              <input
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder={view === 'servers' ? 'Buscar servidor' : 'Buscar ferramenta'}
                aria-label={view === 'servers' ? 'Buscar servidor' : 'Buscar ferramenta'}
              />
            </SearchField>
            <button
              type="button"
              onClick={view === 'servers' ? onRefresh : loadTools}
              disabled={view === 'servers' ? isLoading : isLoadingTools}
              title="Atualizar catálogo"
              aria-label="Atualizar catálogo"
            >
              <ArrowClockwise size={18} className={(view === 'servers' ? isLoading : isLoadingTools) ? 'spinning' : ''} />
            </button>
          </Toolbar>

          {view === 'servers' && (
            <FilterBar aria-label="Filtrar servidores por perfil">
              {PROFILE_OPTIONS.map(option => (
                <ProfileControl
                  key={option.id}
                  type="button"
                  $active={profile === option.id}
                  aria-pressed={profile === option.id}
                  onClick={() => setProfile(option.id)}
                >
                  {option.label}
                </ProfileControl>
              ))}
            </FilterBar>
          )}

          {(view === 'servers' ? error : toolsError) && (
            <ErrorNotice><WarningCircle size={18} /> <span>{view === 'servers' ? error : toolsError}</span></ErrorNotice>
          )}
        </Controls>

        {view === 'tools' && (
          <List>
            {/* O texto explica o efeito, nao o botao: "fixar" sozinho nao diz que a alternativa e
                depender do modelo pesquisar e ativar a ferramenta sozinho. */}
            <p style={{ margin: '0 0 4px', fontSize: '0.78rem', lineHeight: 1.5, opacity: 0.75 }}>
              O que estiver fixado entra em <strong>toda rodada</strong>. O resto o modelo descobre
              sozinho quando precisa — o que economiza contexto, mas depende dele lembrar de ativar.
            </p>
            {filteredTools.map(tool => {
              const isPinned = pinned.includes(tool.name);
              return (
                <ToolRow key={tool.name} $pinned={isPinned}>
                  <ToolMeta>
                    <div className="tool-title">
                      <strong>{tool.name}</strong>
                      <code>{tool.mcpServer || 'avento'}</code>
                    </div>
                    <p>{tool.description}</p>
                  </ToolMeta>
                  <PinButton
                    type="button"
                    $pinned={isPinned}
                    aria-pressed={isPinned}
                    title={isPinned ? 'Sair de toda rodada' : 'Manter em toda rodada'}
                    onClick={() => togglePinned(tool.name)}
                  >
                    {isPinned ? <PushPinSlash size={16} /> : <PushPin size={16} />}
                    <span>{isPinned ? 'Fixada' : 'Fixar'}</span>
                  </PinButton>
                </ToolRow>
              );
            })}
            {!isLoadingTools && filteredTools.length === 0 && (
              <EmptyState>Nenhuma ferramenta encontrada.</EmptyState>
            )}
            {isLoadingTools && tools.length === 0 && <EmptyState>Carregando ferramentas...</EmptyState>}
          </List>
        )}

        {view === 'servers' && (
        <List>
          {filteredServers.map(server => {
            const busy = busyServerId === server.id;
            return (
              <ServerRow key={server.id} $connected={server.connected}>
                <ServerState $connected={server.connected} $available={server.available} aria-hidden="true" />
                <ServerMeta>
                  <div className="server-title">
                    <strong>{server.name}</strong>
                    <code>{server.id}</code>
                  </div>
                  <p>{server.description}</p>
                  {!server.available && server.unavailableReason && (
                    <span className="unavailable">{server.unavailableReason}</span>
                  )}
                </ServerMeta>
                <ActionButton
                  type="button"
                  $connected={server.connected}
                  disabled={busy || (!server.connected && !server.available)}
                  onClick={() => runAction(() => server.connected
                    ? onDisconnect(server.id)
                    : onConnect(server.id))}
                >
                  {server.connected ? <Power size={17} /> : <Plug size={17} />}
                  <span>{busy ? 'Aguarde' : server.connected ? 'Desconectar' : 'Conectar'}</span>
                </ActionButton>
              </ServerRow>
            );
          })}
          {!isLoading && filteredServers.length === 0 && (
            <EmptyState>Nenhum servidor encontrado.</EmptyState>
          )}
          {isLoading && servers.length === 0 && <EmptyState>Carregando catálogo...</EmptyState>}
        </List>
        )}
      </Modal>
    </Backdrop>
  );
}
