import React from 'react';
import { X, Lightning, Robot, ShieldCheck, FileText, Broom, Check } from '@phosphor-icons/react';
import { ModalBackdrop, Modal } from './styles';
import styled from 'styled-components';

export interface TaskTemplate {
  id: string;
  name: string;
  category: string;
  cronExpression: string;
  prompt: string;
  description: string;
  icon: 'lightning' | 'robot' | 'shield' | 'file' | 'broom';
}

export const TEMPLATES_CATALOG: TaskTemplate[] = [
  {
    id: 'backup_daily',
    name: '⚡ Backup Diário de Banco & Arquivos',
    category: 'Infraestrutura',
    cronExpression: '57 3 * * *',
    prompt: 'Execute a rotina de backup do banco de dados e arquivos principais do projeto. Compacte os dados em formato tar.gz, verifique a integridade do arquivo gerado e grave um diagnóstico de status.',
    description: 'Executa toda madrugada às 03:57 AM para garantir backups seguros e íntegros.',
    icon: 'lightning'
  },
  {
    id: 'healthcheck_api',
    name: '🩺 Healthcheck & Auto-Recuperação de API',
    category: 'Monitoramento',
    cronExpression: '*/5 * * * *',
    prompt: 'Verifique a saúde da API REST local (ex: http://localhost:8080/actuator/health ou porta principal). Se a API responder com falha ou timeout, analise a causa raiz, reinicie a aplicação via terminal e envie o diagnóstico de auto-recuperação.',
    description: 'Monitora o serviço a cada 5 minutos e reinicia autonomamente em caso de queda.',
    icon: 'robot'
  },
  {
    id: 'security_audit',
    name: '🛡️ Auditoria Noturna de Segurança & Dependências',
    category: 'Segurança',
    cronExpression: '0 2 * * *',
    prompt: 'Analise o arquivo de dependências do projeto (package.json ou pom.xml). Identifique pacotes desatualizados ou com vulnerabilidades conhecidas de segurança e proponha a lista de atualizações recomendadas.',
    description: 'Roda às 02:00 AM para manter as bibliotecas do projeto protegidas e atualizadas.',
    icon: 'shield'
  },
  {
    id: 'daily_release_notes',
    name: '📝 Resumo Diário de Commits & Release Notes',
    category: 'Desenvolvimento',
    cronExpression: '0 18 * * 1-5',
    prompt: 'Execute git log para listar os commits realizados hoje no repositório. Resuma em linguagem clara todas as melhorias e correções feitas pela equipe e gere uma nota de atualização amigável.',
    description: 'Gera um resumo diário às 18:00 PM nos dias úteis com o progresso do projeto.',
    icon: 'file'
  },
  {
    id: 'clean_tmp_logs',
    name: '🧹 Limpeza Autônoma de Cache & Logs Temporários',
    category: 'Manutenção',
    cronExpression: '0 0 * * 0',
    prompt: 'Inspecione os diretórios de logs, pastas temporárias e cache do projeto. Remova arquivos temporários com mais de 7 dias de criação e exiba a quantidade de espaço em disco liberado.',
    description: 'Roda todos os domingos à meia-noite para liberar espaço e otimizar o sistema.',
    icon: 'broom'
  }
];

const CatalogGrid = styled.div`
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
`;

const TemplateCardBox = styled.div`
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 90%, ${({ theme }) => theme.colors.bg});
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  transition: all 0.2s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    transform: translateY(-1px);
  }

  .template-info {
    display: flex;
    flex-direction: column;
    gap: 4px;
    flex: 1;

    .template-header-row {
      display: flex;
      align-items: center;
      gap: 8px;

      h4 {
        font-size: 0.92rem;
        font-weight: 700;
        color: ${({ theme }) => theme.colors.text};
      }

      .category-tag {
        font-size: 0.68rem;
        font-weight: 600;
        padding: 2px 7px;
        border-radius: 4px;
        background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 15%, transparent);
        color: ${({ theme }) => theme.colors.accent};
      }
    }

    p {
      font-size: 0.8rem;
      color: ${({ theme }) => theme.colors.textMuted};
    }

    .cron-preview {
      font-size: 0.72rem;
      color: ${({ theme }) => theme.colors.textMuted};
      code {
        font-family: monospace;
        color: ${({ theme }) => theme.colors.primary};
      }
    }
  }

  button {
    background: ${({ theme }) => theme.colors.primary};
    color: #ffffff;
    border: none;
    height: 38px;
    padding: 0 14px;
    border-radius: 8px;
    font-size: 0.875rem;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    white-space: nowrap;
    box-sizing: border-box;
    transition: all 0.15s ease;

    &:hover {
      opacity: 0.92;
      transform: translateY(-1px);
    }
  }
`;

interface TemplatesModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectTemplate: (template: TaskTemplate) => void;
}

export const TemplatesModal: React.FC<TemplatesModalProps> = ({ isOpen, onClose, onSelectTemplate }) => {
  if (!isOpen) return null;

  const renderIcon = (icon: string) => {
    switch (icon) {
      case 'lightning': return <Lightning size={20} color="var(--primary)" />;
      case 'robot': return <Robot size={20} color="var(--accent)" />;
      case 'shield': return <ShieldCheck size={20} color="#10B981" />;
      case 'file': return <FileText size={20} color="#F59E0B" />;
      case 'broom': return <Broom size={20} color="#8B5CF6" />;
      default: return <Robot size={20} />;
    }
  };

  return (
    <ModalBackdrop onClick={onClose}>
      <Modal onClick={e => e.stopPropagation()} style={{ maxWidth: 580 }}>
        <div className="modal-header">
          <h2>📦 Galeria de Templates Prontos de Automação</h2>
          <button type="button" onClick={onClose}><X size={20} /></button>
        </div>

        <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)', marginBottom: 4 }}>
          Escolha um modelo pronto para agendar em 1 clique sem precisar escrever prompts do zero:
        </p>

        <CatalogGrid>
          {TEMPLATES_CATALOG.map(tpl => (
            <TemplateCardBox key={tpl.id}>
              <div className="template-info">
                <div className="template-header-row">
                  {renderIcon(tpl.icon)}
                  <h4>{tpl.name}</h4>
                  <span className="category-tag">{tpl.category}</span>
                </div>
                <p>{tpl.description}</p>
                <div className="cron-preview">
                  Cron: <code>{tpl.cronExpression}</code>
                </div>
              </div>

              <button type="button" onClick={() => { onSelectTemplate(tpl); onClose(); }}>
                <Check size={14} /> Usar Template
              </button>
            </TemplateCardBox>
          ))}
        </CatalogGrid>
      </Modal>
    </ModalBackdrop>
  );
};
