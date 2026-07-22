import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from 'styled-components';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { lightTheme } from '../../../styles/theme';
import { ImplPlanCard } from './index';

const PLAN = `## Objetivo
Corrigir o fluxo de autenticação.

## Passos
1. Revisar o filtro JWT.
2. Atualizar os testes.`;

afterEach(() => {
  cleanup();
});

describe('ImplPlanCard', () => {
  it('opens the persisted draft without approving it', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    const onApprove = vi.fn();
    window.addEventListener('avento:open-plan', onOpen);
    window.addEventListener('avento:approve-plan', onApprove);

    render(
      <ThemeProvider theme={lightTheme}>
        <ImplPlanCard plan={PLAN} planId={42} messageIndex={3} />
      </ThemeProvider>,
    );

    expect(screen.getByText('2 etapas planejadas')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /Plano de implementação/i }));

    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onApprove).not.toHaveBeenCalled();
    window.removeEventListener('avento:open-plan', onOpen);
    window.removeEventListener('avento:approve-plan', onApprove);
  });

  it('emits approval only from the approval button', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    const onApprove = vi.fn();
    window.addEventListener('avento:open-plan', onOpen);
    window.addEventListener('avento:approve-plan', onApprove);

    render(
      <ThemeProvider theme={lightTheme}>
        <ImplPlanCard plan={PLAN} planId={42} messageIndex={3} />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Aprovar e executar' }));

    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onOpen).not.toHaveBeenCalled();
    window.removeEventListener('avento:open-plan', onOpen);
    window.removeEventListener('avento:approve-plan', onApprove);
  });
});
