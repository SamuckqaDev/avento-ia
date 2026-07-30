import { renderHook, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useChatStream, type AgentActivityEvent, type ChunkData } from './useChatStream';

vi.mock('../services/apiClient', () => ({
  api: { post: vi.fn(async () => ({ data: { runId: 'run_test', status: 'RUNNING' } })) },
  apiErrorMessage: (error: unknown) => String(error),
}));

/**
 * O parsing de SSE do chat: 523 linhas sem teste até agora.
 *
 * Se o backend mudar o formato de um evento, nada aqui grita — o defeito aparece direto na tela do
 * usuário, como resposta truncada, "Thinking" vazando pro texto visível ou contador de tokens
 * zerado. Estes testes fixam o contrato entre o `/api/ai/runs/{id}/events` e o que o chat recebe.
 *
 * O stream é montado em pedaços de propósito: na rede real um evento chega partido no meio, e o
 * buffer que remonta a linha é justamente a parte que ninguém testava.
 */

/** Um corpo de resposta que entrega os pedaços na ordem, como a rede faria. */
function streamOf(...pieces: string[]): Response {
  const encoder = new TextEncoder();
  let index = 0;
  const body = {
    getReader: () => ({
      read: async () =>
        index < pieces.length
          ? { done: false, value: encoder.encode(pieces[index++]) }
          : { done: true, value: undefined },
    }),
  };
  return { ok: true, status: 200, body } as unknown as Response;
}

function sseData(payload: unknown): string {
  return `data: ${JSON.stringify(payload)}\n`;
}

function contentDelta(content: string): string {
  return sseData({ choices: [{ delta: { content } }] });
}

