import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, apiErrorMessage } from '../services/apiClient';

/** Uma entrada do catálogo operacional, obtida sem iniciar servidores desligados. */
export interface McpToolCatalogEntry {
  id: string;
  entryType: 'TOOL' | 'SERVER';
  name: string;
  source: 'AVENTO_NATIVE' | 'LOCAL_MCP' | 'DOCKER_MCP';
  serverId: string;
  description: string;
  category: string;
  riskLevel: string;
  availability: 'READY' | 'AVAILABLE' | 'DEGRADED' | 'UNAVAILABLE';
  requiresConnection: boolean;
  reason: string;
}

interface McpToolCatalogResponse {
  entries: McpToolCatalogEntry[];
}

/**
 * Catálogo de ferramentas e a lista fixada pelo usuário.
 *
 * <p>Separado do `useMcpCatalog` de propósito: aquele trata de SERVIDORES (ligar e desligar), este
 * de FERRAMENTAS (quais o modelo enxerga em toda rodada). São dois níveis diferentes, e juntá-los
 * num hook só faria a tela recarregar o catálogo inteiro a cada clique num checkbox.
 */
export function useMcpTools(projectPaths: string[], chatId: number | null) {
  const [tools, setTools] = useState<McpToolCatalogEntry[]>([]);
  const [pinned, setPinned] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const projectKey = projectPaths.join('\u0000');
  const workspaces = useMemo(
    () => projectKey ? projectKey.split('\u0000') : [],
    [projectKey],
  );

  const loadTools = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const query = new URLSearchParams();
      workspaces.forEach(path => query.append('workspace', path));
      if (chatId !== null) query.set('chatId', String(chatId));
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      const [toolsResponse, pinnedResponse] = await Promise.all([
        api.get<McpToolCatalogResponse>(`/api/mcp/catalog/tools${suffix}`),
        api.get<string[]>('/api/mcp/tools/pinned'),
      ]);
      setTools(Array.isArray(toolsResponse.data?.entries) ? toolsResponse.data.entries : []);
      setPinned(Array.isArray(pinnedResponse.data) ? pinnedResponse.data : []);
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setIsLoading(false);
    }
  }, [chatId, workspaces]);

  /**
   * Fixa ou desfixa uma ferramenta.
   *
   * <p>Atualiza a tela antes da resposta e desfaz se o servidor recusar: um checkbox que só reage
   * depois da ida e volta parece quebrado, e marcar vários seguidos vira uma fila de esperas.
   */
  const togglePinned = useCallback(async (toolName: string) => {
    const previous = pinned;
    const next = previous.includes(toolName)
      ? previous.filter(name => name !== toolName)
      : [...previous, toolName];

    setPinned(next);
    setError(null);
    try {
      // Estado completo, nunca um delta: o servidor grava exatamente o que a tela mostra.
      const { data } = await api.put<string[]>('/api/mcp/tools/pinned', { toolNames: next });
      setPinned(Array.isArray(data) ? data : next);
    } catch (requestError) {
      setPinned(previous);
      setError(apiErrorMessage(requestError));
    }
  }, [pinned]);

  const clearPinned = useCallback(async () => {
    const previous = pinned;
    setPinned([]);
    try {
      await api.put<string[]>('/api/mcp/tools/pinned', { toolNames: [] });
    } catch (requestError) {
      setPinned(previous);
      setError(apiErrorMessage(requestError));
    }
  }, [pinned]);

  useEffect(() => {
    loadTools();
  }, [loadTools]);

  return { tools, pinned, isLoading, error, loadTools, togglePinned, clearPinned };
}
