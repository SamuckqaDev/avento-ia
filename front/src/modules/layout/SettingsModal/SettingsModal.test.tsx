import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from 'styled-components';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { lightTheme } from '../../../styles/theme';

vi.mock('../../../services/apiClient', () => ({
  api: { get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

const authState = vi.hoisted(() => ({
  user: { email: 'root@avento.local', hasAvatar: false },
}));

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: authState.user, logout: vi.fn() }),
}));

import { api } from '../../../services/apiClient';
import { SettingsModal } from './index';

const SAVED_PROVIDER = {
  providerKind: 'OPENAI_COMPATIBLE',
  baseUrl: 'http://192.168.15.6:11434',
  selectedModel: 'qwen3.5:35b',
  visionModel: 'qwen3.5:35b',
  imageModel: '',
  plannerModel: 'qwen3.5:9b',
  apiKeyMasked: '',
};

beforeEach(() => {
  vi.mocked(api.get).mockImplementation((url: string) => {
    if (url === '/api/version') return Promise.resolve({ data: { version: '2.0.0' } });
    if (url === '/api/ai/providers') return Promise.resolve({ data: SAVED_PROVIDER });
    if (url.startsWith('/api/ai/providers/models')) return Promise.resolve({ data: { data: [] } });
    return Promise.resolve({ data: {} });
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  authState.user = { email: 'root@avento.local', hasAvatar: false };
});

function openProviderTab() {
  return render(
    <ThemeProvider theme={lightTheme}>
      <SettingsModal
        onClose={() => {}}
        isDarkMode={false}
        toggleTheme={() => {}}
        isVoiceEnabled={false}
        handleToggleVoice={() => {}}
      />
    </ThemeProvider>,
  );
}

describe('Aba de provedores com configuração já salva', () => {
  it('mostra o resumo do que está valendo em vez do formulário', async () => {
    const user = userEvent.setup();
    openProviderTab();
    await user.click(screen.getByText('Modelos & Provedores'));

    // Cada papel aparece com o modelo que o cumpre — era isso que faltava para saber o que
    // estava configurado sem reabrir e reler os campos.
    expect(screen.getByText('Planejamento')).toBeTruthy();
    expect(screen.getByText('qwen3.5:9b')).toBeTruthy();
    // Sem escolha para gerar imagem, o resumo diz de onde vem o valor em vez de ficar em branco.
    expect(screen.getByText('padrão do sistema')).toBeTruthy();

    // O formulário fica guardado: nada de "Salvar provedor" na cara de quem já salvou.
    expect(screen.queryByText('Salvar provedor')).toBeNull();
  });

  it('devolve o formulário quando se pede para editar', async () => {
    const user = userEvent.setup();
    openProviderTab();
    await user.click(screen.getByText('Modelos & Provedores'));

    await waitFor(() => expect(screen.getByText('Editar conexão')).toBeTruthy());
    await user.click(screen.getByText('Editar conexão'));

    expect(screen.getByText('Salvar provedor')).toBeTruthy();
    expect(screen.getByText('Testar conexão')).toBeTruthy();
  });
});

describe('Avatar de perfil', () => {
  it('usa a rota do avatar armazenado no servidor', () => {
    authState.user = { email: 'root@avento.local', hasAvatar: true };
    openProviderTab();

    expect(screen.getByAltText('Avatar').getAttribute('src')).toBe('/api/auth/me/avatar');
  });
});

describe('Versão da aplicação', () => {
  it('renders the version returned by the backend', async () => {
    openProviderTab();

    expect(await screen.findByText('Versão 2.0.0')).toBeTruthy();
  });

  it('renders a neutral dash when the version request fails', async () => {
    vi.mocked(api.get).mockImplementationOnce(() => Promise.reject(new Error('Unavailable')));
    openProviderTab();

    expect(await screen.findByText('Versão —')).toBeTruthy();
  });
});
