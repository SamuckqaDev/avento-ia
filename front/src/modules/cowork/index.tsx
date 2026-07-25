import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { api } from '../../services/apiClient';
import { Input, Select, TextArea, DatePicker } from '../../components/ui';
import { 
  Container, Header, CreateButton, Grid, Card, 
  ModalBackdrop, Modal, TabNavigation, CalendarWrapper,
  CalendarHeader, CalendarGrid, DayCell, EventBadge,
  FrequencyGrid, FrequencyOptionButton, SubInputPanel,
  DeleteModalBackdrop, DeleteModal, DeleteModalActions, DeleteModalButton, DeleteModalError,
  KpiBar, KpiCard, EmptyStateWrapper, ToolbarWrapper, FilterChipGroup,
  FilterChipButton, ActionButtonGroup, SecondaryActionButton, SecondaryBadgeButton, PrimaryBadgeButton
} from './styles';
import { Plus, Clock, Play, Pause, Trash, Warning, Calendar as CalendarIcon, X, CaretLeft, CaretRight, Robot, ArrowsClockwise, Timer, Gear, CheckCircle, XCircle, FileText, Eye, Folder, FolderOpen, PencilSimple, Lightning, MagnifyingGlass, DownloadSimple, UploadSimple, SquaresFour } from '@phosphor-icons/react';
import { TemplatesModal, TaskTemplate } from './TemplatesModal';

export interface ScheduledTask {
  id: number;
  name: string;
  description: string;
  cronExpression: string;
  prompt: string;
  chatId?: number;
  projectPath?: string;
  onSuccessTaskId?: number | null;
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
  const [customCron, setCustomCron] = useState('* * * * *');
  const [cronParts, setCronParts] = useState<string[]>(['*', '*', '*', '*', '*']);

  const handleCronPartChange = (index: number, val: string) => {
    const cleanVal = val.trim();
    const updated = [...cronParts];
    updated[index] = cleanVal || '*';
    setCronParts(updated);
    setCustomCron(updated.join(' '));
  };

  const handleCustomCronTextChange = (val: string) => {
    setCustomCron(val);
    const parts = val.trim().split(/\s+/);
    if (parts.length === 5) {
      setCronParts(parts);
    }
  };

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

