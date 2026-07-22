import { useEffect, useMemo, useState, type MouseEvent } from 'react';
import { CaretRight, ClipboardText, Check, PencilSimple } from '@phosphor-icons/react';
import { Card, PreviewButton, Header, Content, Actions, ApproveButton, AdjustButton, Status } from './styles';

/**
 * Renderiza um bloco ```impl-plan``` como um card de plano de implementação com aprovação —
 * o modo "plan-before-execute". "Aprovar e executar" dispara um evento que o Home escuta para
 * enviar a mensagem de execução; "Ajustar" foca o campo para o usuário refinar.
 */
export interface ImplPlanEventDetail {
  plan: string;
  planId?: number;
  messageIndex: number;
}

interface ApprovalResultDetail {
  messageIndex?: number;
  planId?: number;
  success?: boolean;
}

function summarizePlan(plan: string): { objective: string; steps: number } {
  const objective = plan.match(/##\s*Objetivo\s*\n([\s\S]*?)(?=\n##\s|$)/i)?.[1]?.trim()
    || plan.split('\n').find(line => line.trim() && !line.trim().startsWith('#'))?.trim()
    || 'Revise os detalhes antes de iniciar a execução.';
  const stepsSection = plan.match(/##\s*Passos\s*\n([\s\S]*?)(?=\n##\s|$)/i)?.[1] || plan;
  const steps = (stepsSection.match(/^\s*\d+[.)]\s+/gm) || []).length;
  return { objective, steps };
}

export function ImplPlanCard({ plan, planId, messageIndex }: ImplPlanEventDetail) {
  const [submitted, setSubmitted] = useState(false);
  const summary = useMemo(() => summarizePlan(plan), [plan]);

  useEffect(() => {
    const handleResult = (event: Event) => {
      const detail = (event as CustomEvent<ApprovalResultDetail>).detail;
      if (detail?.messageIndex !== messageIndex) return;
      setSubmitted(Boolean(detail.success));
    };
    window.addEventListener('avento:plan-approval-result', handleResult);
    return () => window.removeEventListener('avento:plan-approval-result', handleResult);
  }, [messageIndex]);

  const open = () => {
    window.dispatchEvent(new CustomEvent<ImplPlanEventDetail>('avento:open-plan', {
      detail: { plan, planId, messageIndex },
    }));
  };

  const approve = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (submitted) return;
    setSubmitted(true);
    window.dispatchEvent(new CustomEvent<ImplPlanEventDetail>('avento:approve-plan', {
      detail: { plan, planId, messageIndex },
    }));
  };

  return (
    <Card>
      <PreviewButton type="button" onClick={open} aria-label="Abrir plano de implementação">
        <Header>
          <ClipboardText size={18} weight="fill" />
          <span>Plano de implementação</span>
          <Status $ready={Boolean(planId)}>
            {submitted ? 'Execução iniciada' : planId ? 'Aguardando aprovação' : 'Preparando detalhes'}
          </Status>
        </Header>
        <Content>
          <strong>{summary.objective}</strong>
          <span>{summary.steps > 0 ? `${summary.steps} etapas planejadas` : 'Plano detalhado disponível'}</span>
          <span className="open-hint">Abrir detalhes <CaretRight size={15} /></span>
        </Content>
      </PreviewButton>
      <Actions>
        <ApproveButton
          type="button"
          onClick={approve}
          disabled={submitted}
        >
          <Check size={16} weight="bold" /> {submitted ? 'Execução iniciada' : 'Aprovar e executar'}
        </ApproveButton>
        <AdjustButton
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            window.dispatchEvent(new CustomEvent<ImplPlanEventDetail>(
              'avento:adjust-plan', { detail: { plan, planId, messageIndex } }
            ));
          }}
          disabled={submitted}
        >
          <PencilSimple size={16} /> Ajustar
        </AdjustButton>
      </Actions>
    </Card>
  );
}
