import { useState, useEffect, useCallback, useMemo } from 'react';
import { api } from '../../services/apiClient';
import { 
  Container, Header, CreateButton, Grid, Card, 
  ModalBackdrop, Modal, TabNavigation, CalendarWrapper,
  CalendarHeader, CalendarGrid, DayCell, EventBadge
} from './styles';
import { Plus, Clock, Play, Pause, Trash, Warning, Calendar as CalendarIcon, X, CaretLeft, CaretRight, Robot } from '@phosphor-icons/react';

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

type FrequencyMode = 'daily' | 'interval' | 'specific_date' | 'cron';

export function CoworkView() {
  const [activeTab, setActiveTab] = useState<'calendar' | 'automations'>('calendar');
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);

  // Calendar date state
  const [currentDate, setCurrentDate] = useState(new Date());

  // Form state
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [prompt, setPrompt] = useState('');
  const [projectPath, setProjectPath] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Friendly Date/Time Picker State
  const [freqMode, setFreqMode] = useState<FrequencyMode>('daily');
  const [selectedTime, setSelectedTime] = useState('03:57'); // HH:mm
  const [intervalHours, setIntervalHours] = useState('1'); // 1h, 2h, 6h, etc
  const [specificDate, setSpecificDate] = useState(() => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow.toISOString().split('T')[0]; // YYYY-MM-DD
  });
  const [customCron, setCustomCron] = useState('57 3 * * *');

  // Compute Cron automatically from DatePicker & TimePicker selections
  const computedCron = useMemo(() => {
    if (freqMode === 'daily') {
      const [h, m] = selectedTime.split(':').map(n => parseInt(n, 10) || 0);
      return `${m} ${h} * * *`;
    }
    if (freqMode === 'interval') {
      const h = Math.max(1, Math.min(24, parseInt(intervalHours, 10) || 1));
      return h === 1 ? '0 * * * *' : `0 */${h} * * *`;
    }
    if (freqMode === 'specific_date') {
      if (!specificDate) return '57 3 * * *';
      const [, month, d] = specificDate.split('-').map(n => parseInt(n, 10));
      const [h, m] = selectedTime.split(':').map(n => parseInt(n, 10) || 0);
      return `${m} ${h} ${d} ${month} *`;
    }
    return customCron;
  }, [freqMode, selectedTime, intervalHours, specificDate, customCron]);

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
        cronExpression: computedCron,
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

  // Calendar logic
  const monthYearLabel = useMemo(() => {
    return currentDate.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  }, [currentDate]);

  const calendarDays = useMemo(() => {
    const year = currentDate.getFullYear();
    const month = currentDate.getMonth();
    
    const firstDayOfMonth = new Date(year, month, 1);
    const lastDayOfMonth = new Date(year, month + 1, 0);
    
    const startingDayOfWeek = firstDayOfMonth.getDay();
    const totalDays = lastDayOfMonth.getDate();
    
    const today = new Date();
    const days: { date: Date; dayNum: number; isCurrentMonth: boolean; isToday: boolean }[] = [];

    for (let i = startingDayOfWeek - 1; i >= 0; i--) {
      const prevDate = new Date(year, month, -i);
      days.push({ date: prevDate, dayNum: prevDate.getDate(), isCurrentMonth: false, isToday: false });
    }

    for (let day = 1; day <= totalDays; day++) {
      const date = new Date(year, month, day);
      const isToday = today.getFullYear() === year && today.getMonth() === month && today.getDate() === day;
      days.push({ date, dayNum: day, isCurrentMonth: true, isToday });
    }

    const remaining = (7 - (days.length % 7)) % 7;
    for (let i = 1; i <= remaining; i++) {
      const nextDate = new Date(year, month + 1, i);
      days.push({ date: nextDate, dayNum: i, isCurrentMonth: false, isToday: false });
    }

    return days;
  }, [currentDate]);

  return (
    <Container>
      <Header>
        <div className="title-group">
          <h1><CalendarIcon size={26} color="var(--primary)" /> Avento Cowork & Agenda</h1>
          <p>Super Agente de IA: Calendário de atividades, lembretes e automações autônomas com diagnóstico de auto-recuperação.</p>
        </div>

        <div className="header-actions">
          <TabNavigation>
            <button 
              type="button" 
              className={activeTab === 'calendar' ? 'active' : ''} 
              onClick={() => setActiveTab('calendar')}
            >
              <CalendarIcon size={16} /> Calendário & Agenda
            </button>
            <button 
              type="button" 
              className={activeTab === 'automations' ? 'active' : ''} 
              onClick={() => setActiveTab('automations')}
            >
              <Robot size={16} /> Automações ({tasks.length})
            </button>
          </TabNavigation>

          <CreateButton onClick={() => setIsModalOpen(true)}>
            <Plus size={18} /> Nova Atividade / Automação
          </CreateButton>
        </div>
      </Header>

      {activeTab === 'calendar' ? (
        <CalendarWrapper>
          <CalendarHeader>
            <h2 style={{ textTransform: 'capitalize' }}>{monthYearLabel}</h2>
            <div className="nav-controls">
              <button type="button" onClick={() => setCurrentDate(prev => new Date(prev.getFullYear(), prev.getMonth() - 1, 1))} title="Mês anterior">
                <CaretLeft size={16} /> Anterior
              </button>
              <button type="button" onClick={() => setCurrentDate(new Date())} title="Ir para Hoje">
                Hoje
              </button>
              <button type="button" onClick={() => setCurrentDate(prev => new Date(prev.getFullYear(), prev.getMonth() + 1, 1))} title="Próximo mês">
                Próximo <CaretRight size={16} />
              </button>
            </div>
          </CalendarHeader>

          <CalendarGrid>
            {['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'].map(day => (
              <div key={day} className="weekday">{day}</div>
            ))}

            {calendarDays.map((cell, idx) => {
              const dayTasks = tasks.filter(task => {
                if (!task.nextRunAt) return false;
                const nextDate = new Date(task.nextRunAt);
                return (
                  nextDate.getFullYear() === cell.date.getFullYear() &&
                  nextDate.getMonth() === cell.date.getMonth() &&
                  nextDate.getDate() === cell.date.getDate()
                );
              });

              return (
                <DayCell 
                  key={idx} 
                  $isToday={cell.isToday} 
                  $isCurrentMonth={cell.isCurrentMonth}
                >
                  <div className="day-number">{cell.dayNum}</div>
                  <div className="events-list">
                    {dayTasks.map(t => (
                      <EventBadge 
                        key={t.id} 
                        className={t.lastRunStatus === 'FAILED' ? 'warning' : 'automation'}
                        title={`${t.name} (Próxima: ${new Date(t.nextRunAt!).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })})`}
                      >
                        {t.lastRunStatus === 'FAILED' ? '⚠️ ' : '🤖 '}{t.name}
                      </EventBadge>
                    ))}
                  </div>
                </DayCell>
              );
            })}
          </CalendarGrid>
        </CalendarWrapper>
      ) : (
        <>
          {isLoading && tasks.length === 0 ? (
            <p style={{ color: 'var(--text-muted)' }}>Carregando automações agendadas...</p>
          ) : tasks.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '60px 20px', background: 'var(--surface)', borderRadius: 12, border: '1px dashed var(--border)' }}>
              <Clock size={48} color="var(--accent)" style={{ marginBottom: 16, opacity: 0.8 }} />
              <h3 style={{ fontSize: '1.1rem', marginBottom: 8 }}>Nenhuma tarefa agendada na agenda</h3>
              <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem', maxWidth: 460, margin: '0 auto 20px' }}>
                Crie rotinas diárias como backups de projetos (ex: 03:57 AM), checagem de saúde do sistema ou limpezas automáticas.
              </p>
              <CreateButton onClick={() => setIsModalOpen(true)}>
                <Plus size={18} /> Criar Primeira Atividade
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
                      <strong>Instrução de IA:</strong> {task.prompt}
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
        </>
      )}

      {isModalOpen && (
        <ModalBackdrop onClick={() => setIsModalOpen(false)}>
          <Modal onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Agendar Nova Atividade / Automação</h2>
              <button type="button" onClick={() => setIsModalOpen(false)}><X size={20} /></button>
            </div>

            <form onSubmit={handleCreateTask} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="form-group">
                <label>Nome da Atividade *</label>
                <input 
                  type="text" 
                  placeholder="Ex: Backup Diário das 03:57 AM" 
                  value={name} 
                  onChange={e => setName(e.target.value)} 
                  required 
                />
              </div>

              {/* Intuitive Date & Time Selection Picker */}
              <div className="form-group">
                <label>Frequência / Agendamento *</label>
                <select 
                  value={freqMode} 
                  onChange={e => setFreqMode(e.target.value as FrequencyMode)}
                  style={{ marginBottom: 8 }}
                >
                  <option value="daily">🔄 Diariamente no mesmo horário</option>
                  <option value="interval">⏱ Repetir a cada N horas</option>
                  <option value="specific_date">📅 Em uma data e hora específica</option>
                  <option value="cron">⚙️ Expressão Cron Manual (Avançado)</option>
                </select>

                {freqMode === 'daily' && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <label style={{ fontSize: '0.85rem' }}>Horário:</label>
                    <input 
                      type="time" 
                      value={selectedTime} 
                      onChange={e => setSelectedTime(e.target.value)} 
                      required 
                    />
                    <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      (Converte para Cron: <code>{computedCron}</code>)
                    </span>
                  </div>
                )}

                {freqMode === 'interval' && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <label style={{ fontSize: '0.85rem' }}>Repetir a cada:</label>
                    <select value={intervalHours} onChange={e => setIntervalHours(e.target.value)} style={{ width: 120 }}>
                      <option value="1">1 Hora</option>
                      <option value="2">2 Horas</option>
                      <option value="4">4 Horas</option>
                      <option value="6">6 Horas</option>
                      <option value="12">12 Horas</option>
                    </select>
                    <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      (Converte para Cron: <code>{computedCron}</code>)
                    </span>
                  </div>
                )}

                {freqMode === 'specific_date' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    <div style={{ display: 'flex', gap: 12 }}>
                      <div style={{ flex: 1 }}>
                        <label style={{ fontSize: '0.8rem' }}>Data:</label>
                        <input 
                          type="date" 
                          value={specificDate} 
                          onChange={e => setSpecificDate(e.target.value)} 
                          required 
                        />
                      </div>
                      <div style={{ flex: 1 }}>
                        <label style={{ fontSize: '0.8rem' }}>Horário:</label>
                        <input 
                          type="time" 
                          value={selectedTime} 
                          onChange={e => setSelectedTime(e.target.value)} 
                          required 
                        />
                      </div>
                    </div>
                    <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      (Converte para Cron: <code>{computedCron}</code>)
                    </span>
                  </div>
                )}

                {freqMode === 'cron' && (
                  <div>
                    <input 
                      type="text" 
                      placeholder="Ex: 57 3 * * *" 
                      value={customCron} 
                      onChange={e => setCustomCron(e.target.value)} 
                      required 
                    />
                  </div>
                )}
              </div>

              <div className="form-group">
                <label>Instrução / Prompt para o Agente de IA *</label>
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
                  style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--text)', padding: '10px 16px', borderRadius: 8, cursor: 'pointer' }}
                >
                  Cancelar
                </button>
                <CreateButton type="submit" disabled={isSubmitting}>
                  {isSubmitting ? 'Agendando...' : 'Salvar e Ativar Atividade'}
                </CreateButton>
              </div>
            </form>
          </Modal>
        </ModalBackdrop>
      )}
    </Container>
  );
}
