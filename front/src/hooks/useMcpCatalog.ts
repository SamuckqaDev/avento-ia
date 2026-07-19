import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, apiErrorMessage } from '../services/apiClient';

export type McpProfile = 'core' | 'automation' | 'web' | 'developer' | 'data' | 'advanced';

export interface McpServerDescriptor {
  id: string;
  name: string;
  description: string;
  profile: McpProfile;
  local: boolean;
  requiresNetwork: boolean;
  requiresConfiguration: boolean;
  available: boolean;
  connected: boolean;
  unavailableReason: string;
}

interface McpConnectionResult {
  connected: boolean;
  serverName: string;
  error: string;
}

interface McpConnectResponse {
  connected: boolean;
  results: McpConnectionResult[];
}

export interface McpActionResult {
  ok: boolean;
  message: string;
}

export function useMcpCatalog(projectPaths: string[], chatId: number | null) {
  const [servers, setServers] = useState<McpServerDescriptor[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [busyServerId, setBusyServerId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const projectKey = useMemo(() => projectPaths.join('\u0000'), [projectPaths]);

  const loadServers = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const query = new URLSearchParams();
      projectPaths.forEach(path => query.append('workspace', path));
      if (chatId !== null) query.set('chatId', String(chatId));
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      const { data } = await api.get<McpServerDescriptor[]>(`/api/mcp/catalog${suffix}`);
      setServers(Array.isArray(data) ? data : []);
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setIsLoading(false);
    }
  }, [chatId, projectKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const connectServer = useCallback(async (serverId: string): Promise<McpActionResult> => {
    setBusyServerId(serverId);
    setError(null);
    try {
      const { data } = await api.post<McpConnectResponse>('/api/mcp/catalog/connect', {
        serverIds: [serverId],
        projectPaths,
        chatId,
      });
      const result = data.results?.[0];
      if (!result?.connected) {
        const message = result?.error || 'O servidor não iniciou.';
        setError(message);
        return { ok: false, message };
      }
      await loadServers();
      return { ok: true, message: `${result.serverName || serverId} conectado.` };
    } catch (requestError) {
      const message = apiErrorMessage(requestError);
      setError(message);
      return { ok: false, message };
    } finally {
      setBusyServerId(null);
    }
  }, [chatId, loadServers, projectKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const disconnectServer = useCallback(async (serverId: string): Promise<McpActionResult> => {
    setBusyServerId(serverId);
    setError(null);
    try {
      await api.post('/api/mcp/catalog/disconnect', {
        serverIds: [serverId],
        projectPaths: [],
        chatId,
      });
      await loadServers();
      return { ok: true, message: `${serverId} desconectado.` };
    } catch (requestError) {
      const message = apiErrorMessage(requestError);
      setError(message);
      return { ok: false, message };
    } finally {
      setBusyServerId(null);
    }
  }, [chatId, loadServers]);

  useEffect(() => {
    loadServers();
  }, [loadServers]);

  return {
    servers,
    isLoading,
    busyServerId,
    error,
    loadServers,
    connectServer,
    disconnectServer,
  };
}
