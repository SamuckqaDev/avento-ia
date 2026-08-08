import { cleanup, render, screen } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { afterEach, describe, expect, it } from 'vitest';
import { lightTheme } from '../../../styles/theme';
import { MessageBubble } from './index';

// Só um plano, sem nenhuma chamada de ferramenta — o caso que deixava a bolha girando para sempre.
const PLAN_ONLY = ['```plan', 'Buscar a cotação das moedas', 'Montar a tabela', '```'].join('\n');

function renderBubble(content: string, isStreaming: boolean) {
  return render(
    <ThemeProvider theme={lightTheme}>
      <MessageBubble
        message={{ role: 'assistant', content }}
        messageIndex={0}
        isStreaming={isStreaming}
      />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
});

describe('MessageBubble com resposta que só tem plano', () => {
  it('mostra os passos e para de girar quando o run terminou', () => {
    renderBubble(PLAN_ONLY, false);

    expect(screen.getByText('O agente parou no plano')).toBeTruthy();
    expect(screen.getByText('Buscar a cotação das moedas')).toBeTruthy();
    expect(screen.getByText('Montar a tabela')).toBeTruthy();
    expect(document.querySelector('[data-testid="typing-indicator"]')).toBeNull();
  });

  it('continua girando enquanto a resposta está sendo transmitida', () => {
    renderBubble(PLAN_ONLY, true);

    // Durante o streaming o plano ainda pode ser seguido de conteúdo: girar está certo aqui.
    expect(screen.queryByText('O agente parou no plano')).toBeNull();
  });

  it('avisa quando o modelo terminou sem gerar nada', () => {
    renderBubble('', false);

    expect(screen.getByText('Resposta vazia')).toBeTruthy();
  });
});
