import { useState, useCallback } from 'react';
import { api, apiErrorMessage } from '../services/apiClient';

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  children: FileNode[];
}

export interface WriteFileResult {
  success: boolean;
  backupId?: string;
}

const API_FS_TREE = '/api/fs/tree';
const API_FS_READ = '/api/fs/read';
const API_FS_PICK = '/api/fs/pick-folder';
const API_FS_AUTHORIZE = '/api/fs/authorize';
const API_FS_AUTHORIZE_HOME = '/api/fs/authorize-home';

export function useFileSystem() {
  const [fileTree, setFileTree] = useState<FileNode[]>([]);
  const [projectPaths, setProjectPaths] = useState<string[]>([]);
  // Broad roots (currently just an authorized home directory) are kept out of projectPaths on
  // purpose: adding a path there also drives /api/mcp/connect, /api/rag/index, and project
  // analysis in Home/index.tsx, which are fine for a bounded project folder but would recursively
  // RAG-index (and previously crashed while doing so) the user's *entire* home directory.
  const [homeWorkspaceRoot, setHomeWorkspaceRoot] = useState<string | null>(null);
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set());
  const [isLoadingTree, setIsLoadingTree] = useState<boolean>(false);

  const browseFolder = useCallback(async () => {
    try {
      const { data } = await api.get<{ path?: string }>(API_FS_PICK);
      const pickedPath = data.path;
      if (pickedPath) {
        setProjectPaths(prev => {
          if (!prev.includes(pickedPath)) {
            return [...prev, pickedPath];
          }
          return prev;
        });
        return pickedPath;
      }
    } catch (e) {
      console.error("Erro ao explorar pastas", e);
    }
    return null;
  }, []);

  const authorizeHomeFolder = useCallback(async (): Promise<string | null> => {
    try {
      const { data } = await api.post<{ path?: string }>(API_FS_AUTHORIZE_HOME);
      if (data.path) {
        setHomeWorkspaceRoot(data.path);
        return data.path;
      }
    } catch (e) {
      console.error("Erro ao autorizar pasta pessoal", e);
    }
    return null;
  }, []);

  const authorizeProjectPath = useCallback(async (path: string): Promise<string | null> => {
    if (!path) return null;

    try {
      const { data: authorized } = await api.post<{ path?: string }>(API_FS_AUTHORIZE, { path });
      return authorized.path || path;
    } catch (e) {
      console.error("Erro ao autorizar diretório.", e);
      return null;
    }
  }, []);

  const loadProjectTree = useCallback(async (path: string): Promise<string | null> => {
    if (!path) return null;
    setIsLoadingTree(true);
    setFileTree([]);
    setSelectedFiles(new Set());
    try {
      const authorizedPath = await authorizeProjectPath(path);
      if (!authorizedPath) throw new Error("Diretório não autorizado");

      const { data: tree } = await api.post<FileNode[]>(API_FS_TREE, { path: authorizedPath });
      setFileTree(tree);
      setProjectPaths(prev => prev.includes(authorizedPath) ? prev : [...prev, authorizedPath]);
      return authorizedPath;
    } catch (e) {
      console.error("Erro ao carregar diretório.", e);
      return null;
    } finally {
      setIsLoadingTree(false);
    }
  }, [authorizeProjectPath]);

  const toggleFileSelection = useCallback((path: string, checked: boolean) => {
    setSelectedFiles(prev => {
      const next = new Set(prev);
      if (checked) next.add(path);
      else next.delete(path);
      return next;
    });
  }, []);

  const readSelectedFiles = useCallback(async (): Promise<string> => {
    let fileContext = "";
    if (selectedFiles.size > 0) {
      fileContext = "O usuário anexou o conteúdo dos seguintes arquivos locais:\n\n";
      for (const path of Array.from(selectedFiles)) {
        try {
          const { data } = await api.post<{ content?: string }>(API_FS_READ, { path });
          fileContext += `--- Arquivo: ${path} ---\n\`\`\`\n${data.content}\n\`\`\`\n\n`;
        } catch (e) { 
          console.error("Erro lendo arquivo", path, e); 
        }
      }
    }
    return fileContext;
  }, [selectedFiles]);

  const readFile = useCallback(async (path: string): Promise<string> => {
    try {
      const { data } = await api.post<{ content?: string }>(API_FS_READ, { path });
      return data.content || '';
    } catch (e) {
      console.error("Erro ao ler arquivo", e);
      return '';
    }
  }, []);

  const writeFile = useCallback(async (path: string, content: string): Promise<WriteFileResult> => {
    try {
      const { data } = await api.post<{ backupId?: string }>('/api/fs/write', { path, content });
      return { success: true, backupId: data.backupId };
    } catch (e) {
      console.error("Erro ao escrever arquivo", e);
      return { success: false };
    }
  }, []);

  const restoreFile = useCallback(async (backupId: string): Promise<boolean> => {
    try {
      await api.post('/api/fs/restore', { backupId });
      return true;
    } catch (e) {
      console.error("Erro ao restaurar arquivo", apiErrorMessage(e));
      return false;
    }
  }, []);

  const removeProjectPath = useCallback((pathToRemove: string) => {
    setProjectPaths(prev => prev.filter(p => p !== pathToRemove));
  }, []);

  // loadProjectTree só sobrescreve a árvore quando o chat novo tem projeto —
  // sem isso, trocar para um chat sem projeto (ou começar um novo) deixava a
  // árvore/seleção de arquivos do chat anterior visível na sidebar.
  const clearFileTree = useCallback(() => {
    setFileTree([]);
    setSelectedFiles(new Set());
  }, []);

  const clearHomeWorkspaceRoot = useCallback(() => {
    setHomeWorkspaceRoot(null);
  }, []);

  return {
    fileTree,
    projectPaths,
    setProjectPaths,
    removeProjectPath,
    clearFileTree,
    homeWorkspaceRoot,
    clearHomeWorkspaceRoot,
    selectedFiles,
    isLoadingTree,
    browseFolder,
    authorizeProjectPath,
    authorizeHomeFolder,
    loadProjectTree,
    toggleFileSelection,
    readSelectedFiles,
    setSelectedFiles,
    readFile,
    writeFile,
    restoreFile
  };
}
