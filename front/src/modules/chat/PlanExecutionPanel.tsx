import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowClockwise,
  Check,
  CheckCircle,
  Circle,
  Clock,
  FloppyDisk,
  Pause,
  PencilSimple,
  Play,
  Robot,
  Stop,
  WarningCircle,
  X,
} from '@phosphor-icons/react';
import { apiErrorMessage } from '../../services/apiClient';
import { planApi, type PlanResponse, type TaskResponse, type TaskUpdateRequest } from '../../services/planApi';
import {
  EditForm,
  EmptyState,
  ErrorBanner,
  FileList,
  Header,
  IconButton,
  Panel,
  PrimaryButton,
  ProposalNotice,
  TaskAgent,
  TaskActions,
  TaskCard,
  TaskHeader,
  TaskList,
  TaskResult,
  Toolbar,
} from './PlanExecutionPanel.styles';

interface PlanExecutionPanelProps {
  chatId?: number;
  workspaceRoots: string[];
  focusPlanId?: number;
  proposalPreview?: string;
  onClose?: () => void;
}

const LIVE_EVENTS = [
  'plan.running',
  'plan.paused',
  'plan.cancelled',
  'plan.completed',
  'plan.failed',
  'plan.task.running',
  'plan.task.completed',
  'plan.task.retrying',
  'plan.task.failed',
  'plan.approval.required',
];

