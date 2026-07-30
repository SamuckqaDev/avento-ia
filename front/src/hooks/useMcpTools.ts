import { useCallback, useEffect, useState } from 'react';
import { api, apiErrorMessage } from '../services/apiClient';

/**
 * Ferramenta individual, como o backend a expõe em `/api/mcp/tools`.
 *
 * <p>`mcpServer` vazio identifica ferramenta local do Avento (arquivo, terminal, imagem), que não
 * vem de servidor MCP nenhum.
 */
export interface McpToolDescriptor {
  name: string;
  description: string;
  mcpServer: string;
  originalName: string;
}

/**
 * Catálogo de ferramentas e a lista fixada pelo usuário.
 *
 * <p>Separado do `useMcpCatalog` de propósito: aquele trata de SERVIDORES (ligar e desligar), este
 * de FERRAMENTAS (quais o modelo enxerga em toda rodada). São dois níveis diferentes, e juntá-los
 * num hook só faria a tela recarregar o catálogo inteiro a cada clique num checkbox.
 */
export function useMcpTools() {
  const [tools, setTools] = useState<McpToolDescriptor[]>([]);
  const [pinned, setPinned] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadTools = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [toolsResponse, pinnedResponse] = await Promise.all([
        api.get<McpToolDescriptor[]>('/api/mcp/tools'),
        api.get<string[]>('/api/mcp/tools/pinned'),
      ]);
      setTools(Array.isArray(toolsResponse.data) ? toolsResponse.data : []);
      setPinned(Array.isArray(pinnedResponse.data) ? pinnedResponse.data : []);
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setIsLoading(false);
    }
  }, []);

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