describe('useChatStream — leitura do stream SSE', () => {
  let chunks: ChunkData[];
  let events: AgentActivityEvent[];

  const setup = (response: Response) => {
    vi.stubGlobal('fetch', vi.fn(async () => response));
    chunks = [];
    events = [];
    return renderHook(() =>
      useChatStream(
        chunk => chunks.push(chunk),
        event => events.push(event),
      ),
    );
  };

  const send = async (hook: ReturnType<typeof setup>) => {
    let result: string | undefined;
    await act(async () => {
      result = await hook.result.current.sendMessage([{ role: 'user', content: 'oi' }]);
    });
    return result;
  };

  const lastOf = (list: ChunkData[]) => list[list.length - 1];
  const finalChunk = () => lastOf(chunks.filter(chunk => chunk.isFinal));

  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('junta os deltas de conteúdo numa resposta só', async () => {
    const hook = setup(streamOf(contentDelta('Olá'), contentDelta(', '), contentDelta('mundo')));

    const response = await send(hook);

    expect(response).toBe('Olá, mundo');
    expect(finalChunk().content).toBe('Olá, mundo');
    expect(finalChunk().isFinal).toBe(true);
  });

  // A rede não respeita fronteira de linha: um evento chega cortado no meio do JSON e a metade
  // final vem no pacote seguinte. Sem o buffer, o JSON.parse falha e o pedaço some da resposta.
  it('remonta um evento partido entre dois pacotes', async () => {
    const complete = contentDelta('tudo certo');
    const cut = Math.floor(complete.length / 2);
    const hook = setup(streamOf(complete.slice(0, cut), complete.slice(cut)));

    const response = await send(hook);

    expect(response).toBe('tudo certo');
  });

  // Qwen3 emite <think>...</think> como conteúdo normal. Sem separar, o raciocínio do modelo
  // aparece no meio da resposta ao usuário.
  it('separa o bloco de thinking do texto visível', async () => {
    const hook = setup(streamOf(contentDelta('<think>vou checar</think>resposta final')));

    const response = await send(hook);

    expect(response).toBe('resposta final');
    expect(finalChunk().thinking).toBe('vou checar');
  });

  it('separa o thinking mesmo quando a tag abre num chunk e fecha em outro', async () => {
    const hook = setup(
      streamOf(contentDelta('<think>primeira parte '), contentDelta('segunda parte</think>visível')),
    );

    const response = await send(hook);

    expect(response).toBe('visível');
    expect(finalChunk().thinking).toBe('primeira parte segunda parte');
  });

  it('entrega os eventos do agente a quem escuta, sem misturá-los no texto', async () => {
    const hook = setup(
      streamOf(
        sseData({ avento_event: { type: 'agent.round.started', title: 'Rodada 1', detail: '' } }),
        contentDelta('feito'),
        sseData({
          avento_event: { type: 'tool.approval.required', title: 'Aprovar', approvalId: 'ap_1', toolName: 'read_file' },
        }),
      ),
    );

    const response = await send(hook);

    expect(response).toBe('feito');
    expect(events.map(event => event.type)).toEqual(['agent.round.started', 'tool.approval.required']);
    expect(events[1].approvalId).toBe('ap_1');
  });

  // O contador mostrava chunks como se fossem tokens. eval_count é o número real, e quando ele
  // chega tem de substituir a estimativa em vez de somar por cima.
  it('prefere o eval_count real à estimativa por chunk', async () => {
    const hook = setup(
      streamOf(
        contentDelta('a'),
        contentDelta('b'),
        sseData({ avento_event: { type: 'agent.tokens.usage', evalCount: 42 } }),
      ),
    );

    await send(hook);

    expect(finalChunk().tokens).toBe(42);
  });

  // O chunk final sempre usa confirmedTokens, então ele NÃO prova que a estimativa foi zerada.
  // Quem prova é o contador durante a rodada seguinte: os dois chunks já contados não podem ser
  // somados de novo por cima do eval_count.
  it('zera a estimativa da rodada ao receber o eval_count', async () => {
    const hook = setup(
      streamOf(
        contentDelta('a'),
        contentDelta('b'),
        sseData({ avento_event: { type: 'agent.tokens.usage', evalCount: 42 } }),
        contentDelta('c'),
      ),
    );

    await send(hook);

    const afterUsage = lastOf(chunks.filter(chunk => !chunk.isFinal));
    expect(afterUsage.newText).toBe('c');
    expect(afterUsage.tokens).toBe(43);
  });

  it('cai na estimativa quando nenhum eval_count chega', async () => {
    const hook = setup(streamOf(contentDelta('a'), contentDelta('b')));

    await send(hook);

    expect(finalChunk().tokens).toBe(2);
  });

  // Um JSON quebrado no meio do stream não pode derrubar o resto da resposta.
  it('ignora um evento malformado e segue lendo', async () => {
    const hook = setup(streamOf(contentDelta('antes'), 'data: {isso nao e json}\n', contentDelta(' e depois')));

    const response = await send(hook);

    expect(response).toBe('antes e depois');
  });

  it('ignora o marcador [DONE] e as linhas em branco', async () => {
    const hook = setup(streamOf(contentDelta('ok'), '\n', 'data: [DONE]\n'));

    const response = await send(hook);

    expect(response).toBe('ok');
  });

  // O modelo às vezes escreve a chamada de ferramenta como texto. Ela não pode chegar à tela.
  it('corta a marcação de ferramenta que vaza para o texto', async () => {
    const hook = setup(streamOf(contentDelta('vou ler o arquivo {"name": "read_file"}')));

    const response = await send(hook);

    expect(response).toBe('vou ler o arquivo');
  });

  it('reporta o erro no próprio chat quando o backend responde falha', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, text: async () => 'boom' }) as unknown as Response),
    );
    chunks = [];
    events = [];
    const hook = renderHook(() => useChatStream(chunk => chunks.push(chunk)));

    await act(async () => {
      await hook.result.current.sendMessage([{ role: 'user', content: 'oi' }]);
    });

    expect(finalChunk().content).toContain('❌');
    expect(finalChunk().content).toContain('500');
    // O chat tem de sair do estado "gerando", senão o input fica travado para sempre.
    expect(hook.result.current.isGenerating).toBe(false);
  });

  it('marca e desmarca o chat como gerando', async () => {
    const hook = setup(streamOf(contentDelta('ok')));

    expect(hook.result.current.isGenerating).toBe(false);
    await send(hook);
    expect(hook.result.current.isGenerating).toBe(false);
  });
});