export function PlanExecutionPanel({
  chatId,
  focusPlanId,
  proposalPreview,
  onClose,
}: PlanExecutionPanelProps) {
  const [plans, setPlans] = useState<PlanResponse[]>([]);
  const [selectedPlanId, setSelectedPlanId] = useState<number>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const plan = useMemo(
    () => selectedPlanId === undefined
      ? plans[0]
      : plans.find(candidate => candidate.id === selectedPlanId),
    [plans, selectedPlanId],
  );

  const loadPlans = useCallback(async (preferredPlanId?: number) => {
    try {
      const allPlans = await planApi.listPlans();
      const loaded = chatId ? allPlans.filter(candidate => candidate.chatId === chatId) : [];
      setPlans(loaded);
      setSelectedPlanId(current => {
        if (preferredPlanId && loaded.some(candidate => candidate.id === preferredPlanId)) {
          return preferredPlanId;
        }
        return loaded.some(candidate => candidate.id === current) ? current : loaded[0]?.id;
      });
      setError(undefined);
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, [chatId]);

  useEffect(() => {
    if (!focusPlanId) return;
    setSelectedPlanId(focusPlanId);
    void loadPlans(focusPlanId);
  }, [focusPlanId, loadPlans]);

  useEffect(() => {
    void loadPlans();
    const interval = window.setInterval(() => void loadPlans(), 3000);
    return () => window.clearInterval(interval);
  }, [loadPlans]);

  useEffect(() => {
    if (!plan || plan.status !== 'RUNNING') return;
    const source = new EventSource(`/api/plans/${plan.id}/stream`, { withCredentials: true });
    const refresh = () => void loadPlans();
    LIVE_EVENTS.forEach(type => source.addEventListener(type, refresh));
    source.onerror = () => source.close();
    return () => {
      LIVE_EVENTS.forEach(type => source.removeEventListener(type, refresh));
      source.close();
    };
  }, [loadPlans, plan?.id, plan?.status]);

  const perform = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    try {
      await action();
      await loadPlans();
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setBusy(false);
    }
  };

  const startOrResume = () => {
    if (!plan) return;
    void perform(() => plan.status === 'PAUSED' ? planApi.resumePlan(plan.id) : planApi.runPlan(plan.id));
  };

  const approve = (taskId: number) => {
    if (!plan) return;
    void perform(async () => {
      await planApi.approveTask(plan.id, taskId);
      await planApi.resumePlan(plan.id);
    });
  };

  const saveTask = (taskId: number, update: TaskUpdateRequest) => {
    if (!plan) return Promise.resolve();
    return perform(() => planApi.updateTask(plan.id, taskId, update));
  };

  const editable = plan?.status === 'DRAFT' || plan?.status === 'PAUSED';

  return (
    <Panel aria-label="Plano de execução">
      <Header>
        <div>
          <h2>Plano de execução</h2>
          <p>{plan ? displayPlanGoal(plan.goal) : 'Nenhum plano selecionado'}</p>
        </div>
        <IconButton type="button" onClick={() => void loadPlans()} disabled={busy} title="Atualizar">
          <ArrowClockwise size={17} />
        </IconButton>
        {onClose && (
          <IconButton type="button" onClick={onClose} title="Fechar">
            <X size={17} />
          </IconButton>
        )}
      </Header>

      {plans.length > 0 && (
        <Toolbar>
          <select
            value={plan?.id || ''}
            onChange={event => setSelectedPlanId(Number(event.target.value))}
            aria-label="Plano selecionado"
          >
            {plans.map(candidate => (
              <option key={candidate.id} value={candidate.id}>
                {displayPlanGoal(candidate.goal)} · {candidate.status}
              </option>
            ))}
          </select>
          {plan?.status === 'DRAFT' && (
            <PrimaryButton type="button" onClick={startOrResume} disabled={busy}>
              <Check size={17} weight="bold" />
              Aprovar e executar
            </PrimaryButton>
          )}
          {plan?.status === 'PAUSED' && (
            <PrimaryButton type="button" onClick={startOrResume} disabled={busy}>
              <Play size={17} weight="fill" />
              Retomar
            </PrimaryButton>
          )}
          {plan?.status === 'RUNNING' && (
            <IconButton type="button" onClick={() => void perform(() => planApi.pausePlan(plan.id))} disabled={busy} title="Pausar">
              <Pause size={17} weight="fill" />
            </IconButton>
          )}
          {plan && !['DONE', 'CANCELLED'].includes(plan.status) && (
            <IconButton $danger type="button" onClick={() => void perform(() => planApi.cancelPlan(plan.id))} disabled={busy} title="Cancelar">
              <Stop size={17} weight="fill" />
            </IconButton>
          )}
        </Toolbar>
      )}

      {error && <ErrorBanner role="alert">{error}</ErrorBanner>}

      {loading && plans.length === 0 ? (
        <EmptyState>Carregando...</EmptyState>
      ) : !plan ? (
        proposalPreview ? <ProposalPreview plan={proposalPreview} /> : (
          <EmptyState>
            Nenhum plano ainda neste chat.<br />
            Peça algo que precise de vários passos no chat — ex.: <em>"cria o componente botão"</em> — e o
            Avento monta os passos aqui, executando um de cada vez.
          </EmptyState>
        )
      ) : (
        <TaskList>
          {plan.tasks.map(task => (
            <TaskItem
              key={task.id}
              task={task}
              editable={editable}
              busy={busy}
              onApprove={() => approve(task.id)}
              onSave={update => saveTask(task.id, update)}
            />
          ))}
        </TaskList>
      )}
    </Panel>
  );
}

function TaskItem({
  task,
  editable,
  busy,
  onApprove,
  onSave,
}: {
  task: TaskResponse;
  editable: boolean;
  busy: boolean;
  onApprove: () => void;
  onSave: (update: TaskUpdateRequest) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(task.title);
  const [details, setDetails] = useState(task.details);
  const [orderIndex, setOrderIndex] = useState(task.orderIndex);
  const files = parseTargetFiles(task.targetFiles);

  useEffect(() => {
    if (editing) return;
    setTitle(task.title);
    setDetails(task.details);
    setOrderIndex(task.orderIndex);
  }, [editing, task.details, task.orderIndex, task.title]);

  const icon = task.status === 'DONE'
    ? <CheckCircle size={19} weight="fill" color="#16a34a" />
    : task.status === 'RUNNING'
      ? <Play size={19} weight="fill" color="#2563eb" />
      : task.status === 'FAILED'
        ? <WarningCircle size={19} weight="fill" color="#dc2626" />
        : task.status === 'BLOCKED'
          ? <Pause size={19} weight="fill" color="#d97706" />
          : task.status === 'SKIPPED'
            ? <Circle size={19} color="#6b7280" />
            : <Clock size={19} color="#6b7280" />;

  const save = async () => {
    await onSave({
      title: title.trim(),
      details: details.trim(),
      needsApproval: task.needsApproval,
      orderIndex,
      skipped: task.status === 'SKIPPED',
    });
    setEditing(false);
  };

  return (
    <TaskCard $active={task.status === 'RUNNING'} $failed={task.status === 'FAILED'}>
      <TaskHeader>
        {icon}
        <div>
          <strong>{task.orderIndex}. {task.title}</strong>
          <p>{task.details}</p>
        </div>
      </TaskHeader>

      {files.length > 0 && (
        <FileList>{files.map(file => <code key={file} title={file}>{file}</code>)}</FileList>
      )}
      <TaskAgent>
        <Robot size={14} />
        Agente: {task.assignedAgentName || 'Avento padrão'}
        {task.agentRationale && <span>{task.agentRationale}</span>}
      </TaskAgent>
      {task.resultSummary && <TaskResult>{task.resultSummary}</TaskResult>}

      {editing && (
        <EditForm>
          <input value={title} onChange={event => setTitle(event.target.value)} maxLength={160} />
          <textarea value={details} onChange={event => setDetails(event.target.value)} maxLength={4000} />
          <label>
            Ordem
            <input
              type="number"
              min={1}
              max={20}
              value={orderIndex}
              onChange={event => setOrderIndex(Number(event.target.value))}
            />
          </label>
        </EditForm>
      )}

      <TaskActions>
        {task.status === 'BLOCKED' && task.needsApproval && (
          <button type="button" onClick={onApprove} disabled={busy}>
            <Check size={15} /> Aprovar
          </button>
        )}
        {editable && !editing && (
          <button type="button" onClick={() => setEditing(true)} disabled={busy}>
            <PencilSimple size={15} /> Editar
          </button>
        )}
        {editing && (
          <button
            type="button"
            onClick={() => void save()}
            disabled={busy || !title.trim() || !details.trim() || orderIndex < 1 || orderIndex > 20}
          >
            <FloppyDisk size={15} /> Salvar
          </button>
        )}
        {editable && (
          <button
            type="button"
            disabled={busy}
            onClick={() => void onSave({
              title: task.title,
              details: task.details,
              needsApproval: task.needsApproval,
              orderIndex: task.orderIndex,
              skipped: task.status !== 'SKIPPED',
            })}
          >
            {task.status === 'SKIPPED' ? 'Restaurar' : 'Pular'}
          </button>
        )}
      </TaskActions>
    </TaskCard>
  );
}

function ProposalPreview({ plan }: { plan: string }) {
  const steps = parseProposalSteps(plan);
  return (
    <TaskList>
      <ProposalNotice>
        O plano está sendo sincronizado. Revisar os detalhes não inicia nenhuma execução.
      </ProposalNotice>
      {steps.map((step, index) => (
        <TaskCard key={`${index}-${step}`} $active={false} $failed={false}>
          <TaskHeader>
            <Clock size={19} color="#6b7280" />
            <div>
              <strong>{index + 1}. {step}</strong>
              <p>Agente previsto: Avento padrão</p>
            </div>
          </TaskHeader>
        </TaskCard>
      ))}
    </TaskList>
  );
}

function parseProposalSteps(plan: string): string[] {
  const section = plan.match(/##\s*Passos\s*\n([\s\S]*?)(?=\n##\s|$)/i)?.[1] || plan;
  const steps = section
    .split('\n')
    .map(line => line.match(/^\s*\d+[.)]\s+(.+)$/)?.[1]?.trim())
    .filter((value): value is string => Boolean(value));
  return steps.length > 0 ? steps : ['Revisar o plano detalhado gerado no chat.'];
}

function displayPlanGoal(goal: string): string {
  const objective = goal.match(/^([\s\S]*?)(?=\n\nPlano proposto para execução:|$)/i)?.[1]?.trim();
  return objective || goal;
}

function parseTargetFiles(raw?: string): string[] {
  if (!raw?.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter((value): value is string => typeof value === 'string' && Boolean(value.trim()));
    }
  } catch {
    // Planos legados podem ter salvo um único caminho como texto simples.
  }
  return raw.split(',').map(value => value.trim()).filter(Boolean);
}
