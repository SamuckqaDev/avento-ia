import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from 'styled-components';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { lightTheme } from '../../../styles/theme';
import { ImplPlanCard, type ImplPlanEventDetail } from './index';

afterEach(cleanup);

describe('ImplPlanCard', () => {
  it('dispatches the exact selected plan only once', async () => {
    const user = userEvent.setup();
    const listener = vi.fn<(event: Event) => void>();
    window.addEventListener('avento:approve-plan', listener);
    render(
      <ThemeProvider theme={lightTheme}>
        <ImplPlanCard plan="1. Corrigir autenticação" messageIndex={7} />
      </ThemeProvider>,
    );

    const button = screen.getByRole('button', { name: 'Aprovar e executar' });
    await user.dblClick(button);

    expect(listener).toHaveBeenCalledTimes(1);
    const detail = (listener.mock.calls[0][0] as CustomEvent<ImplPlanEventDetail>).detail;
    expect(detail).toEqual({ plan: '1. Corrigir autenticação', messageIndex: 7 });
    expect(screen.getByRole('button', { name: 'Plano enviado' }).hasAttribute('disabled')).toBe(true);
    window.removeEventListener('avento:approve-plan', listener);
  });
});
