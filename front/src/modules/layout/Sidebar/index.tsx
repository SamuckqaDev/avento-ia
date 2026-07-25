import { memo, useState, useEffect, useMemo } from 'react';
import { 
  Container, Brand, LogoContainer, LogoMesh, ScrollArea, Footer,
  Section, ActionBtn, ChatList, ProjectSelectorWrapper, FileTreeWrapper,
  MinimizeButton, ProjectPathList, ProjectPathItem, RemovePathButton,
  HeaderActions, MediaList, MediaItemButton, SectionToggle, SectionCount,
  ChatRow, ChatActions, ChatDeleteButton, DeleteModalBackdrop, DeleteModal, DeleteModalActions, DeleteModalButton,
  DeleteModalError, AccountBtn, AccountAvatar, AccountInfo, AccountName, AccountRole, SearchInputWrapper
} from './styles';
import { ChatSession } from '../../../hooks/useChatHistory';
import { FileNode } from '../../../hooks/useFileSystem';
import { AppNotification } from '../../../hooks/useNotifications';
import { NotificationBell } from '../NotificationBell';
import { Plus, Folder, FolderUser, FileText, ChatsCircle, List, CaretDown, CaretRight, Trash, X, ImageSquare, FilmSlate, Browsers, FilePdf, DownloadSimple, PencilSimple, Check, MagnifyingGlass, Clock } from '@phosphor-icons/react';
import logoUrl from '../../../assets/avento-logo.svg';
import { SettingsModal } from '../SettingsModal';
import { useAuth } from '../../auth/AuthProvider';
import { api } from '../../../services/apiClient';

export interface GeneratedMedia {
  id: string;
  url: string;
  name: string;
  type?: 'image' | 'video' | 'document' | 'artifact';
  createdAt: string;
}

interface SidebarProps {
  isMobileOpen: boolean;
  chats: ChatSession[];
  currentChatId: number | null;
  onNewChat: () => void;
  onLoadChat: (id: number, title: string, projectPaths: string[]) => void;
  onDeleteChat: (chat: ChatSession) => Promise<void>;
  onRenameChat: (chatId: number, title: string) => Promise<void>;

  // Notifications
  notifications: AppNotification[];
  unreadNotificationCount: number;
  onMarkNotificationRead: (id: number) => void;
  onMarkAllNotificationsRead: () => void;

  // File System
  projectPaths: string[];
  removeProjectPath: (path: string) => void;
  homeWorkspaceRoot: string | null;
  clearHomeWorkspaceRoot: () => void;
  browseFolder: () => Promise<string | null>;
  authorizeHomeFolder: () => Promise<string | null>;
  loadProjectTree: (path: string) => void;
  fileTree: FileNode[];
  selectedFiles: Set<string>;
  toggleFileSelection: (path: string, checked: boolean) => void;
  media: GeneratedMedia[];
  onOpenMedia: (media: GeneratedMedia) => void;
  isDarkMode: boolean;
  toggleTheme: () => void;
  isVoiceEnabled: boolean;
  handleToggleVoice: (enabled: boolean) => void;
  activeTab?: 'chat' | 'cowork';
  onSelectTab?: (tab: 'chat' | 'cowork') => void;
}

interface FileTreeNodeProps {
  node: FileNode;
  selectedFiles: Set<string>;
  toggleFileSelection: (path: string, checked: boolean) => void;
}

function FileTreeNode({ node, selectedFiles, toggleFileSelection }: FileTreeNodeProps) {
  const [isExpanded, setIsExpanded] = useState(true);

  if (node.type === 'directory') {
    const children = node.children || [];
    return (
      <li>
        <button
          type="button"
          className="tree-folder"
          onClick={() => setIsExpanded(prev => !prev)}
          title={node.path}
        >
          {isExpanded ? <CaretDown size={12} weight="bold" /> : <CaretRight size={12} weight="bold" />}
          <Folder size={15} weight="fill" />
          <span>{node.name}</span>
        </button>
        {isExpanded && children.length > 0 && (
          <ul>
            {children.map(child => (
              <FileTreeNode
                key={child.path}
                node={child}
                selectedFiles={selectedFiles}
                toggleFileSelection={toggleFileSelection}
              />
            ))}
          </ul>
        )}
      </li>
    );
  }

  return (
    <li>
      <label title={node.path}>
        <input
          type="checkbox"
          checked={selectedFiles.has(node.path)}
          onChange={(event) => toggleFileSelection(node.path, event.target.checked)}
        />
        <FileText size={14} />
        <span>{node.name}</span>
      </label>
    </li>
  );
}

