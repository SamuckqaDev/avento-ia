import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from 'styled-components';
import { afterEach, describe, expect, it } from 'vitest';
import { lightTheme } from '../../../styles/theme';
import { UiPreviewCard } from './index';

const MOCKUP = `<!doctype html>
<html lang="pt-BR">
  <head><title>Login Avento</title></head>
  <body><main><h1>Entrar</h1><button>Continuar</button></main></body>
</html>`;

function renderPreview() {
  return render(
    <ThemeProvider theme={lightTheme}>
      <UiPreviewCard html={MOCKUP} />
    </ThemeProvider>,
  );
}

afterEach(cleanup);

describe('UiPreviewCard', () => {
  it('renders a collapsed thumbnail and expands it inside the chat', async () => {
    const user = userEvent.setup();
    renderPreview();

    const thumbnail = screen.getByRole('button', { name: 'Abrir miniatura: Login Avento' });
    expect(thumbnail).toBeTruthy();
    expect(screen.getByTitle('Login Avento em Desktop 1440 px')).toBeTruthy();

    await user.click(thumbnail);

    expect(screen.queryByRole('button', { name: 'Abrir miniatura: Login Avento' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Recolher mockup' })).toBeTruthy();
  });

  it('opens and closes the expanded preview modal', async () => {
    const user = userEvent.setup();
    renderPreview();

    await user.click(screen.getByRole('button', { name: 'Expandir prévia' }));
    expect(screen.getByRole('dialog', { name: 'Prévia expandida: Login Avento' })).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Fechar prévia' }));
    expect(screen.queryByRole('dialog', { name: 'Prévia expandida: Login Avento' })).toBeNull();
  });
});
