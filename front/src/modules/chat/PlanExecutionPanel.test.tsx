import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from 'styled-components';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { planApi, type PlanResponse } from '../../services/planApi';
import { lightTheme } from '../../styles/theme';
import { PlanExecutionPanel } from './PlanExecutionPanel';

vi.mock('../../services/planApi', () => ({
  planApi: {
    listPlans: vi.fn(),
    createPlan: vi.fn(),
    updateTask: vi.fn(),
    approveTask: vi.fn(),
    runPlan: vi.fn(),
    pausePlan: vi.fn(),
    resumePlan: vi.fn(),
    cancelPlan: vi.fn(),
  },
}));

const currentPlan: PlanResponse = {
  id: 1,
  chatId: 7,
  goal: 'Corrigir autenticação',
  status: 'DRAFT',
  tasks: [{
    id: 10,
    orderIndex: 1,
    title: 'Revisar filtro JWT',
    details: 'Validar o cookie e os testes.',
    status: 'PENDING',
    needsApproval: false,
    targetFiles: '["back/AuthFilter.java"]',
  }],
};

beforeEach(() => {
  vi.mocked(planApi.listPlans).mockResolvedValue([
    currentPlan,
    { ...currentPlan, id: 2, chatId: 99, goal: 'Plano de outro chat' },
  ]);
  vi.mocked(planApi.updateTask).mockResolvedValue(currentPlan.tasks[0]);
  vi.mocked(planApi.runPlan).mockResolvedValue({ ...currentPlan, status: 'RUNNING' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('PlanExecutionPanel', () => {
  it('keeps plans scoped to the current chat and sends an edited order', async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={lightTheme}>
        <PlanExecutionPanel chatId={7} workspaceRoots={['/tmp/project']} />
      </ThemeProvider>,
    );

    expect(await screen.findByRole('option', { name: /Corrigir autenticação/ })).toBeTruthy();
    expect(screen.queryByText('Plano de outro chat')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Editar' }));
    const order = screen.getByRole('spinbutton', { name: 'Ordem' });
    await user.clear(order);
    await user.type(order, '2');
    await user.click(screen.getByRole('button', { name: 'Salvar' }));

    await waitFor(() => expect(planApi.updateTask).toHaveBeenCalledWith(1, 10, {
      title: 'Revisar filtro JWT',
      details: 'Validar o cookie e os testes.',
      needsApproval: false,
      orderIndex: 2,
      skipped: false,
    }));
  });

  it('does not execute a draft until the user explicitly approves it', async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={lightTheme}>
        <PlanExecutionPanel
          chatId={7}
          focusPlanId={1}
          proposalPreview="## Passos\n1. Revisar o filtro JWT"
          workspaceRoots={['/tmp/project']}
        />
      </ThemeProvider>,
    );

    expect(await screen.findByText(/Revisar filtro JWT/)).toBeTruthy();
    expect(planApi.runPlan).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Aprovar e executar' }));

    await waitFor(() => expect(planApi.runPlan).toHaveBeenCalledWith(1));
  });
});