  const fetchTasks = useCallback(async (isInitialLoad = false) => {
    try {
      if (isInitialLoad) setIsLoading(true);
      const { data } = await api.get<ScheduledTask[]>('/api/scheduled-tasks');
      setTasks(data);
    } catch (e) {
      console.error('Erro ao carregar tarefas agendadas', e);
    } finally {
      if (isInitialLoad) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchTasks(true);
    const interval = setInterval(() => {
      void fetchTasks(false);
    }, 15000);
    return () => clearInterval(interval);
  }, [fetchTasks]);

  // Search & Filter state
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'PAUSED' | 'FAILED'>('ALL');

  // Chained task & Templates state
  const [onSuccessTaskId, setOnSuccessTaskId] = useState<number | null>(null);
  const [isTemplatesModalOpen, setIsTemplatesModalOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const filteredTasks = useMemo(() => {
    return tasks.filter(task => {
      const matchesSearch = 
        searchQuery.trim() === '' ||
        task.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        task.prompt.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (task.description && task.description.toLowerCase().includes(searchQuery.toLowerCase()));

      let matchesStatus = true;
      if (statusFilter === 'ACTIVE') matchesStatus = task.status === 'ACTIVE';
      if (statusFilter === 'PAUSED') matchesStatus = task.status === 'PAUSED';
      if (statusFilter === 'FAILED') matchesStatus = task.lastRunStatus === 'FAILED';

      return matchesSearch && matchesStatus;
    });
  }, [tasks, searchQuery, statusFilter]);

  const handleSelectTemplate = (template: TaskTemplate) => {
    setEditingTask(null);
    setName(template.name);
    setDescription(template.description);
    setPrompt(template.prompt);
    setProjectPath('');
    setOnSuccessTaskId(null);
    setFreqMode('cron');
    setCustomCron(template.cronExpression);
    handleCustomCronTextChange(template.cronExpression);
    setIsModalOpen(true);
  };

  const handleExportRoutines = () => {
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(tasks, null, 2));
    const downloadAnchor = document.createElement('a');
    downloadAnchor.setAttribute("href", dataStr);
    downloadAnchor.setAttribute("download", `avento-cowork-routines-${new Date().toISOString().split('T')[0]}.json`);
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
  };

  const handleImportRoutines = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      const reader = new FileReader();
      reader.onload = async (event) => {
        try {
          const imported = JSON.parse(event.target?.result as string);
          if (Array.isArray(imported)) {
            for (const t of imported) {
              if (t.name && t.cronExpression && t.prompt) {
                await api.post('/api/scheduled-tasks', {
                  name: t.name,
                  description: t.description || '',
                  cronExpression: t.cronExpression,
                  prompt: t.prompt,
                  projectPath: t.projectPath || '',
                  onSuccessTaskId: t.onSuccessTaskId || null
                });
              }
            }
            void fetchTasks(false);
          }
        } catch (err) {
          console.error("Erro ao importar arquivo JSON de rotinas", err);
        }
      };
      reader.readAsText(file);
    } catch (err) {
      console.error("Erro ao ler arquivo de importação", err);
    }
  };

  // Editing state
  const [editingTask, setEditingTask] = useState<ScheduledTask | null>(null);

  const handleOpenEditTask = (task: ScheduledTask) => {
    setEditingTask(task);
    setName(task.name);
    setDescription(task.description || '');
    setPrompt(task.prompt);
    setProjectPath(task.projectPath || '');
    setOnSuccessTaskId(task.onSuccessTaskId || null);
    setCustomCron(task.cronExpression);
    const parts = task.cronExpression.split(' ');
    if (parts.length === 5) setCronParts(parts);
    setFreqMode('cron');
    setIsModalOpen(true);
  };

  const handleCloseModal = () => {
    setIsModalOpen(false);
    setEditingTask(null);
    setName('');
    setDescription('');
    setPrompt('');
    setProjectPath('');
    setOnSuccessTaskId(null);
  };

  const handleCreateTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !prompt.trim()) return;

    try {
      setIsSubmitting(true);
      if (editingTask) {
        await api.put(`/api/scheduled-tasks/${editingTask.id}`, {
          name,
          description,
          cronExpression: computedCron,
          prompt,
          projectPath,
          onSuccessTaskId
        });
      } else {
        await api.post('/api/scheduled-tasks', {
          name,
          description,
          cronExpression: computedCron,
          prompt,
          projectPath,
          onSuccessTaskId
        });
      }
      handleCloseModal();
      void fetchTasks();
    } catch (e) {
      console.error('Erro ao salvar tarefa agendada', e);
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

  // Delete modal state
  const [taskToDelete, setTaskToDelete] = useState<ScheduledTask | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const confirmDeleteTask = async () => {
    if (!taskToDelete) return;
    setIsDeleting(true);
    setDeleteError(null);
    try {
      await api.delete(`/api/scheduled-tasks/${taskToDelete.id}`);
      await fetchTasks();
      setTaskToDelete(null);
    } catch (e: any) {
      console.error('Erro ao excluir tarefa agendada', e);
      setDeleteError(e?.response?.data?.message || 'Erro ao tentar excluir a atividade.');
    } finally {
      setIsDeleting(false);
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
          <h1><CalendarIcon size={26} /> Avento Cowork & Agenda</h1>
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

          <CreateButton onClick={() => { setEditingTask(null); setIsModalOpen(true); }}>
            <Plus size={18} /> Nova Atividade
          </CreateButton>
        </div>
      </Header>

      <KpiBar>
        <KpiCard $variant="primary">
          <div className="kpi-icon"><Robot size={18} /></div>
          <div className="kpi-info">
            <span>Total de Atividades</span>
            <strong>{tasks.length}</strong>
          </div>
        </KpiCard>
        <KpiCard $variant="success">
          <div className="kpi-icon"><Play size={18} /></div>
          <div className="kpi-info">
            <span>Ativas</span>
            <strong>{tasks.filter(t => t.status === 'ACTIVE').length}</strong>
          </div>
        </KpiCard>
        <KpiCard $variant="warning">
          <div className="kpi-icon"><Pause size={18} /></div>
          <div className="kpi-info">
            <span>Pausadas</span>
            <strong>{tasks.filter(t => t.status === 'PAUSED').length}</strong>
          </div>
        </KpiCard>
        <KpiCard $variant="primary">
          <div className="kpi-icon"><CheckCircle size={18} /></div>
          <div className="kpi-info">
            <span>Com Sucesso</span>
            <strong>{tasks.filter(t => t.lastRunStatus === 'SUCCESS').length}</strong>
          </div>
        </KpiCard>
      </KpiBar>

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

            {calendarDays.map(cell => {
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
                  key={cell.date.toISOString()} 
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
          <ToolbarWrapper>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1, minWidth: 260 }}>
              <div style={{ position: 'relative', width: '100%', maxWidth: 300 }}>
                <MagnifyingGlass size={16} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
                <Input 
                  type="text" 
                  placeholder="Pesquisar automações..." 
                  value={searchQuery} 
                  onChange={e => setSearchQuery(e.target.value)} 
                  style={{ paddingLeft: 34, height: 36 }}
                />
              </div>

              <FilterChipGroup>
                <FilterChipButton $active={statusFilter === 'ALL'} onClick={() => setStatusFilter('ALL')}>
                  <SquaresFour size={14} /> Todas ({tasks.length})
                </FilterChipButton>
                <FilterChipButton $active={statusFilter === 'ACTIVE'} onClick={() => setStatusFilter('ACTIVE')}>
                  <Play size={14} style={{ color: '#10B981' }} /> Ativas ({tasks.filter(t => t.status === 'ACTIVE').length})
                </FilterChipButton>
                <FilterChipButton $active={statusFilter === 'PAUSED'} onClick={() => setStatusFilter('PAUSED')}>
                  <Pause size={14} style={{ color: '#F59E0B' }} /> Pausadas ({tasks.filter(t => t.status === 'PAUSED').length})
                </FilterChipButton>
                <FilterChipButton $active={statusFilter === 'FAILED'} onClick={() => setStatusFilter('FAILED')}>
                  <XCircle size={14} style={{ color: '#EF4444' }} /> Com Falha ({tasks.filter(t => t.lastRunStatus === 'FAILED').length})
                </FilterChipButton>
              </FilterChipGroup>
            </div>

            <ActionButtonGroup>
              <SecondaryActionButton onClick={() => setIsTemplatesModalOpen(true)}>
                <SquaresFour size={16} /> Templates Prontos
              </SecondaryActionButton>
              <SecondaryActionButton onClick={handleExportRoutines} title="Baixar backup em JSON">
                <DownloadSimple size={16} /> Exportar JSON
              </SecondaryActionButton>
              <SecondaryActionButton onClick={() => fileInputRef.current?.click()} title="Importar backup em JSON">
                <UploadSimple size={16} /> Importar JSON
              </SecondaryActionButton>
              <input 
                type="file" 
                ref={fileInputRef} 
                onChange={handleImportRoutines} 
                accept=".json" 
                style={{ display: 'none' }} 
              />
            </ActionButtonGroup>
          </ToolbarWrapper>

          {isLoading && tasks.length === 0 ? (
            <p style={{ color: 'var(--text-muted)' }}>Carregando automações agendadas...</p>
          ) : filteredTasks.length === 0 ? (
            <EmptyStateWrapper>
              <Clock size={48} />
              <h3>{searchQuery || statusFilter !== 'ALL' ? 'Nenhuma automação encontrada' : 'Nenhuma tarefa agendada na agenda'}</h3>
              <p>
                {searchQuery || statusFilter !== 'ALL' 
                  ? 'Tente ajustar os termos de pesquisa ou remover os filtros aplicados.'
                  : 'Crie rotinas diárias como backups de projetos (ex: 03:57 AM), checagem de saúde do sistema ou limpezas automáticas.'}
              </p>
              <CreateButton onClick={() => setIsModalOpen(true)}>
                <Plus size={18} /> Criar Primeira Atividade
              </CreateButton>
            </EmptyStateWrapper>
          ) : (
            <Grid>
              {filteredTasks.map(task => (
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
                        <SecondaryBadgeButton type="button" onClick={() => setSelectedOutputTask(task)}>
                          <Eye size={14} /> Ver Último Retorno
                        </SecondaryBadgeButton>

                        <PrimaryBadgeButton type="button" onClick={() => handleViewLogs(task)}>
                          <FileText size={14} /> Histórico & Logs de Execução
                        </PrimaryBadgeButton>
                      </div>
                    </div>
                  )}

                  <div className="card-footer">
                    <div className="next-run">
                      Próxima rodada: {task.nextRunAt ? new Date(task.nextRunAt).toLocaleString('pt-BR') : 'Agendada'}
                    </div>

                    <div className="actions">
                      <button type="button" className="run-now" onClick={() => handleRunNow(task.id)} title="Executar Agora">
                        <Lightning size={14} /> Rodar Agora
                      </button>
                      <button type="button" onClick={() => handleOpenEditTask(task)} title="Editar Atividade">
                        <PencilSimple size={14} />
                      </button>
                      <button type="button" onClick={() => handleToggleTask(task.id)} title={task.status === 'ACTIVE' ? 'Pausar' : 'Ativar'}>
                        {task.status === 'ACTIVE' ? <Pause size={14} /> : <Play size={14} />}
                      </button>
                      <button type="button" className="delete" onClick={() => setTaskToDelete(task)} title="Excluir">
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
        <ModalBackdrop onClick={handleCloseModal}>
          <Modal onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editingTask ? 'Editar Atividade / Automação' : 'Agendar Nova Atividade / Automação'}</h2>
              <button type="button" onClick={handleCloseModal}><X size={20} /></button>
            </div>

            <form onSubmit={handleCreateTask} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="form-group">
                <label>Nome da Atividade *</label>
                <Input 
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
                        <DatePicker 
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
                        <Select value={intervalHours} onChange={e => setIntervalHours(e.target.value)}>
                          <option value="1min">A cada 1 Minuto (* * * * *)</option>
                          <option value="5min">A cada 5 Minutos (*/5 * * * *)</option>
                          <option value="15min">A cada 15 Minutos (*/15 * * * *)</option>
                          <option value="30min">A cada 30 Minutos (*/30 * * * *)</option>
                          <option value="1h">A cada 1 Hora (0 * * * *)</option>
                          <option value="2h">A cada 2 Horas (0 */2 * * *)</option>
                          <option value="6h">A cada 6 Horas (0 */6 * * *)</option>
                          <option value="12h">A cada 12 Horas (0 */12 * * *)</option>
                        </Select>
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
                        <DatePicker 
                          type="date" 
                          value={specificDate} 
                          onChange={e => setSpecificDate(e.target.value)} 
                          required 
                        />
                      </div>
                      <div className="field-box">
                        <label>Horário de Execução:</label>
                        <DatePicker 
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
                    <div>
                      <label style={{ fontSize: '0.78rem', fontWeight: 700, display: 'block', marginBottom: 6, color: 'var(--text)' }}>
                        Campos Estruturados do Cron (5 Posições Padrão):
                      </label>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 6 }}>
                        {[
                          { title: 'Minuto', sub: '0-59' },
                          { title: 'Hora', sub: '0-23' },
                          { title: 'Dia Mês', sub: '1-31' },
                          { title: 'Mês', sub: '1-12' },
                          { title: 'Dia Sem.', sub: '0-6' }
                        ].map((part, idx) => (
                          <div key={part.title} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                            <span style={{ fontSize: '0.68rem', fontWeight: 600, color: 'var(--text)', textAlign: 'center' }}>{part.title}</span>
                            <Input
                              type="text"
                              value={cronParts[idx] || '*'}
                              placeholder="*"
                              onChange={e => handleCronPartChange(idx, e.target.value)}
                              style={{
                                textAlign: 'center',
                                fontFamily: 'monospace',
                                fontWeight: 700,
                                fontSize: '0.88rem',
                                padding: '4px',
                                height: 34,
                                background: 'var(--surface)',
                                border: '1px solid var(--border)',
                                borderRadius: 6
                              }}
                            />
                            <span style={{ fontSize: '0.62rem', color: 'var(--text-muted)', textAlign: 'center' }}>{part.sub}</span>
                          </div>
                        ))}
                      </div>
                    </div>

                    <div className="field-box" style={{ marginTop: 2 }}>
                      <label style={{ fontSize: '0.75rem' }}>Expressão Cron Resultante:</label>
                      <Input 
                        type="text" 
                        placeholder="Ex: * * * * *" 
                        value={customCron} 
                        onChange={e => handleCustomCronTextChange(e.target.value)} 
                        style={{ fontFamily: 'monospace', fontSize: '0.9rem', letterSpacing: '2px', fontWeight: 700, textAlign: 'center', color: 'var(--primary)', height: 34 }}
                        required 
                      />
                    </div>

                    <div style={{ marginTop: 4 }}>
                      <label style={{ fontSize: '0.75rem', fontWeight: 600, display: 'block', marginBottom: 4 }}>Atalhos Rápidos:</label>
                      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                        {[
                          { label: '⚡ 1 min', cron: '* * * * *' },
                          { label: '⏱️ 5 min', cron: '*/5 * * * *' },
                          { label: '⏱️ 15 min', cron: '*/15 * * * *' },
                          { label: '03:57 AM', cron: '57 3 * * *' },
                          { label: 'Seg-Sex', cron: '0 9 * * 1-5' },
                          { label: '1º dia Mês', cron: '0 0 1 * *' },
                        ].map(preset => (
                          <button
                            key={preset.cron}
                            type="button"
                            onClick={() => handleCustomCronTextChange(preset.cron)}
                            style={{
                              background: customCron === preset.cron ? 'var(--primary)' : 'var(--surface)',
                              color: customCron === preset.cron ? '#fff' : 'var(--text)',
                              border: '1px solid var(--border)',
                              borderRadius: 4,
                              padding: '2px 6px',
                              fontSize: '0.7rem',
                              cursor: 'pointer',
                              fontWeight: 600,
                              transition: 'all 0.15s ease'
                            }}
                          >
                            {preset.label}
                          </button>
                        ))}
                      </div>
                    </div>
                  </SubInputPanel>
                )}
              </div>

              <div className="form-group">
                <label>Instrução / Prompt para o Agente de IA *</label>
                <TextArea 
                  rows={2} 
                  placeholder="Ex: Execute o script de backup no terminal, compacte os arquivos da pasta e verifique se o arquivo final tem tamanho maior que zero." 
                  value={prompt} 
                  onChange={e => setPrompt(e.target.value)} 
                  required 
                />
              </div>

              <div className="form-group">
                <label>Caminho do Projeto / Pasta de Trabalho (Opcional)</label>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <Input 
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

              <div className="form-group">
                <label>Próxima Atividade Encadeada (Opcional)</label>
                <Select 
                  value={onSuccessTaskId || ''} 
                  onChange={e => setOnSuccessTaskId(e.target.value ? Number(e.target.value) : null)}
                >
                  <option value="">Nenhuma (Execução isolada)</option>
                  {tasks
                    .filter(t => !editingTask || t.id !== editingTask.id)
                    .map(t => (
                      <option key={t.id} value={t.id}>
                        ⚡ Disparar: {t.name} (ID: {t.id})
                      </option>
                    ))}
                </Select>
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

      {taskToDelete && (
        <DeleteModalBackdrop role="presentation" onClick={() => {
          if (!isDeleting) {
            setDeleteError(null);
            setTaskToDelete(null);
          }
        }}>
          <DeleteModal role="dialog" aria-modal="true" aria-labelledby="delete-task-title" onClick={event => event.stopPropagation()}>
            <button type="button" className="modal-close" onClick={() => {
              setDeleteError(null);
              setTaskToDelete(null);
            }} disabled={isDeleting} title="Cancelar">
              <X size={18} />
            </button>
            <h2 id="delete-task-title">Apagar atividade agendada?</h2>
            <p>Isso apagará permanentemente a atividade “{taskToDelete.name}” e todo o seu histórico de execuções.</p>
            {deleteError && <DeleteModalError role="alert">{deleteError}</DeleteModalError>}
            <DeleteModalActions>
              <DeleteModalButton type="button" onClick={() => {
                setDeleteError(null);
                setTaskToDelete(null);
              }} disabled={isDeleting}>Cancelar</DeleteModalButton>
              <DeleteModalButton $danger type="button" onClick={confirmDeleteTask} disabled={isDeleting}>
                {isDeleting ? 'Apagando...' : 'Apagar definitivamente'}
              </DeleteModalButton>
            </DeleteModalActions>
          </DeleteModal>
        </DeleteModalBackdrop>
      )}

      <TemplatesModal
        isOpen={isTemplatesModalOpen}
        onClose={() => setIsTemplatesModalOpen(false)}
        onSelectTemplate={handleSelectTemplate}
      />
    </Container>
  );
}
