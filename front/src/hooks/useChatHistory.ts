import { useState, useCallback } from 'react';
import axios from 'axios';
import { Message } from '../modules/chat/MessageBubble';
import { api, apiErrorMessage } from '../services/apiClient';

export interface ChatSession {
  id: number;
  title: string;
  projectPaths: string[];
}

const API_CHATS_DB = '/api/chats';

function parseProjectPaths(projectPath?: string | null): string[] {
  return projectPath ? projectPath.split('|').map(path => path.trim()).filter(Boolean) : [];
}

function serializeProjectPaths(projectPaths?: string[]): string {
  return projectPaths?.map(path => path.trim()).filter(Boolean).join('|') || '';
}

export function useChatHistory() {
  const [chats, setChats] = useState<ChatSession[]>([]);
  const [currentChatId, setCurrentChatId] = useState<number | null>(null);

  const loadChats = useCallback(async () => {
    try {
      const { data } = await api.get<any[]>(API_CHATS_DB);
      const parsedData = data.map((chat: any) => ({
        ...chat,
        projectPaths: parseProjectPaths(chat.projectPath)
      }));
      setChats(parsedData);
      setCurrentChatId(currentId => (
        currentId !== null && !parsedData.some(chat => chat.id === currentId) ? null : currentId
      ));
    } catch (e) {
      console.error("Error loading chats", e);
    }
  }, []);

  // Retorna null em falha (em vez de []): quem chama precisa distinguir "chat sem
  // historico" de "não consegui buscar o historico". Devolver [] numa falha fazia a
  // tela renderizar a conversa como vazia — parecia perda de dados quando era só um
  // fetch que falhou (ex.: backend reiniciando no momento da troca de chat).
  const loadChatMessages = useCallback(async (id: number): Promise<Message[] | null> => {
    try {
      const { data } = await api.get<Message[]>(`${API_CHATS_DB}/${id}/messages`);
      return data;
    } catch (e) {
      console.error("Error loading chat messages", e);
      return null;
    }
  }, []);

  const updateChatContext = useCallback(async (chatId: number, projectPaths: string[]) => {
    const projectPath = serializeProjectPaths(projectPaths);
    await api.patch(`${API_CHATS_DB}/${chatId}`, { projectPath });
    await loadChats();
  }, [loadChats]);

  const deleteChat = useCallback(async (chatId: number) => {
    try {
      await api.delete(`${API_CHATS_DB}/${chatId}`);
    } catch (error) {
      throw new Error(apiErrorMessage(error));
    }
    setChats(prev => prev.filter(chat => chat.id !== chatId));
    if (currentChatId === chatId) {
      setCurrentChatId(null);
    }
  }, [currentChatId]);

  const saveMessageToDB = useCallback(async (
    role: string,
    content: string,
    title?: string,
    projectPaths?: string[],
    existingChatId?: number | null,
    documentContext?: string,
    documentNames?: string
  ) => {
    let activeChatId = existingChatId;
    const pathStr = serializeProjectPaths(projectPaths);

    const createChat = async () => {
      const chatTitle = title || content.substring(0, 30) || 'Nova Conversa';
      const { data: newChat } = await api.post<{ id: number }>(API_CHATS_DB, {
        title: chatTitle,
        projectPath: pathStr,
      });
      setCurrentChatId(newChat.id);
      await loadChats();
      return newChat.id;
    };

    if (!activeChatId) {
      activeChatId = await createChat();
    } else if (projectPaths) {
      try {
        await updateChatContext(activeChatId, projectPaths);
      } catch (error) {
        console.error("Error updating chat context", error);
      }
    }
    
    const messagePayload = {
      role,
      content,
      documentContext: documentContext || null,
      documentNames: documentNames || null,
    };
    try {
      await api.post(`${API_CHATS_DB}/${activeChatId}/messages`, messagePayload);
    } catch (error) {
      if (!axios.isAxiosError(error) || error.response?.status !== 404) {
        throw error;
      }
      activeChatId = await createChat();
      await api.post(`${API_CHATS_DB}/${activeChatId}/messages`, messagePayload);
    }

    return activeChatId;
  }, [loadChats, updateChatContext]);

  return {
    chats,
    currentChatId,
    setCurrentChatId,
    loadChats,
    loadChatMessages,
    saveMessageToDB,
    updateChatContext,
    deleteChat
  };
}
