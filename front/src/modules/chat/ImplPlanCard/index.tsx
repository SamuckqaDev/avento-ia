import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ClipboardText, Check, PencilSimple } from '@phosphor-icons/react';
import { Card, Header, Content, Actions, ApproveButton, AdjustButton } from './styles';

/**
 * Renderiza um bloco ```impl-plan``` como um card de plano de implementação com aprovação —
 * o modo "plan-before-execute". "Aprovar e executar" dispara um evento que o Home escuta para
 * enviar a mensagem de execução; "Ajustar" foca o campo para o usuário refinar.
 */
export interface ImplPlanEventDetail {
  plan: string;
  messageIndex: number;
}

export function ImplPlanCard({ plan, messageIndex }: ImplPlanEventDetail) {
  const [submitted, setSubmitted] = useState(false);

  const approve = () => {
    if (submitted) return;
    setSubmitted(true);
    window.dispatchEvent(new CustomEvent<ImplPlanEventDetail>('avento:approve-plan', {
      detail: { plan, messageIndex },
    }));
  };

  return (
    <Card>
      <Header>
        <ClipboardText size={18} weight="fill" />
        <span>Plano de implementação</span>
      </Header>
      <Content>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{plan}</ReactMarkdown>
      </Content>
      <Actions>
        <ApproveButton
          type="button"
          onClick={approve}
          disabled={submitted}
        >
          <Check size={16} weight="bold" /> {submitted ? 'Plano enviado' : 'Aprovar e executar'}
        </ApproveButton>
        <AdjustButton
          type="button"
          onClick={() => window.dispatchEvent(new CustomEvent<ImplPlanEventDetail>(
            'avento:adjust-plan', { detail: { plan, messageIndex } }
          ))}
          disabled={submitted}
        >
          <PencilSimple size={16} /> Ajustar
        </AdjustButton>
      </Actions>
    </Card>
  );
}
