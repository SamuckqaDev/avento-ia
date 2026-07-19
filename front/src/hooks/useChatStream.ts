import { useState, useCallback, useEffect, useRef } from 'react';
import { api } from '../services/apiClient';

export interface ChunkData {
  content: string;
  thinking: string;
  newText: string;
  duration: string;
  tokens: number;
  isFinal: boolean;
}

export interface ChatStreamContext {
  chatId: number | null;
  requestId: string;
  runId?: string;
  resumed?: boolean;
}

interface ActiveAgentRun {
  runId: string;
  chatId: number;
  status: 'QUEUED' | 'RUNNING' | 'WAITING_APPROVAL' | 'CANCEL_REQUESTED';
}

export interface AgentActivityEvent {
  type: string;
  title: string;
  detail: string;
  timestamp: string;
  // Campos presentes apenas em eventos tool.approval.required
  approvalId?: string;
  toolName?: string;
  toolArguments?: Record<string, unknown>;
  // Campos presentes em eventos tool.completed de ferramentas de terminal
  processId?: string;
  running?: boolean;
  command?: string;
  runId?: string;
  // Campo presente em eventos agent.tokens.usage
  evalCount?: number;
}

export interface MessageContext {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  images?: string[];
  tool_calls?: any[];
  tool_call_id?: string;
  name?: string;
}

export interface ImageGenerationOptions {
  qualityPreset: 'draft' | 'balanced' | 'quality';
  aspectRatio: 'square' | 'portrait' | 'landscape';
  subjectType: 'auto' | 'person' | 'object' | 'environment' | 'vehicle' | 'animal';
  seed?: number;
  subjectCount: number;
  enhancePrompt: boolean;
  refinementEnabled: boolean;
  refinementStrength: number;
  detailMode: 'none' | 'face' | 'face-hands';
  cfgScale: number;
  referenceImageDataUrl?: string;
  referenceStrength: number;
  referenceMode: 'composition' | 'identity' | 'transform';
  structureControl: 'none' | 'depth' | 'canny';
  structureStrength: number;
  poseReferenceDataUrl?: string;
  poseStrength: number;
  adherenceValidationEnabled: boolean;
  maxAdherenceRetries: number;
}

function looksLikeToolMarkup(text: string): boolean {
  const normalized = text.trim().toLowerCase();
  return normalized.includes('{function') ||
    normalized.includes('function <') ||
    normalized.startsWith('{"name"') ||
    normalized.startsWith('{"parameters"') ||
    normalized.startsWith('{"arguments"');
}

function stripToolMarkup(text: string): string {
  const normalized = text.toLowerCase();
  const markers = ['{function', 'function <', '{"name"', '{"parameters"', '{"arguments"'];
  const firstMarkerIndex = markers
    .map(marker => normalized.indexOf(marker))
    .filter(index => index >= 0)
    .sort((a, b) => a - b)[0];

  if (firstMarkerIndex === undefined) {
    return text;
  }

  return text.slice(0, firstMarkerIndex).trimEnd();
}

async function fetchStreamWithCookie(input: RequestInfo | URL, init: RequestInit): Promise<Response> {
  const requestInit: RequestInit = {
    ...init,
    credentials: 'include',
  };
  const response = await fetch(input, requestInit);
  if (response.status !== 401) {
    return response;
  }

  try {
    await api.post('/api/auth/refresh');
  } catch {
    return response;
  }

  return fetch(input, requestInit);
}

