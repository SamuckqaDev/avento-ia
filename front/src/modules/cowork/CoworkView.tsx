import { useState, useEffect, useCallback, useMemo } from 'react';
import { api } from '../../services/apiClient';
import { 
  Container, Header, CreateButton, Grid, Card, 
  ModalBackdrop, Modal, TabNavigation, CalendarWrapper,
  CalendarHeader, CalendarGrid, DayCell, EventBadge,
  FrequencyGrid, FrequencyOptionButton, SubInputPanel
} from './styles';
import { Plus, Clock, Play, Pause, Trash, Warning, Calendar as CalendarIcon, X, CaretLeft, CaretRight, Robot, ArrowsClockwise, Timer, Gear, CheckCircle, XCircle, FileText, Eye, Folder, FolderOpen } from '@phosphor-icons/react';

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
  lastRunOutput?: string;
  createdAt: string;
}

export interface ScheduledTaskRun {
  id: number;
  taskId: number;
  status: 'SUCCESS' | 'FAILED';
  prompt?: string;
  output?: string;
  error?: string;
  createdAt: string;
}

type FrequencyMode = 'daily' | 'interval' | 'specific_date' | 'cron';

export function CoworkView() {
  const [activeTab, setActiveTab] = useState<'calendar' | 'automations'>('calendar');
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);
  const [selectedOutputTask, setSelectedOutputTask] = useState<ScheduledTask | null>(null);
  const [selectedLogTask, setSelectedLogTask] = useState<ScheduledTask | null>(null);
  const [taskRuns, setTaskRuns] = useState<ScheduledTaskRun[]>([]);
  const [isLoadingRuns, setIsLoadingRuns] = useState<boolean>(false);

  const handleViewLogs = async (task: ScheduledTask) => {
    setSelectedLogTask(task);
    setIsLoadingRuns(true);
    try {
      const response = await api.get(`/api/scheduled-tasks/${task.id}/runs`);
      setTaskRuns(response.data);
    } catch (err) {
      console.error("Erro ao carregar histórico de execuções", err);
    } finally {
      setIsLoadingRuns(false);
    }
  };

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
      if (intervalHours === '1min') return '* * * * *';
      if (intervalHours === '5min') return '*/5 * * * *';
      if (intervalHours === '15min') return '*/15 * * * *';
      if (intervalHours === '30min') return '*/30 * * * *';
      if (intervalHours === '1h') return '0 * * * *';
      if (intervalHours === '2h') return '0 */2 * * *';
      if (intervalHours === '6h') return '0 */6 * * *';
      if (intervalHours === '12h') return '0 */12 * * *';
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
                        {t.lastRunStatus === 'FAILED' ? (
                          <Warning size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
                        ) : (
                          <Robot size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
                        )}
                        {t.name}
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
                    <div className={`diagnosis-box ${task.lastRunStatus === 'SUCCESS' ? 'success' : task.lastRunStatus === 'FAILED' ? 'failed' : 'warning'}`}>
                      <strong>
                        {task.lastRunStatus === 'SUCCESS' ? (
                          <>
                            <CheckCircle size={15} /> Status da Execução (Sucesso):
                          </>
                        ) : task.lastRunStatus === 'FAILED' ? (
                          <>
                            <XCircle size={15} /> Diagnóstico de Erro:
                          </>
                        ) : (
                          <>
                            <Warning size={15} /> Observação / Status:
                          </>
                        )}
                      </strong>
                      <span>{task.lastRunDiagnosis}</span>
                      {task.lastRunError && task.lastRunStatus === 'FAILED' && (
                        <div className="error-details">
                          <strong>Sugestão de Correção:</strong>
                          <span>{task.lastRunError}</span>
                        </div>
                      )}

                      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
                        <button
                          type="button"
                          onClick={() => setSelectedOutputTask(task)}
                          style={{
                            background: 'var(--surface)',
                            border: '1px solid var(--border)',
                            color: 'var(--text)',
                            padding: '4px 10px',
                            borderRadius: 6,
                            fontSize: '0.75rem',
                            cursor: 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            fontWeight: 600,
                            width: 'fit-content'
                          }}
                        >
                          <Eye size={14} /> Ver Último Retorno
                        </button>

                        <button
                          type="button"
                          onClick={() => handleViewLogs(task)}
                          style={{
                            background: 'var(--primary)',
                            border: 'none',
                            color: '#fff',
                            padding: '4px 10px',
                            borderRadius: 6,
                            fontSize: '0.75rem',
                            cursor: 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            fontWeight: 600,
                            width: 'fit-content'
                          }}
                        >
                          <FileText size={14} /> 📋 Histórico & Logs de Execução
                        </button>
                      </div>
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

              {/* Custom Phosphor Icons Frequency Mode Selector */}
              <div className="form-group">
                <label>Frequência / Agendamento *</label>
                <FrequencyGrid>
                  <FrequencyOptionButton 
                    type="button" 
                    $active={freqMode === 'daily'} 
                    onClick={() => setFreqMode('daily')}
                  >
                    <ArrowsClockwise size={18} /> Diariamente
                  </FrequencyOptionButton>
                  <FrequencyOptionButton 
                    type="button" 
                    $active={freqMode === 'interval'} 
                    onClick={() => setFreqMode('interval')}
                  >
                    <Timer size={18} /> Repetir por Hora
                  </FrequencyOptionButton>
                  <FrequencyOptionButton 
                    type="button" 
                    $active={freqMode === 'specific_date'} 
                    onClick={() => setFreqMode('specific_date')}
                  >
                    <CalendarIcon size={18} /> Data Específica
                  </FrequencyOptionButton>
                  <FrequencyOptionButton 
                    type="button" 
                    $active={freqMode === 'cron'} 
                    onClick={() => setFreqMode('cron')}
                  >
                    <Gear size={18} /> Cron Manual
                  </FrequencyOptionButton>
                </FrequencyGrid>

                {freqMode === 'daily' && (
                  <SubInputPanel>
                    <div className="input-row">
                      <div className="field-box">
                        <label>Horário da Execução:</label>
                        <input 
                          type="time" 
                          value={selectedTime} 
                          onChange={e => setSelectedTime(e.target.value)} 
                          required 
                        />
                      </div>
                    </div>
                    <div className="cron-hint">
                      Converte para expressão Cron: <code>{computedCron}</code>
                    </div>
                  </SubInputPanel>
                )}

                {freqMode === 'interval' && (
                  <SubInputPanel>
                    <div className="input-row">
                      <div className="field-box">
                        <label>Repetir a cada:</label>
                        <select value={intervalHours} onChange={e => setIntervalHours(e.target.value)}>
                          <option value="1min">⚡ A cada 1 Minuto (* * * * *)</option>
                          <option value="5min">⏱️ A cada 5 Minutos (*/5 * * * *)</option>
                          <option value="15min">⏱️ A cada 15 Minutos (*/15 * * * *)</option>
                          <option value="30min">⏱️ A cada 30 Minutos (*/30 * * * *)</option>
                          <option value="1h">🕐 A cada 1 Hora (0 * * * *)</option>
                          <option value="2h">🕑 A cada 2 Horas (0 */2 * * *)</option>
                          <option value="6h">🕕 A cada 6 Horas (0 */6 * * *)</option>
                          <option value="12h">🕛 A cada 12 Horas (0 */12 * * *)</option>
                        </select>
                      </div>
                    </div>
                    <div className="cron-hint">
                      Converte para expressão Cron: <code>{computedCron}</code>
                    </div>
                  </SubInputPanel>
                )}

                {freqMode === 'specific_date' && (
                  <SubInputPanel>
                    <div className="input-row">
                      <div className="field-box">
                        <label>Data de Execução:</label>
                        <input 
                          type="date" 
                          value={specificDate} 
                          onChange={e => setSpecificDate(e.target.value)} 
                          required 
                        />
                      </div>
                      <div className="field-box">
                        <label>Horário de Execução:</label>
                        <input 
                          type="time" 
                          value={selectedTime} 
                          onChange={e => setSelectedTime(e.target.value)} 
                          required 
                        />
                      </div>
                    </div>
                    <div className="cron-hint">
                      Converte para expressão Cron: <code>{computedCron}</code>
                    </div>
                  </SubInputPanel>
                )}

                {freqMode === 'cron' && (
                  <SubInputPanel>
                    <div className="field-box">
                      <label>Expressão Cron Personalizada (5 partes):</label>
                      <input 
                        type="text" 
                        placeholder="Ex: * * * * *" 
                        value={customCron} 
                        onChange={e => setCustomCron(e.target.value)} 
                        style={{ fontFamily: 'monospace', fontSize: '0.95rem', letterSpacing: '1px', fontWeight: 600 }}
                        required 
                      />
                    </div>
                    
                    <div style={{ display: 'flex', gap: 6, fontSize: '0.72rem', color: 'var(--text-muted)', flexWrap: 'wrap', marginTop: 2 }}>
                      <span style={{ background: 'var(--surface)', padding: '3px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>1. Minuto (0-59)</span>
                      <span style={{ background: 'var(--surface)', padding: '3px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>2. Hora (0-23)</span>
                      <span style={{ background: 'var(--surface)', padding: '3px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>3. Dia do Mês (1-31)</span>
                      <span style={{ background: 'var(--surface)', padding: '3px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>4. Mês (1-12)</span>
                      <span style={{ background: 'var(--surface)', padding: '3px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>5. Dia da Semana (0-6)</span>
                    </div>

                    <div style={{ marginTop: 4 }}>
                      <label style={{ fontSize: '0.78rem', fontWeight: 600, display: 'block', marginBottom: 6 }}>Atalhos de Expressões Comuns:</label>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {[
                          { label: '⚡ A cada 1 minuto', cron: '* * * * *' },
                          { label: '⏱️ A cada 5 min', cron: '*/5 * * * *' },
                          { label: '⏱️ A cada 15 min', cron: '*/15 * * * *' },
                          { label: '03:57 AM Diário', cron: '57 3 * * *' },
                          { label: '09:00 AM Seg-Sex', cron: '0 9 * * 1-5' },
                          { label: '1º dia do Mês', cron: '0 0 1 * *' },
                        ].map(preset => (
                          <button
                            key={preset.cron}
                            type="button"
                            onClick={() => setCustomCron(preset.cron)}
                            style={{
                              background: customCron === preset.cron ? 'var(--accent)' : 'var(--surface)',
                              color: customCron === preset.cron ? '#fff' : 'var(--text)',
                              border: '1px solid var(--border)',
                              borderRadius: 6,
                              padding: '4px 10px',
                              fontSize: '0.75rem',
                              cursor: 'pointer',
                              fontWeight: 600,
                              transition: 'all 0.15s ease'
                            }}
                          >
                            {preset.label} (<code>{preset.cron}</code>)
                          </button>
                        ))}
                      </div>
                    </div>
                  </SubInputPanel>
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
                <label>Caminho do Projeto / Pasta de Trabalho (Opcional)</label>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input 
                    type="text" 
                    placeholder="Ex: /Users/sr.tomimatu/projetcs/avento-ia" 
                    value={projectPath} 
                    onChange={e => setProjectPath(e.target.value)} 
                    style={{ flex: 1 }}
                  />
                  <label
                    style={{
                      width: 42,
                      height: 42,
                      flex: '0 0 auto',
                      background: 'var(--surface)',
                      border: '1px solid var(--border)',
                      color: 'var(--text)',
                      borderRadius: 8,
                      cursor: 'pointer',
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      transition: 'all 0.2s ease',
                    }}
                    title="Selecionar pasta no seu computador"
                    onMouseEnter={(e) => {
                      e.currentTarget.style.borderColor = 'var(--primary)';
                      e.currentTarget.style.color = 'var(--primary)';
                      e.currentTarget.style.background = 'color-mix(in srgb, var(--primary) 12%, var(--surface))';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.borderColor = 'var(--border)';
                      e.currentTarget.style.color = 'var(--text)';
                      e.currentTarget.style.background = 'var(--surface)';
                    }}
                  >
                    <FolderOpen size={22} />
                    <input
                      type="file"
                      // @ts-ignore
                      webkitdirectory=""
                      directory=""
                      style={{ display: 'none' }}
                      onChange={(e) => {
                        const files = e.target.files;
                        if (files && files.length > 0) {
                          const firstFile = files[0];
                          const relPath = firstFile.webkitRelativePath || firstFile.name;
                          const folderName = relPath.split('/')[0];
                          // @ts-ignore
                          if (firstFile.path) {
                            // @ts-ignore
                            const fullPath: string = firstFile.path;
                            const idx = fullPath.indexOf(relPath);
                            const folderPath = idx !== -1 ? fullPath.substring(0, idx + folderName.length) : fullPath;
                            setProjectPath(folderPath);
                          } else {
                            setProjectPath(`/Users/sr.tomimatu/projetcs/${folderName}`);
                          }
                        }
                      }}
                    />
                  </label>
                  <button
                    type="button"
                    onClick={() => setProjectPath('/Users/sr.tomimatu/projetcs/avento-ia')}
                    style={{
                      width: 42,
                      height: 42,
                      flex: '0 0 auto',
                      background: 'var(--surface)',
                      border: '1px solid var(--border)',
                      color: 'var(--text)',
                      borderRadius: 8,
                      cursor: 'pointer',
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      transition: 'all 0.2s ease',
                    }}
                    title="Usar pasta atual do projeto (Avento-IA)"
                    onMouseEnter={(e) => {
                      e.currentTarget.style.borderColor = 'var(--primary)';
                      e.currentTarget.style.color = 'var(--primary)';
                      e.currentTarget.style.background = 'color-mix(in srgb, var(--primary) 12%, var(--surface))';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.borderColor = 'var(--border)';
                      e.currentTarget.style.color = 'var(--text)';
                      e.currentTarget.style.background = 'var(--surface)';
                    }}
                  >
                    <Folder size={22} />
                  </button>
                </div>
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

      {selectedOutputTask && (
        <ModalBackdrop onClick={() => setSelectedOutputTask(null)}>
          <Modal onClick={e => e.stopPropagation()} style={{ maxWidth: 640 }}>
            <div className="modal-header">
              <h2><FileText size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: 'var(--primary)' }} /> Retorno da Execução - {selectedOutputTask.name}</h2>
              <button type="button" onClick={() => setSelectedOutputTask(null)}><X size={20} /></button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'var(--bg)', padding: '10px 14px', borderRadius: 8, border: '1px solid var(--border)', fontSize: '0.82rem' }}>
                <span><strong>Status:</strong> {selectedOutputTask.lastRunStatus === 'SUCCESS' ? '✅ Sucesso' : '❌ Falha'}</span>
                <span><strong>Último Disparo:</strong> {selectedOutputTask.lastRunAt ? new Date(selectedOutputTask.lastRunAt).toLocaleString('pt-BR') : 'Sem dados'}</span>
              </div>

              <div>
                <label style={{ fontSize: '0.8rem', fontWeight: 700, display: 'block', marginBottom: 4 }}>Instrução / Prompt Enviado:</label>
                <div style={{ background: 'var(--bg)', padding: 12, borderRadius: 8, border: '1px solid var(--border)', fontSize: '0.82rem' }}>
                  {selectedOutputTask.prompt}
                </div>
              </div>

              <div>
                <label style={{ fontSize: '0.8rem', fontWeight: 700, display: 'block', marginBottom: 4 }}>Retorno Bruto da Execução (Stdout / Resposta do Agente):</label>
                <pre style={{
                  background: '#0d1117',
                  color: '#e6edf3',
                  padding: 14,
                  borderRadius: 8,
                  border: '1px solid #30363d',
                  fontSize: '0.8rem',
                  fontFamily: 'monospace',
                  maxHeight: 240,
                  overflowY: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word'
                }}>
                  {selectedOutputTask.lastRunOutput || selectedOutputTask.lastRunDiagnosis || 'Execução registrada sem saída de texto adicional.'}
                </pre>
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setSelectedOutputTask(null)}
                style={{ background: 'var(--primary)', color: '#fff', border: 'none', padding: '8px 18px', borderRadius: 8, cursor: 'pointer', fontWeight: 600 }}
              >
                Fechar
              </button>
            </div>
          </Modal>
        </ModalBackdrop>
      )}

      {selectedLogTask && (
        <ModalBackdrop onClick={() => setSelectedLogTask(null)}>
          <Modal onClick={e => e.stopPropagation()} style={{ maxWidth: 760, width: '90%' }}>
            <div className="modal-header">
              <h2><FileText size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: 'var(--primary)' }} /> Histórico de Execuções e Logs - {selectedLogTask.name}</h2>
              <button type="button" onClick={() => setSelectedLogTask(null)}><X size={20} /></button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              {isLoadingRuns ? (
                <div style={{ padding: 30, textAlign: 'center', color: 'var(--text-secondary)' }}>Carregando histórico de execuções...</div>
              ) : taskRuns.length === 0 ? (
                <div style={{ padding: 30, textAlign: 'center', color: 'var(--text-secondary)' }}>Nenhuma execução registrada até o momento.</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 420, overflowY: 'auto' }}>
                  {taskRuns.map(run => (
                    <div key={run.id} style={{ background: 'var(--bg)', border: '1px solid var(--border)', borderRadius: 8, padding: 12 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, fontSize: '0.82rem' }}>
                        <span>
                          <strong>Status:</strong> {run.status === 'SUCCESS' ? '✅ Sucesso (Validado)' : '❌ Falha'}
                        </span>
                        <span style={{ color: 'var(--text-secondary)' }}>
                          🕒 {new Date(run.createdAt).toLocaleString('pt-BR')}
                        </span>
                      </div>

                      {run.prompt && (
                        <div style={{ fontSize: '0.78rem', marginBottom: 6, color: 'var(--text-secondary)' }}>
                          <strong>Prompt/Instrução:</strong> {run.prompt}
                        </div>
                      )}

                      <div style={{ fontSize: '0.78rem', fontWeight: 700, marginBottom: 4 }}>Log Bruto / Resposta do Terminal:</div>
                      <pre style={{
                        background: '#0d1117',
                        color: '#e6edf3',
                        padding: 10,
                        borderRadius: 6,
                        border: '1px solid #30363d',
                        fontSize: '0.78rem',
                        fontFamily: 'monospace',
                        maxHeight: 160,
                        overflowY: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                        margin: 0
                      }}>
                        {run.output || run.error || 'Nenhum log retornado.'}
                      </pre>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setSelectedLogTask(null)}
                style={{ background: 'var(--primary)', color: '#fff', border: 'none', padding: '8px 18px', borderRadius: 8, cursor: 'pointer', fontWeight: 600 }}
              >
                Fechar Logs
              </button>
            </div>
          </Modal>
        </ModalBackdrop>
      )}
    </Container>
  );
}
