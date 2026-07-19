import { useEffect, useMemo, useState } from 'react';
import {
  ArrowClockwise,
  CheckCircle,
  MagnifyingGlass,
  Plug,
  Power,
  WarningCircle,
  X,
} from '@phosphor-icons/react';
import type { McpActionResult, McpProfile, McpServerDescriptor } from '../../../hooks/useMcpCatalog';
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
  ProfileControl,
  SearchField,
  ServerMeta,
  ServerRow,
  ServerState,
  Summary,
  Toolbar,
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

  const filteredServers = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('pt-BR');
    return servers
      .filter(server => profile === 'all' || server.profile === profile)
      .filter(server => !query || `${server.name} ${server.description} ${server.id}`.toLocaleLowerCase('pt-BR').includes(query))
      .sort((left, right) => Number(right.connected) - Number(left.connected) || left.name.localeCompare(right.name));
  }, [profile, search, servers]);

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
          <Summary>
            <span><CheckCircle size={17} weight="fill" /> {connectedCount} conectadas</span>
            <span><Plug size={17} /> {availableCount} disponíveis</span>
          </Summary>

          <Toolbar>
            <SearchField>
              <MagnifyingGlass size={17} />
              <input
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder="Buscar ferramenta"
                aria-label="Buscar ferramenta"
              />
            </SearchField>
            <button type="button" onClick={onRefresh} disabled={isLoading} title="Atualizar catálogo" aria-label="Atualizar catálogo">
              <ArrowClockwise size={18} className={isLoading ? 'spinning' : ''} />
            </button>
          </Toolbar>

          <FilterBar aria-label="Filtrar ferramentas por perfil">
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

          {error && <ErrorNotice><WarningCircle size={18} /> <span>{error}</span></ErrorNotice>}
        </Controls>

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
            <EmptyState>Nenhuma ferramenta encontrada.</EmptyState>
          )}
          {isLoading && servers.length === 0 && <EmptyState>Carregando catálogo...</EmptyState>}
        </List>
      </Modal>
    </Backdrop>
  );
}