export function useChatStream(
  onChunkReceived: (chunk: ChunkData, context: ChatStreamContext) => void,
  onActivityEvent?: (event: AgentActivityEvent, context: ChatStreamContext) => void,
  onResumedRunCompleted?: (response: string, context: ChatStreamContext) => void | Promise<void>
) {
  const [generatingChatIds, setGeneratingChatIds] = useState<Set<number>>(new Set());
  const abortControllersRef = useRef<Map<string, {
    controller: AbortController;
    chatId: number | null;
    runId?: string;
  }>>(new Map());
  const resumingChatIdsRef = useRef<Set<number>>(new Set());

  const startStream = useCallback((chatId: number | null, runId?: string): ChatStreamContext => {
    const requestId = `stream_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
    const controller = new AbortController();
    abortControllersRef.current.set(requestId, { controller, chatId, runId });
    if (chatId !== null) {
      setGeneratingChatIds(previous => {
        const next = new Set(previous);
        next.add(chatId);
        return next;
      });
    }
    return { chatId, requestId, runId };
  }, []);

  const finishStream = useCallback((context: ChatStreamContext) => {
    abortControllersRef.current.delete(context.requestId);
    if (context.chatId === null) return;
    const hasAnotherStreamForChat = Array.from(abortControllersRef.current.values())
      .some(stream => stream.chatId === context.chatId);
    if (!hasAnotherStreamForChat) {
      setGeneratingChatIds(previous => {
        const next = new Set(previous);
        next.delete(context.chatId as number);
        return next;
      });
    }
  }, []);

  const readStreamingResponse = useCallback(async (
    response: Response,
    startTime: number,
    context: ChatStreamContext,
    initialResponse = ''
  ): Promise<string> => {
    if (!response.body) throw new Error('ReadableStream not yet supported in this browser.');

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    // confirmedTokens soma o eval_count real (do Ollama) de cada rodada já
    // concluída; estimatedRoundTokens conta chunks recebidos na rodada em
    // andamento só pra o número ir se movendo durante o streaming. Contar
    // chunk como se fosse token (o que existia antes) não tem relação real
    // com tokens — um chunk pode conter zero, um ou vários.
    let confirmedTokens = 0;
    let estimatedRoundTokens = 0;
    let fullResponse = initialResponse;
    let fullThinking = '';
    let insideThink = false;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.trim() === '') continue;
        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim();
          if (dataStr === '[DONE]') continue;
          try {
            const json = JSON.parse(dataStr);
            if (json.avento_event) {
              if (json.avento_event.type === 'agent.tokens.usage' && typeof json.avento_event.evalCount === 'number') {
                confirmedTokens += json.avento_event.evalCount;
                estimatedRoundTokens = 0;
                const currentDuration = ((Date.now() - startTime) / 1000).toFixed(1);
                onChunkReceived({
                  content: fullResponse,
                  thinking: fullThinking,
                  newText: '',
                  duration: currentDuration,
                  tokens: confirmedTokens,
                  isFinal: false
                }, context);
              }
              onActivityEvent?.(json.avento_event, context);
              continue;
            }

            const choice = json.choices && json.choices[0] ? json.choices[0] : null;
            const msg = choice ? choice.delta : null;

            if (msg && msg.content) {
              // --- Thinking block parser ---
              // Qwen3 emits <think>...</think> as regular content chunks.
              // We strip those tags and route the text to fullThinking.
              let remaining = msg.content;
              let visibleChunk = '';

              while (remaining.length > 0) {
                if (insideThink) {
                  const closeIdx = remaining.indexOf('</think>');
                  if (closeIdx === -1) {
                    fullThinking += remaining;
                    remaining = '';
                  } else {
                    fullThinking += remaining.slice(0, closeIdx);
                    remaining = remaining.slice(closeIdx + '</think>'.length);
                    insideThink = false;
                  }
                } else {
                  const openIdx = remaining.indexOf('<think>');
                  if (openIdx === -1) {
                    visibleChunk += remaining;
                    remaining = '';
                  } else {
                    visibleChunk += remaining.slice(0, openIdx);
                    remaining = remaining.slice(openIdx + '<think>'.length);
                    insideThink = true;
                  }
                }
              }
              // --- End thinking block parser ---

              const nextResponse = fullResponse + visibleChunk;
              const sanitizedResponse = stripToolMarkup(nextResponse);
              if (looksLikeToolMarkup(visibleChunk) || sanitizedResponse.length < nextResponse.length) {
                fullResponse = sanitizedResponse;
                const currentDuration = ((Date.now() - startTime) / 1000).toFixed(1);
                onChunkReceived({
                  content: fullResponse,
                  thinking: fullThinking,
                  newText: '',
                  duration: currentDuration,
                  tokens: confirmedTokens + estimatedRoundTokens,
                  isFinal: false
                }, context);
                continue;
              }

              if (visibleChunk) estimatedRoundTokens++;
              fullResponse = nextResponse;
              const currentDuration = ((Date.now() - startTime) / 1000).toFixed(1);

              onChunkReceived({
                content: fullResponse,
                thinking: fullThinking,
                newText: visibleChunk,
                duration: currentDuration,
                tokens: confirmedTokens + estimatedRoundTokens,
                isFinal: false
              }, context);
            }
          } catch (e) {
            console.error('Error parsing stream chunk', dataStr, e);
          }
        }
      }
    }

    const durationSecs = ((Date.now() - startTime) / 1000).toFixed(1);
    onChunkReceived({
      content: fullResponse,
      thinking: fullThinking,
      newText: "",
      duration: durationSecs,
      // Se por algum motivo o evento agent.tokens.usage não chegou pra
      // alguma rodada (ex.: modelo antigo do Ollama sem eval_count), cai de
      // volta pra estimativa em vez de mostrar 0.
      tokens: confirmedTokens > 0 ? confirmedTokens : confirmedTokens + estimatedRoundTokens,
      isFinal: true
    }, context);

    return fullResponse;
  }, [onActivityEvent, onChunkReceived]);


  const sendMessage = useCallback(async (
    messageContext: MessageContext[],
    // Vazio deixa o backend resolver com o default configurável
    // (avento.agent.default-model) — nenhum nome de modelo fixo aqui.
    model: string = "",
    workspaceRoots: string[] = [],
    imageModel: string = "",
    imageOptions: ImageGenerationOptions = {
      qualityPreset: 'balanced',
      aspectRatio: 'square',
      subjectType: 'auto',
      subjectCount: 0,
      enhancePrompt: true,
      refinementEnabled: true,
      refinementStrength: 0.3,
      detailMode: 'face',
      cfgScale: 6,
      referenceStrength: 0.65,
      referenceMode: 'composition',
      structureControl: 'depth',
      structureStrength: 0.75,
      poseStrength: 0.75,
      adherenceValidationEnabled: true,
      maxAdherenceRetries: 1,
    },
    chatId: number | null = null
  ): Promise<string | undefined> => {
    const context = startStream(chatId);
    const abortController = abortControllersRef.current.get(context.requestId)!.controller;
    const startTime = Date.now();
    let fullResponse = "";

    try {
      const reqBody = {
        model: model.trim(),
        imageModel,
        imageOptions,
        messages: messageContext,
        workspaceRoots,
        chatId,
      };

      const { data: submission } = await api.post<{ runId: string; status: string }>('/api/ai/runs', reqBody);
      context.runId = submission.runId;
      const activeStream = abortControllersRef.current.get(context.requestId);
      if (activeStream) {
        activeStream.runId = submission.runId;
      }

      const response = await fetchStreamWithCookie(`/api/ai/runs/${submission.runId}/events`, {
        method: 'GET',
        signal: abortController.signal
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`Erro do Servidor: ${response.status} ${errText}`);
      }

      fullResponse = await readStreamingResponse(response, startTime, context);
      finishStream(context);
      return fullResponse;
    } catch (error: any) {
      if (error?.name === 'AbortError') {
        finishStream(context);
        return undefined;
      }

      console.error('Error in chat stream:', error);
      onChunkReceived({
        content: fullResponse + `\n\n> ❌ **Erro:** ${error.message}`,
        thinking: '',
        newText: "",
        duration: "0.0",
        tokens: 0,
        isFinal: true
      }, context);
      finishStream(context);
    }
  }, [finishStream, readStreamingResponse, onChunkReceived, startStream]);

  const sendApproval = useCallback(async (
    approvalId: string,
    decision: 'approve' | 'reject',
    comment = '',
    chatId: number | null = null
  ): Promise<string | undefined> => {
    const context = startStream(chatId);
    const abortController = abortControllersRef.current.get(context.requestId)!.controller;
    const startTime = Date.now();
    let fullResponse = "";

    try {
      const response = await fetchStreamWithCookie(`/api/ai/approvals/${approvalId}/${decision}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ comment }),
        signal: abortController.signal
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`Erro do Servidor: ${response.status} ${errText}`);
      }

      fullResponse = await readStreamingResponse(response, startTime, context);
      finishStream(context);
      return fullResponse;
    } catch (error: any) {
      if (error?.name === 'AbortError') {
        finishStream(context);
        return undefined;
      }

      console.error('Error in approval stream:', error);
      onChunkReceived({
        content: `\n\n> ❌ **Erro:** ${error.message}`,
        thinking: '',
        newText: "",
        duration: "0.0",
        tokens: 0,
        isFinal: true
      }, context);
      finishStream(context);
    }
  }, [finishStream, readStreamingResponse, onChunkReceived, startStream]);

  const resumeChat = useCallback(async (chatId: number): Promise<boolean> => {
    const existing = Array.from(abortControllersRef.current.values())
      .some(stream => stream.chatId === chatId);
    if (existing || resumingChatIdsRef.current.has(chatId)) {
      setGeneratingChatIds(previous => new Set(previous).add(chatId));
      return true;
    }

    resumingChatIdsRef.current.add(chatId);
    try {
      const { data } = await api.get<ActiveAgentRun | undefined>(`/api/ai/runs/active/${chatId}`);
      if (!data?.runId) {
        return false;
      }

      const streamStartedWhileLoading = Array.from(abortControllersRef.current.values())
        .some(stream => stream.chatId === chatId);
      if (streamStartedWhileLoading) {
        setGeneratingChatIds(previous => new Set(previous).add(chatId));
        return true;
      }

      const context = startStream(chatId, data.runId);
      context.resumed = true;
      const abortController = abortControllersRef.current.get(context.requestId)!.controller;

      void (async () => {
        try {
          const response = await fetchStreamWithCookie(`/api/ai/runs/${data.runId}/events`, {
            method: 'GET',
            signal: abortController.signal,
          });
          if (!response.ok) {
            throw new Error(`Erro do Servidor: ${response.status} ${await response.text()}`);
          }
          const fullResponse = await readStreamingResponse(response, Date.now(), context);
          if (fullResponse.trim()) {
            await onResumedRunCompleted?.(fullResponse, context);
          }
        } catch (error: any) {
          if (error?.name !== 'AbortError') {
            console.error('Error resuming chat stream:', error);
            onChunkReceived({
              content: `\n\n> **Erro ao retomar:** ${error.message}`,
              thinking: '',
              newText: '',
              duration: '0.0',
              tokens: 0,
              isFinal: true,
            }, context);
          }
        } finally {
          finishStream(context);
        }
      })();
      return true;
    } catch (error: any) {
      if (error?.response?.status !== 404) {
        console.error('Could not restore active run for chat', chatId, error);
      }
      return false;
    } finally {
      resumingChatIdsRef.current.delete(chatId);
    }
  }, [finishStream, onChunkReceived, onResumedRunCompleted, readStreamingResponse, startStream]);

  const abortGeneration = useCallback((chatId?: number | null) => {
    abortControllersRef.current.forEach(stream => {
      if (chatId === undefined || stream.chatId === chatId) {
        stream.controller.abort();
        if (stream.runId) {
          api.post(`/api/ai/runs/${stream.runId}/cancel`).catch(error => {
            console.error('Could not cancel backend run', stream.runId, error);
          });
        }
      }
    });
  }, []);

  useEffect(() => () => {
    abortControllersRef.current.forEach(stream => stream.controller.abort());
    abortControllersRef.current.clear();
  }, []);

  return {
    sendMessage,
    sendApproval,
    isGenerating: generatingChatIds.size > 0,
    generatingChatIds,
    abortGeneration,
    resumeChat,
  };
}