// memo evita re-renderizar a sidebar inteira (lista de chats, arvore de
// arquivos, galeria de midia) toda vez que o Home re-renderiza por algo sem
// relacao, como o nivel de audio do microfone atualizando ~12x/s. Só
// funciona se quem chama <Sidebar> passar props estaveis (useCallback nos
// handlers) — ver Home/index.tsx.
function SidebarComponent({
  isMobileOpen, chats, currentChatId, onNewChat, onLoadChat,
  notifications, unreadNotificationCount, onMarkNotificationRead, onMarkAllNotificationsRead,
  projectPaths, removeProjectPath, homeWorkspaceRoot, clearHomeWorkspaceRoot,
  browseFolder, authorizeHomeFolder, loadProjectTree,
  fileTree, selectedFiles, toggleFileSelection, media, onOpenMedia
  ,onDeleteChat, onRenameChat, isDarkMode, toggleTheme, isVoiceEnabled, handleToggleVoice, activeTab, onSelectTab
}: SidebarProps) {
  const [isMinimized, setIsMinimized] = useState(false);
  const [isMediaExpanded, setIsMediaExpanded] = useState(false);
  const [isProjectContextExpanded, setIsProjectContextExpanded] = useState(true);
  const [chatToDelete, setChatToDelete] = useState<ChatSession | null>(null);
  const [editingChatId, setEditingChatId] = useState<number | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<{ chatId: number; chatTitle: string; snippet: string }[] | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string>(() => localStorage.getItem('avento_avatar_url') || '');

  useEffect(() => {
    const term = searchQuery.trim();
    if (!term || term.length < 2) {
      setSearchResults(null);
      return;
    }
    const timer = setTimeout(() => {
      api.get<{ chatId: number; chatTitle: string; snippet: string }[]>(`/api/chats/search?q=${encodeURIComponent(term)}`)
        .then(({ data }) => {
          setSearchResults(data || []);
        })
        .catch(() => setSearchResults([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const filteredChats = useMemo(() => {
    if (!searchQuery.trim()) return chats;
    const term = searchQuery.toLowerCase().trim();
    if (searchResults !== null && searchResults.length > 0) {
      const matchIds = new Set(searchResults.map(r => r.chatId));
      return chats.filter(chat => matchIds.has(chat.id) || chat.title.toLowerCase().includes(term));
    }
    return chats.filter(chat => chat.title.toLowerCase().includes(term));
  }, [chats, searchQuery, searchResults]);

  // localStorage não é reativo: ouvimos o evento disparado pelo SettingsModal ao trocar a foto,
  // além do evento nativo `storage` (troca em outra aba).
  useEffect(() => {
    const sync = () => setAvatarUrl(localStorage.getItem('avento_avatar_url') || '');
    window.addEventListener('avento:avatar-changed', sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener('avento:avatar-changed', sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  const commitRename = async (chatId: number) => {
    const title = draftTitle.trim();
    setEditingChatId(null);
    const original = chats.find(c => c.id === chatId)?.title;
    if (title && title !== original) {
      try {
        await onRenameChat(chatId, title);
      } catch {
        // Mantém o título antigo se a renomeação falhar.
      }
    }
  };
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const { user } = useAuth();

  const openArtifactSafely = async (item: GeneratedMedia) => {
    const previewWindow = window.open('', '_blank', 'noopener');
    try {
      const response = await api.get<string>(item.url, { responseType: 'text' });
      const blobUrl = URL.createObjectURL(new Blob([response.data], { type: 'text/html' }));
      if (previewWindow) {
        previewWindow.location.replace(blobUrl);
      } else {
        window.open(blobUrl, '_blank', 'noopener');
      }
      window.setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
    } catch {
      previewWindow?.close();
    }
  };

  const confirmDeleteChat = async () => {
    if (!chatToDelete) return;
    setDeleteError(null);
    setIsDeleting(true);
    try {
      await onDeleteChat(chatToDelete);
      setChatToDelete(null);
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : 'Não foi possível apagar a conversa.');
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <Container $isOpen={isMobileOpen} $isMinimized={isMinimized} data-minimized={isMinimized ? 'true' : 'false'}>
      <Brand>
        <LogoContainer className={isMinimized ? 'hide-on-minimized' : ''}>
          <LogoMesh className={isMinimized ? 'hide-on-minimized' : ''}>
            <img src={logoUrl} alt="Avento Logo" />
          </LogoMesh>
          <h1 className={isMinimized ? 'hide-on-minimized' : ''}>
            AVENTO IA
            <span>Local workbench</span>
          </h1>
        </LogoContainer>
        <HeaderActions $isMinimized={isMinimized}>
          <MinimizeButton
            onClick={() => setIsMinimized(!isMinimized)}
            title={isMinimized ? "Expandir" : "Minimizar"}
          >
            <List size={22} />
          </MinimizeButton>
          <NotificationBell
            notifications={notifications}
            unreadCount={unreadNotificationCount}
            onMarkRead={onMarkNotificationRead}
            onMarkAllRead={onMarkAllNotificationsRead}
            isMinimized={isMinimized}
          />
        </HeaderActions>
      </Brand>
      
      <ScrollArea>
        <Section>
          <ActionBtn onClick={() => { onSelectTab?.('chat'); onNewChat(); }} title="Nova Conversa">
            <Plus size={18} weight="bold" /> <span className="hide-on-minimized">Nova Conversa</span>
          </ActionBtn>
          <ActionBtn 
            type="button"
            onClick={() => onSelectTab?.(activeTab === 'cowork' ? 'chat' : 'cowork')} 
            title="Avento Cowork (Agenda)"
            style={{ 
              marginTop: 8, 
              background: activeTab === 'cowork' ? 'rgba(16, 185, 129, 0.2)' : undefined,
              borderColor: activeTab === 'cowork' ? '#10b981' : undefined
            }}
          >
            <Clock size={18} weight="bold" color={activeTab === 'cowork' ? '#10b981' : undefined} /> 
            <span className="hide-on-minimized">Agenda & Cowork</span>
          </ActionBtn>
        </Section>

        <Section className={isMinimized ? 'hide-on-minimized' : ''}>
          <SectionToggle
            type="button"
            $open={isMediaExpanded}
            onClick={() => setIsMediaExpanded(current => !current)}
            aria-expanded={isMediaExpanded}
            title={isMediaExpanded ? 'Minimizar mídias' : 'Expandir mídias'}
          >
            <span className="section-label"><ImageSquare size={16} /> Mídias e Artefatos</span>
            <SectionCount aria-label={`${media.length} itens`}>{media.length}</SectionCount>
            <CaretDown size={14} className="caret" />
          </SectionToggle>
          {isMediaExpanded && (
            media.length === 0 ? (
              <p className="empty-section">As mídias e artefatos gerados aparecerão aqui.</p>
            ) : (
              <MediaList>
                {media.slice(0, 12).map(item => {
                  const kind = item.type
                    ?? (/^avento-video-/i.test(item.name) ? 'video'
                      : /^avento-doc-/i.test(item.name) ? 'document'
                      : /^avento-mockup-/i.test(item.name) ? 'artifact' : 'image');
                  const Icon = kind === 'video' ? FilmSlate
                    : kind === 'document' ? FilePdf
                    : kind === 'artifact' ? Browsers : ImageSquare;
                  const label = item.name
                    .replace(/^avento-(?:image|video|doc|mockup)-/, '')
                    .replace(/\.(?:png|webp|pdf|html)$/, '');
                  const handleClick = () => {
                    if (kind === 'image' || kind === 'video') {
                      onOpenMedia(item);
                    } else if (kind === 'artifact') {
                      void openArtifactSafely(item);
                    } else {
                      window.open(item.url, '_blank', 'noopener');
                    }
                  };
                  return (
                    <MediaItemButton key={item.id} type="button" onClick={handleClick} title={label || item.name}>
                      {kind === 'image' ? (
                        <img src={item.url} alt={label} />
                      ) : (
                        <>
                          <Icon size={18} />
                          <span>{label}</span>
                        </>
                      )}
                    </MediaItemButton>
                  );
                })}
                {currentChatId && media.some(m => m.type === 'artifact' || m.type === 'document'
                  || /^avento-(?:doc|mockup)-/i.test(m.name)) && (
                  <MediaItemButton
                    as="a"
                    href={`/api/media/chat/${currentChatId}/artifacts.zip`}
                    title="Baixar telas e documentos deste chat (.zip)"
                  >
                    <DownloadSimple size={15} />
                    <span>Baixar telas (.zip)</span>
                  </MediaItemButton>
                )}
              </MediaList>
            )
          )}
        </Section>

        <Section className={isMinimized ? 'hide-on-minimized' : ''}>
          <h3>
            <ChatsCircle size={16} /> Histórico
          </h3>
          <SearchInputWrapper>
            <MagnifyingGlass size={15} className="search-icon" />
            <input
              type="text"
              placeholder="Buscar conversas..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
            />
            {searchQuery && (
              <button
                type="button"
                className="clear-search-btn"
                onClick={() => setSearchQuery('')}
                title="Limpar busca"
              >
                <X size={13} />
              </button>
            )}
          </SearchInputWrapper>
          <ChatList>
            {filteredChats.length === 0 ? (
              <li style={{ padding: '8px 10px', fontSize: '0.78rem', color: 'var(--text-muted, #888)' }}>
                {searchQuery ? 'Nenhuma conversa encontrada.' : 'Nenhuma conversa ativa.'}
              </li>
            ) : (
              filteredChats.map(chat => (
              <ChatRow
                key={chat.id}
                className={currentChatId === chat.id ? 'active' : ''}
                onClick={() => editingChatId === chat.id ? undefined : onLoadChat(chat.id, chat.title, chat.projectPaths)}
                title={chat.title}
              >
                {editingChatId === chat.id ? (
                  <input
                    className="chat-rename-input"
                    value={draftTitle}
                    autoFocus
                    onClick={(event) => event.stopPropagation()}
                    onChange={(event) => setDraftTitle(event.target.value)}
                    onBlur={() => commitRename(chat.id)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') { event.preventDefault(); commitRename(chat.id); }
                      if (event.key === 'Escape') { setEditingChatId(null); }
                    }}
                  />
                ) : (
                  <span>{chat.title}</span>
                )}
                <ChatActions>
                  {editingChatId === chat.id ? (
                    <ChatDeleteButton
                      type="button"
                      title="Salvar nome"
                      aria-label="Salvar nome"
                      onMouseDown={(event) => { event.preventDefault(); event.stopPropagation(); commitRename(chat.id); }}
                    >
                      <Check size={15} />
                    </ChatDeleteButton>
                  ) : (
                    <>
                      <ChatDeleteButton
                        type="button"
                        title={`Renomear ${chat.title}`}
                        aria-label={`Renomear ${chat.title}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          setDraftTitle(chat.title);
                          setEditingChatId(chat.id);
                        }}
                      >
                        <PencilSimple size={15} />
                      </ChatDeleteButton>
                      <ChatDeleteButton
                        type="button"
                        className="danger-btn"
                        title={`Apagar ${chat.title}`}
                        aria-label={`Apagar ${chat.title}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          setDeleteError(null);
                          setChatToDelete(chat);
                        }}
                      >
                        <Trash size={15} />
                      </ChatDeleteButton>
                    </>
                  )}
                </ChatActions>
              </ChatRow>
            ))
          )}
          </ChatList>
        </Section>

        <Section className={isMinimized ? 'hide-on-minimized' : ''}>
          <h3 
            className="clickable"
            onClick={() => setIsProjectContextExpanded(!isProjectContextExpanded)}
            title="Mostrar/Ocultar contexto do projeto"
          >
            {isProjectContextExpanded ? <CaretDown size={14} weight="bold" /> : <CaretRight size={14} weight="bold" />}
            <Folder size={16} /> Contexto do Projeto
          </h3>
          
          {isProjectContextExpanded && (
            <ProjectSelectorWrapper>
              <button title="Adicionar Diretório" onClick={async () => {
                const path = await browseFolder();
                if (path) loadProjectTree(path); // Update current tree view if necessary
              }}>
                <Folder size={18} /> <span>Adicionar Pasta</span>
              </button>
              <button
                title="Autoriza sua pasta pessoal inteira (~) de uma vez, sem precisar selecionar subpastas depois. O Avento explora subpastas sob demanda quando você pedir; a árvore de arquivos aqui na barra lateral não é carregada para a pasta pessoal inteira (evita travar com o volume de arquivos)."
                onClick={() => authorizeHomeFolder()}
              >
                <FolderUser size={18} /> <span>Autorizar Pasta Pessoal</span>
              </button>

              {homeWorkspaceRoot && (
                <ProjectPathList>
                  <ProjectPathItem key={homeWorkspaceRoot}>
                    <div
                      className="path-display"
                      title={`${homeWorkspaceRoot} (pasta pessoal — não entra em análise/RAG, só fica disponível para as ferramentas do Avento)`}
                    >
                      🏠 {homeWorkspaceRoot.length > 22 ? '...' + homeWorkspaceRoot.slice(-22) : homeWorkspaceRoot}
                    </div>
                    <RemovePathButton
                      onClick={() => clearHomeWorkspaceRoot()}
                      title="Revogar acesso à pasta pessoal"
                    >
                      <Trash size={14} />
                    </RemovePathButton>
                  </ProjectPathItem>
                </ProjectPathList>
              )}

              <ProjectPathList>
                {projectPaths && projectPaths.map(p => (
                  <ProjectPathItem key={p}>
                    <div className="path-display" title={p}>
                      {p.length > 25 ? '...' + p.slice(-25) : p}
                    </div>
                    <RemovePathButton
                      onClick={() => removeProjectPath(p)} 
                      title="Remover pasta"
                    >
                      <Trash size={14} />
                    </RemovePathButton>
                  </ProjectPathItem>
                ))}
              </ProjectPathList>
              {fileTree.length > 0 && (
                <FileTreeWrapper>
                  <ul>
                    {fileTree.map(node => (
                      <FileTreeNode
                        key={node.path}
                        node={node}
                        selectedFiles={selectedFiles}
                        toggleFileSelection={toggleFileSelection}
                      />
                    ))}
                  </ul>
                </FileTreeWrapper>
              )}
            </ProjectSelectorWrapper>
          )}
        </Section>
      </ScrollArea>

      <Footer>
        <AccountBtn onClick={() => setIsSettingsOpen(true)} title="Sua Conta e Configurações">
          <AccountAvatar>
            {avatarUrl ? (
              <img src={avatarUrl} alt="Foto de perfil" />
            ) : (
              (user?.displayName ? user.displayName.substring(0, 2) : 'US').toUpperCase()
            )}
          </AccountAvatar>
          <AccountInfo className="hide-on-minimized">
            <AccountName>{user?.displayName || 'Usuário'}</AccountName>
            <AccountRole>{user?.role || 'USER'}</AccountRole>
          </AccountInfo>
        </AccountBtn>
      </Footer>

      {isSettingsOpen && (
        <SettingsModal 
          onClose={() => setIsSettingsOpen(false)} 
          isDarkMode={isDarkMode}
          toggleTheme={toggleTheme}
          isVoiceEnabled={isVoiceEnabled}
          handleToggleVoice={handleToggleVoice}
        />
      )}

      {chatToDelete && (
        <DeleteModalBackdrop role="presentation" onClick={() => {
          if (!isDeleting) {
            setDeleteError(null);
            setChatToDelete(null);
          }
        }}>
          <DeleteModal role="dialog" aria-modal="true" aria-labelledby="delete-chat-title" onClick={event => event.stopPropagation()}>
            <button type="button" className="modal-close" onClick={() => {
              setDeleteError(null);
              setChatToDelete(null);
            }} disabled={isDeleting} title="Cancelar">
              <X size={18} />
            </button>
            <h2 id="delete-chat-title">Apagar conversa?</h2>
            <p>Isso apagará permanentemente “{chatToDelete.title}”, suas mensagens e todos os artefatos gerados pelo Avento nessa conversa.</p>
            {deleteError && <DeleteModalError role="alert">{deleteError}</DeleteModalError>}
            <DeleteModalActions>
              <DeleteModalButton type="button" onClick={() => {
                setDeleteError(null);
                setChatToDelete(null);
              }} disabled={isDeleting}>Cancelar</DeleteModalButton>
              <DeleteModalButton $danger type="button" onClick={confirmDeleteChat} disabled={isDeleting}>
                {isDeleting ? 'Apagando...' : 'Apagar definitivamente'}
              </DeleteModalButton>
            </DeleteModalActions>
          </DeleteModal>
        </DeleteModalBackdrop>
      )}
    </Container>
  );
}

export const Sidebar = memo(SidebarComponent);
