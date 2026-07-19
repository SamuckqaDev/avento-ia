import { memo, useState } from 'react';
import { 
  Container, Brand, LogoContainer, LogoMesh, ScrollArea, Footer,
  Section, ActionBtn, ChatList, ProjectSelectorWrapper, FileTreeWrapper,
  MinimizeButton, ProjectPathList, ProjectPathItem, RemovePathButton,
  HeaderActions, MediaList, MediaItemButton, SectionToggle, SectionCount,
  ChatRow, ChatDeleteButton, DeleteModalBackdrop, DeleteModal, DeleteModalActions, DeleteModalButton,
  DeleteModalError
} from './styles';
import { ChatSession } from '../../../hooks/useChatHistory';
import { FileNode } from '../../../hooks/useFileSystem';
import { AppNotification } from '../../../hooks/useNotifications';
import { NotificationBell } from '../NotificationBell';
import { Plus, Folder, FolderUser, FileText, ChatsCircle, List, CaretDown, CaretRight, Trash, X, ImageSquare, FilmSlate, Gear } from '@phosphor-icons/react';
import logoUrl from '../../../assets/avento-logo.svg';
import { SettingsModal } from '../SettingsModal';

export interface GeneratedMedia {
  id: string;
  url: string;
  name: string;
  createdAt: string;
}

interface SidebarProps {
  isMobileOpen: boolean;
  chats: ChatSession[];
  currentChatId: number | null;
  onNewChat: () => void;
  onLoadChat: (id: number, title: string, projectPaths: string[]) => void;
  onDeleteChat: (chat: ChatSession) => Promise<void>;

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
  ,onDeleteChat
}: SidebarProps) {
  const [isMinimized, setIsMinimized] = useState(false);
  const [isMediaExpanded, setIsMediaExpanded] = useState(false);
  const [isProjectContextExpanded, setIsProjectContextExpanded] = useState(true);
  const [chatToDelete, setChatToDelete] = useState<ChatSession | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

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
        <LogoContainer>
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
          <ActionBtn onClick={onNewChat} title="Nova Conversa">
            <Plus size={18} weight="bold" /> <span className="hide-on-minimized">Nova Conversa</span>
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
            <span className="section-label"><ImageSquare size={16} /> Mídias</span>
            <SectionCount aria-label={`${media.length} mídias`}>{media.length}</SectionCount>
            <CaretDown size={14} className="caret" />
          </SectionToggle>
          {isMediaExpanded && (
            media.length === 0 ? (
              <p className="empty-section">As mídias geradas aparecerão aqui.</p>
            ) : (
              <MediaList>
                {media.slice(0, 8).map(item => {
                  const isVideo = /^avento-video-/i.test(item.name);
                  return (
                    <MediaItemButton key={item.id} type="button" onClick={() => onOpenMedia(item)} title={item.name}>
                      {isVideo ? <FilmSlate size={15} /> : <ImageSquare size={15} />}
                      <span>{item.name.replace(/^avento-(?:image|video)-/, '').replace(/\.(?:png|webp)$/, '')}</span>
                    </MediaItemButton>
                  );
                })}
              </MediaList>
            )
          )}
        </Section>

        <Section className={isMinimized ? 'hide-on-minimized' : ''}>
          <h3>
            <ChatsCircle size={16} /> Histórico
          </h3>
          <ChatList>
            {chats.map(chat => (
              <ChatRow
                key={chat.id} 
                className={currentChatId === chat.id ? 'active' : ''}
                onClick={() => onLoadChat(chat.id, chat.title, chat.projectPaths)}
                title={chat.title}
              >
                <span>{chat.title}</span>
                <ChatDeleteButton
                  type="button"
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
              </ChatRow>
            ))}
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

      <Footer className="hide-on-minimized">
        <p>Avento Model Context Protocol</p>
        <button 
          type="button" 
          onClick={() => setIsSettingsOpen(true)}
          title="Configurações Locais"
          style={{ background: 'transparent', border: 'none', color: '#9FB8B1', cursor: 'pointer', display: 'flex' }}
        >
          <Gear size={20} />
        </button>
      </Footer>

      {isSettingsOpen && (
        <SettingsModal onClose={() => setIsSettingsOpen(false)} />
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
