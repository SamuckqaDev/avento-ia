import { useState, useEffect, useCallback } from 'react';
import { api } from '../../services/apiClient';
import { 
  Container, Header, CreateButton, Grid, Card, 
  ModalBackdrop, Modal 
} from './styles';
import { Plus, Clock, Play, Pause, Trash, Warning, Calendar, X } from '@phosphor-icons/react';

export interface ScheduledTask {
  id: number;
  name: string;
  description: string;
  cronExpression: string;
  prompt: string;
  chatId?: number;
  projectPath?: string;
  status: 'ACTIVE' | 'PAUSED';
  lastRunStatus: 'IDLE' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  lastRunAt?: string;
  nextRunAt?: string;
  lastRunError?: string;
  lastRunDiagnosis?: string;
  createdAt: string;
}

export function CoworkView() {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);

  // Form state
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [cronExpression, setCronExpression] = useState('57 3 * * *');
  const [prompt, setPrompt] = useState('');
  const [projectPath, setProjectPath] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const fetchTasks = useCallback(async () => {
    try {
      setIsLoading(true);
      const { data } = await api.get<ScheduledTask[]>('/api/scheduled-tasks');
      setTasks(data);
    } catch (e) {
      console.error('Erro ao carregar tarefas agendadas', e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchTasks();
    const interval = setInterval(fetchTasks, 15000);
    return () => clearInterval(interval);
  }, [fetchTasks]);

  const handleCreateTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !prompt.trim()) return;

    try {
      setIsSubmitting(true);
      await api.post('/api/scheduled-tasks', {
        name,
        description,
        cronExpression,
        prompt,
        projectPath
      });
      setIsModalOpen(false);
      setName('');
      setDescription('');
      setPrompt('');
      void fetchTasks();
    } catch (e) {
      console.error('Erro ao criar tarefa agendada', e);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleToggleTask = async (id: number) => {
    try {
      await api.post(`/api/scheduled-tasks/${id}/toggle`);
      void fetchTasks();
    } catch (e) {
      console.error('Erro ao alterar status da tarefa', e);
    }
  };

  const handleRunNow = async (id: number) => {
    try {
      await api.post(`/api/scheduled-tasks/${id}/run-now`);
      void fetchTasks();
    } catch (e) {
      console.error('Erro ao disparar tarefa manualmente', e);
    }
  };

  const handleDeleteTask = async (id: number) => {
    if (!confirm('Deseja realmente excluir esta tarefa da agenda?')) return;
    try {
      await api.delete(`/api/scheduled-tasks/${id}`);
      void fetchTasks();
    } catch (e) {
      console.error('Erro ao excluir tarefa agendada', e);
    }
  };

  return (
    <Container>
      <Header>
        <div className="title-group">
          <h1><Calendar size={26} color="#10b981" /> Avento Cowork (Agenda & Automações)</h1>
          <p>Gerencie rotinas repetitivas que o Avento executa autonomamente com diagnósticos de auto-recuperação.</p>
        </div>
        <CreateButton onClick={() => setIsModalOpen(true)}>
          <Plus size={18} /> Nova Automação Agendada
        </CreateButton>
      </Header>

      {isLoading && tasks.length === 0 ? (
        <p style={{ color: '#8e9b95' }}>Carregando agenda de automações...</p>
      ) : tasks.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '60px 20px', background: 'rgba(255,255,255,0.02)', borderRadius: 12, border: '1px dashed rgba(255,255,255,0.1)' }}>
          <Clock size={48} color="#10b981" style={{ marginBottom: 16, opacity: 0.8 }} />
          <h3 style={{ fontSize: '1.1rem', marginBottom: 8 }}>Nenhuma tarefa agendada ainda</h3>
          <p style={{ color: '#8e9b95', fontSize: '0.9rem', maxWidth: 460, margin: '0 auto 20px' }}>
            Crie rotinas diárias como backups de projetos, checagem de saúde do sistema ou limpezas automáticas.
          </p>
          <CreateButton onClick={() => setIsModalOpen(true)}>
            <Plus size={18} /> Criar Primeira Automação
          </CreateButton>
        </div>
      ) : (
        <Grid>
          {tasks.map(task => (
            <Card key={task.id} $status={task.status}>
              <div className="card-header">
                <h3>{task.name}</h3>
                <span className={`badge ${task.status.toLowerCase()}`}>
                  {task.status === 'ACTIVE' ? 'Ativa' : 'Pausada'}
                </span>
              </div>

              <div className="card-body">
                <span className="cron-pill">
                  <Clock size={14} /> Cron: {task.cronExpression}
                </span>
                {task.description && <p>{task.description}</p>}
                <div className="prompt-snippet" title={task.prompt}>
                  <strong>Instrução:</strong> {task.prompt}
                </div>
              </div>

              {task.lastRunDiagnosis && (
                <div className="diagnosis-box">
                  <strong><Warning size={14} /> Diagnóstico de Auto-Recuperação:</strong>
                  <span>{task.lastRunDiagnosis}</span>
                </div>
              )}

              <div className="card-footer">
                <div className="next-run">
                  Próxima rodada: {task.nextRunAt ? new Date(task.nextRunAt).toLocaleString('pt-BR') : 'Agendada'}
                </div>

                <div className="actions">
                  <button type="button" className="run-now" onClick={() => handleRunNow(task.id)} title="Rodar Agora">
                    <Play size={14} /> Rodar Agora
                  </button>
                  <button type="button" onClick={() => handleToggleTask(task.id)} title={task.status === 'ACTIVE' ? 'Pausar' : 'Ativar'}>
                    {task.status === 'ACTIVE' ? <Pause size={14} /> : <Play size={14} />}
                  </button>
                  <button type="button" className="delete" onClick={() => handleDeleteTask(task.id)} title="Excluir">
                    <Trash size={14} />
                  </button>
                </div>
              </div>
            </Card>
          ))}
        </Grid>
      )}

      {isModalOpen && (
        <ModalBackdrop onClick={() => setIsModalOpen(false)}>
          <Modal onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Agendar Nova Tarefa Autônoma</h2>
              <button type="button" onClick={() => setIsModalOpen(false)}><X size={20} /></button>
            </div>

            <form onSubmit={handleCreateTask} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="form-group">
                <label>Nome da Tarefa *</label>
                <input 
                  type="text" 
                  placeholder="Ex: Backup Diário das 03:57 AM" 
                  value={name} 
                  onChange={e => setName(e.target.value)} 
                  required 
                />
              </div>

              <div className="form-group">
                <label>Expressão Cron (ou Horário) *</label>
                <input 
                  type="text" 
                  placeholder="Ex: 57 3 * * * (Todos os dias às 03:57 AM)" 
                  value={cronExpression} 
                  onChange={e => setCronExpression(e.target.value)} 
                  required 
                />
              </div>

              <div className="form-group">
                <label>Instrução / Prompt para a IA *</label>
                <textarea 
                  rows={4} 
                  placeholder="Ex: Execute o script de backup no terminal, compacte os arquivos da pasta e verifique se o arquivo final tem tamanho maior que zero." 
                  value={prompt} 
                  onChange={e => setPrompt(e.target.value)} 
                  required 
                />
              </div>

              <div className="form-group">
                <label>Caminho do Projeto (Opcional)</label>
                <input 
                  type="text" 
                  placeholder="Ex: /Users/sr.tomimatu/projetcs/avento-ia" 
                  value={projectPath} 
                  onChange={e => setProjectPath(e.target.value)} 
                />
              </div>

              <div className="modal-footer">
                <button 
                  type="button" 
                  onClick={() => setIsModalOpen(false)}
                  style={{ background: 'transparent', border: '1px solid rgba(255,255,255,0.1)', color: '#fff', padding: '10px 16px', borderRadius: 8, cursor: 'pointer' }}
                >
                  Cancelar
                </button>
                <CreateButton type="submit" disabled={isSubmitting}>
                  {isSubmitting ? 'Agendando...' : 'Salvar e Ativar Tarefa'}
                </CreateButton>
              </div>
            </form>
          </Modal>
        </ModalBackdrop>
      )}
    </Container>
  );
}
