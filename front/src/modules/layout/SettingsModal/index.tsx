import { useState, useEffect, useRef, ChangeEvent, FormEvent } from 'react';
import { createPortal } from 'react-dom';
import { X } from '@phosphor-icons/react';
import {
  ModalBackdrop, ModalContainer, Header, Body, SettingRow,
  ToggleSwitch, Footer, DestructiveButton, SaveButton,
  Tabs, TabButton, UsageCard, BarChart, UsageTable,
  RangeSelector, RangeButton, StatGrid, StatBox,
  MemoryIntro, MemoryAddRow, MemorySectionTitle, MemoryList,
  MemoryCard, MemoryActionButton, MemoryEmpty,
  AgentForm, AgentField, AgentDefaultToggleRow, AgentDefaultBadge,
  BadgeShared, BadgePrivate, ProviderCard, ProviderGrid, ProviderSectionTitle, TestButton, TestStatusPill
} from './styles';
import { api } from '../../../services/apiClient';
import { useAuth } from '../../auth/AuthProvider';

interface SettingsModalProps {
  onClose: () => void;
  isDarkMode: boolean;
  toggleTheme: () => void;
  isVoiceEnabled: boolean;
  handleToggleVoice: (enabled: boolean) => void;
}

interface DayTotal {
  day: string;
  total: number;
}

interface ModelUsage {
  model: string;
  promptTokens: number;
  completionTokens: number;
  total: number;
}

interface ChatUsage {
  chatId: number;
  title: string;
  total: number;
}

interface UsageSummary {
  range: string;
  total: number;
  promptTotal: number;
  completionTotal: number;
  requestCount: number;
  byModel: ModelUsage[];
  byDay: DayTotal[];
  byChat: ChatUsage[];
}

interface MemoryItem {
  id: number;
  content: string;
  category: string;
  status: 'PENDING' | 'ACTIVE';
  origin: 'MANUAL' | 'SUGGESTED';
  createdAt: string;
  updatedAt: string;
}

interface AgentItem {
  id: number;
  name: string;
  specialty: string;
  systemInstructions: string;
  triggers: string;
  model: string | null;
  isDefault: boolean;
}

const EMPTY_AGENT_FORM = { name: '', specialty: '', systemInstructions: '', triggers: '', isDefault: false };

type UsageRange = 'today' | '7d' | '30d';

const RANGE_LABEL: Record<UsageRange, string> = {
  today: 'hoje',
  '7d': 'últimos 7 dias',
  '30d': 'últimos 30 dias',
};

export function SettingsModal({ 
  onClose,
  isDarkMode,
  toggleTheme,
  isVoiceEnabled,
  handleToggleVoice
}: SettingsModalProps) {
  const [activeTab, setActiveTab] = useState<'conta' | 'uso' | 'preferencias' | 'provedores' | 'memoria' | 'agentes'>('conta');
  const [ttsEnabled, setTtsEnabled] = useState(false);
  // Thinking é opt-in: o padrão acompanha o backend (avento.agent.enable-thinking = false). Com
  // `true` aqui, o menu mostrava "ligado" antes de a preferência real chegar, e desligar não tinha
  // efeito enquanto nada estivesse gravado.
  const [thinkingEnabled, setThinkingEnabled] = useState(false);
  const [isLoadingSettings, setIsLoadingSettings] = useState(true);
  const [isSaving, setIsSaving] = useState(false);

  // Uma configuracao, dirigida pelo TIPO. Antes eram duas secoes paralelas ("servidor da casa" e
  // "nuvem pessoal") com um toggle escolhendo quem ganhava — o que nao descreve a realidade: Ollama
  // local, Ollama na rede, DGX compativel com OpenAI e Gemini sao o mesmo conceito com endereco,
  // formato e chave diferentes.
  const [providerSettings, setProviderSettings] = useState({
    providerKind: 'OLLAMA',
    baseUrl: 'http://127.0.0.1:11434',
    selectedModel: '',
    visionModel: '',
    imageModel: '',
    plannerModel: '',
    embeddingModel: '',
    apiKeyMasked: '',
    apiKeyInput: '',
  });
  const [providerModels, setProviderModels] = useState<string[]>([]);
  // Chave ja salva fica guardada e mascarada; editar e um ato deliberado. Campo de senha sempre
  // aberto convida a colar por cima sem querer, e apagar uma chave valida por engano e caro.
  const [editingApiKey, setEditingApiKey] = useState(false);
  const [isLoadingProviders, setIsLoadingProviders] = useState(false);
  const [testLanStatus, setTestLanStatus] = useState<{ success?: boolean; message?: string; loading?: boolean }>({});
  
  const [usageData, setUsageData] = useState<UsageSummary | null>(null);
  const [metricsData, setMetricsData] = useState<{ avgDurationSecs?: number; runsCount?: number } | null>(null);
  const [isLoadingUsage, setIsLoadingUsage] = useState(true);
  const [usageRange, setUsageRange] = useState<UsageRange>('7d');
  
  const [avatarUrl, setAvatarUrl] = useState<string>(() => localStorage.getItem('avento_avatar_url') || '');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [memories, setMemories] = useState<MemoryItem[]>([]);
  const [isLoadingMemory, setIsLoadingMemory] = useState(true);
  const [newMemory, setNewMemory] = useState('');
  const [memoryBusy, setMemoryBusy] = useState(false);

  const [agents, setAgents] = useState<AgentItem[]>([]);
  const [isLoadingAgents, setIsLoadingAgents] = useState(true);
  const [agentForm, setAgentForm] = useState(EMPTY_AGENT_FORM);
  const [agentBusy, setAgentBusy] = useState(false);
  
  const { user, logout } = useAuth();

  useEffect(() => {
    if (activeTab === 'preferencias') {
      const loadSettings = async () => {
        try {
          const { data } = await api.get<{ ttsEnabled: boolean; thinkingEnabled: boolean }>('/api/settings');
          setTtsEnabled(data.ttsEnabled || false);
          setThinkingEnabled(data.thinkingEnabled ?? false);
        } catch (error) {
          console.error("Erro ao carregar configurações", error);
        } finally {
          setIsLoadingSettings(false);
        }
      };
      loadSettings();
    }
  }, [activeTab]);

  useEffect(() => {
    if (activeTab === 'uso') {
      const loadUsage = async () => {
        setIsLoadingUsage(true);
        try {
          const { data } = await api.get<UsageSummary>(`/api/usage/summary?range=${usageRange}`);
          setUsageData(data);
        } catch (error) {
          console.error("Erro ao carregar uso de tokens", error);
        } finally {
          setIsLoadingUsage(false);
        }
      };
      loadUsage();
      api.get<{ avgDurationSecs?: number; runsCount?: number }>('/api/metrics')
        .then(({ data }) => setMetricsData(data))
        .catch(() => {});
    }
  }, [activeTab, usageRange]);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await api.put('/api/settings', { ttsEnabled, thinkingEnabled });
      onClose();
    } catch (error) {
      console.error("Erro ao salvar configurações", error);
    } finally {
      setIsSaving(false);
    }
  };

  useEffect(() => {
    if (activeTab === 'memoria') {
      loadMemories();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);

  const loadMemories = async () => {
    setIsLoadingMemory(true);
    try {
      const { data } = await api.get<MemoryItem[]>('/api/memory');
      setMemories(data);
    } catch (error) {
      console.error("Erro ao carregar memória", error);
    } finally {
      setIsLoadingMemory(false);
    }
  };

  const handleAddMemory = async () => {
    const content = newMemory.trim();
    if (!content) return;
    setMemoryBusy(true);
    try {
      await api.post('/api/memory', { content });
      setNewMemory('');
      await loadMemories();
    } catch (error) {
      console.error("Erro ao adicionar memória", error);
    } finally {
      setMemoryBusy(false);
    }
  };

  const handleConfirmMemory = async (id: number) => {
    setMemoryBusy(true);
    try {
      await api.put(`/api/memory/${id}/confirm`, {});
      await loadMemories();
    } catch (error) {
      console.error("Erro ao confirmar memória", error);
    } finally {
      setMemoryBusy(false);
    }
  };

  const handleDeleteMemory = async (id: number) => {
    setMemoryBusy(true);
    try {
      await api.delete(`/api/memory/${id}`);
      await loadMemories();
    } catch (error) {
      console.error("Erro ao remover memória", error);
    } finally {
      setMemoryBusy(false);
    }
  };

  useEffect(() => {
    if (activeTab === 'agentes') {
      loadAgents();
    } else if (activeTab === 'provedores') {
      loadProviders();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);

  const loadProviders = async () => {
    setIsLoadingProviders(true);
    try {
      const { data } = await api.get<{
        providerKind: string;
        baseUrl: string;
        selectedModel: string;
        visionModel: string;
        imageModel: string;
        plannerModel: string;
        embeddingModel: string;
        apiKeyMasked: string;
      }>('/api/ai/providers');
      if (data) {
        setProviderSettings(prev => ({
          ...prev,
          providerKind: data.providerKind || 'OLLAMA',
          baseUrl: data.baseUrl || 'http://127.0.0.1:11434',
          selectedModel: data.selectedModel || '',
          visionModel: data.visionModel || '',
          imageModel: data.imageModel || '',
          plannerModel: data.plannerModel || '',
          embeddingModel: data.embeddingModel || '',
          apiKeyMasked: data.apiKeyMasked || '',
          apiKeyInput: '',
        }));
      }
      // Modelos que o provedor REALMENTE tem, para o campo de modelo sugerir em vez de exigir que a
      // pessoa acerte o nome de cabeca.
      try {
        // /api/models, nao /api/ollama/models: o controller esta mapeado em @RequestMapping("/api").
        // A URL errada devolvia 404 e o catch zerava a lista em silencio — o seletor abria vazio e
        // nao havia como escolher modelo nenhum.
        const models = await api.get<{ data?: { id: string }[] }>('/api/models');
        setProviderModels((models.data?.data || []).map(model => model.id).filter(Boolean));
      } catch (error) {
        console.error('Erro ao listar modelos do provedor', error);
        setProviderModels([]);
        setTestLanStatus({
          success: false,
          message: 'Não consegui listar os modelos do provedor. Clique em "Testar conexão".',
          loading: false,
        });
      }
    } catch (error) {
      console.error("Erro ao carregar configurações de provedores", error);
    } finally {
      setIsLoadingProviders(false);
    }
  };

  // Um teste só: o backend lista modelos no provedor: se a listagem funciona, endereço, formato e
  // chave estão certos. Não faz sentido "testar rede" e "testar nuvem" separados quando existe uma
  // configuração ativa.
  const handleTestProvider = async () => {
    setTestLanStatus({ loading: true });
    try {
      const { data } = await api.post<{ success: boolean; message: string; latencyMs: number; models?: string[] }>('/api/ai/providers/test', {
        targetType: 'PROVIDER',
        serverType: providerSettings.providerKind,
        serverUrl: providerSettings.baseUrl,
        apiKey: providerSettings.apiKeyInput || undefined,
        modelName: providerSettings.selectedModel,
      });
      setTestLanStatus({ success: data.success, message: data.message, loading: false });
      // O teste alimenta o seletor: e assim que se escolhe um modelo ANTES de salvar, sem digitar
      // nome de cabeca. Foi digitando de cabeca que um nome chumbado acabou salvo e deu 404.
      if (data.models && data.models.length > 0) {
        setProviderModels(data.models);
        setProviderSettings(prev => ({
          ...prev,
          selectedModel: data.models!.includes(prev.selectedModel) ? prev.selectedModel : data.models![0],
        }));
      }
    } catch {
      setTestLanStatus({ success: false, message: 'Falha ao testar o provedor.', loading: false });
    }
  };

  const handleDisconnectProvider = async () => {
    setIsSaving(true);
    try {
      await api.delete('/api/ai/providers');
      setProviderModels([]);
      setEditingApiKey(false);
      setTestLanStatus({ success: true, message: 'Provedor removido — voltando ao modelo local.', loading: false });
      await loadProviders();
    } catch (error) {
      console.error('Erro ao remover provedor', error);
      setTestLanStatus({ success: false, message: 'Falha ao remover o provedor.', loading: false });
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * Grava so o modelo, na hora em que ele e escolhido.
   *
   * O select mudava apenas o estado local e exigia apertar "Salvar provedor" depois. Quem troca num
   * dropdown espera que valha — e nao valia: a conversa seguia com o modelo antigo sem nenhum aviso.
   */
  const persistSelectedModel = async (model: string) => {
    setProviderSettings(prev => ({ ...prev, selectedModel: model }));
    if (!model) return;
    try {
      await api.put('/api/ai/providers', { providerKind: providerSettings.providerKind, selectedModel: model });
      const { data } = await api.get<{ selectedModel: string }>('/api/ai/providers');
      setTestLanStatus(
        data?.selectedModel === model
          ? { success: true, message: `Modelo ativo: ${model}`, loading: false }
          : { success: false, message: `O backend manteve "${data?.selectedModel}" em vez de "${model}".`, loading: false }
      );
    } catch (error) {
      console.error('Erro ao salvar o modelo', error);
      setTestLanStatus({ success: false, message: `Falha ao salvar o modelo ${model}.`, loading: false });
    }
  };

  const handleSaveProviders = async () => {
    setIsSaving(true);
    try {
      await api.put('/api/ai/providers', {
        providerKind: providerSettings.providerKind,
        baseUrl: providerSettings.baseUrl,
        selectedModel: providerSettings.selectedModel,
        visionModel: providerSettings.visionModel,
        imageModel: providerSettings.imageModel,
        plannerModel: providerSettings.plannerModel,
        embeddingModel: providerSettings.embeddingModel,
        // Só manda a chave quando foi digitada: mandar a mascarada a apagaria no backend.
        apiKey: providerSettings.apiKeyInput || undefined,
      });
      // Confere o que voltou: se o backend nao gravou o que foi enviado, dizer — salvar em
      // silencio e depois a conversa usar outro modelo foi exatamente o que aconteceu.
      const { data } = await api.get<{ selectedModel: string }>('/api/ai/providers');
      if (data?.selectedModel === providerSettings.selectedModel) {
        setTestLanStatus({ success: true, message: `Salvo: ${data.selectedModel}`, loading: false });
      } else {
        setTestLanStatus({
          success: false,
          message: `O backend gravou "${data?.selectedModel || '(vazio)'}" em vez de "${providerSettings.selectedModel}".`,
          loading: false,
        });
      }
      await loadProviders();
    } catch (error) {
      console.error("Erro ao salvar configurações de provedor", error);
      setTestLanStatus({ success: false, message: 'Falha ao salvar o provedor.', loading: false });
    } finally {
      setIsSaving(false);
    }
  };

  const loadAgents = async () => {
    setIsLoadingAgents(true);
    try {
      const { data } = await api.get<AgentItem[]>('/api/agents');
      setAgents(data);
    } catch (error) {
      console.error("Erro ao carregar agentes", error);
    } finally {
      setIsLoadingAgents(false);
    }
  };

  const handleCreateAgent = async (event: FormEvent) => {
    event.preventDefault();
    if (!agentForm.name.trim()) return;
    setAgentBusy(true);
    try {
      await api.post('/api/agents', {
        name: agentForm.name.trim(),
        specialty: agentForm.specialty.trim(),
        systemInstructions: agentForm.systemInstructions.trim(),
        triggers: agentForm.triggers.trim(),
        isDefault: agentForm.isDefault,
      });
      setAgentForm(EMPTY_AGENT_FORM);
      await loadAgents();
    } catch (error) {
      console.error("Erro ao criar agente", error);
    } finally {
      setAgentBusy(false);
    }
  };

  const handleDeleteAgent = async (id: number) => {
    setAgentBusy(true);
    try {
      await api.delete(`/api/agents/${id}`);
      await loadAgents();
    } catch (error) {
      console.error("Erro ao remover agente", error);
    } finally {
      setAgentBusy(false);
    }
  };

  const handleRestore = async () => {
    setIsSaving(true);
    try {
      const { data } = await api.put<{ ttsEnabled: boolean; thinkingEnabled: boolean }>(
        '/api/settings/defaults', {}
      );
      setTtsEnabled(data.ttsEnabled);
      setThinkingEnabled(data.thinkingEnabled);
    } catch (error) {
      console.error("Erro ao restaurar configurações", error);
    } finally {
      setIsSaving(false);
    }
  };
  
  const handleLogout = async () => {
    await logout();
    onClose();
  };

  const getInitials = (name?: string) => {
    if (!name) return 'U';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return parts[0].substring(0, 2).toUpperCase();
  };

  const handleAvatarChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const base64 = event.target?.result as string;
      setAvatarUrl(base64);
      localStorage.setItem('avento_avatar_url', base64);
      // Avisa a sidebar (e qualquer outro lugar) que o avatar mudou — localStorage não é reativo.
      window.dispatchEvent(new Event('avento:avatar-changed'));
    };
    reader.readAsDataURL(file);
  };

  const renderConta = () => (
    <Body>
      <div className="profile-header">
        <div 
          className="profile-avatar" 
          onClick={() => fileInputRef.current?.click()}
          title="Alterar foto de perfil"
          style={{ cursor: 'pointer', overflow: 'hidden' }}
        >
          {avatarUrl ? (
            <img src={avatarUrl} alt="Avatar" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          ) : (
            getInitials(user?.displayName)
          )}
        </div>
        <input 
          type="file" 
          accept="image/*" 
          ref={fileInputRef} 
          style={{ display: 'none' }} 
          onChange={handleAvatarChange} 
        />
        <div className="profile-info">
          <h3>{user?.displayName || 'Usuário'}</h3>
          <p>{user?.email || '-'}</p>
          <span className="profile-badge">{user?.role || 'USER'}</span>
        </div>
      </div>
      <Footer style={{ marginTop: 'auto' }}>
        <DestructiveButton onClick={handleLogout}>Sair da Conta</DestructiveButton>
      </Footer>
    </Body>
  );

  const renderRangeSelector = () => (
    <RangeSelector role="tablist" aria-label="Período">
      {(['today', '7d', '30d'] as UsageRange[]).map(r => (
        <RangeButton
          key={r}
          type="button"
          $active={usageRange === r}
          aria-pressed={usageRange === r}
          onClick={() => setUsageRange(r)}
        >
          {r === 'today' ? 'Hoje' : r === '7d' ? '7 dias' : '30 dias'}
        </RangeButton>
      ))}
    </RangeSelector>
  );

  const renderUso = () => {
    const fmt = (n: number | undefined | null) => (typeof n === 'number' ? n : 0).toLocaleString('pt-BR');
    const rangeKey = (usageData?.range as UsageRange) ?? usageRange;
    const rangeLabel = RANGE_LABEL[rangeKey] ?? RANGE_LABEL['7d'];

    if (isLoadingUsage) {
      return (
        <Body>
          {renderRangeSelector()}
          <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>Carregando métricas...</p>
        </Body>
      );
    }

    const total = usageData?.total ?? 0;
    if (!usageData || total === 0) {
      return (
        <Body>
          {renderRangeSelector()}
          <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>
            Nenhum token consumido {rangeLabel}. Os tokens são uma métrica de custo computacional local
            (Ollama), não financeiro.
          </p>
        </Body>
      );
    }

    // Defesa: o backend pode ainda estar numa versão antiga (sem os campos novos) — nada aqui pode
    // acessar propriedade de undefined, senão o React derruba a tela inteira.
    const byDay = usageData.byDay ?? [];
    const byModel = usageData.byModel ?? [];
    const byChat = usageData.byChat ?? [];
    const chartHeight = 100;
    const chartWidth = 400;
    const maxTokens = Math.max(1, ...byDay.map(d => d?.total ?? 0));
    const slot = chartWidth / Math.max(1, byDay.length);
    const barWidth = Math.max(2, slot * 0.7);

    return (
      <Body>
        {renderRangeSelector()}

        <StatGrid>
          <StatBox>
            <span className="stat-value">{fmt(total)}</span>
            <span className="stat-label">Tokens no total</span>
          </StatBox>
          <StatBox>
            <span className="stat-value">{fmt(usageData.promptTotal)}</span>
            <span className="stat-label">Entrada (prompt)</span>
          </StatBox>
          <StatBox>
            <span className="stat-value">{fmt(usageData.completionTotal)}</span>
            <span className="stat-label">Saída (geração)</span>
          </StatBox>
          <StatBox>
            <span className="stat-value">{fmt(usageData.requestCount)}</span>
            <span className="stat-label">Requisições</span>
          </StatBox>
          {metricsData && typeof metricsData.avgDurationSecs === 'number' && metricsData.avgDurationSecs > 0 && (
            <StatBox>
              <span className="stat-value">{metricsData.avgDurationSecs}s</span>
              <span className="stat-label">Tempo médio de raciocínio</span>
            </StatBox>
          )}
        </StatGrid>

        {byDay.length > 0 && (
          <UsageCard>
            <h3>Uso diário ({rangeLabel})</h3>
            <BarChart viewBox={`0 0 ${chartWidth} 120`} preserveAspectRatio="none">
              <defs>
                <linearGradient id="barGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#66E6C8" />
                  <stop offset="100%" stopColor="#104E45" />
                </linearGradient>
              </defs>
              {byDay.map((day, index) => {
                const dayTotal = day?.total ?? 0;
                const dayLabel = String(day?.day ?? '');
                const barHeight = (dayTotal / maxTokens) * chartHeight;
                const x = index * slot + (slot - barWidth) / 2;
                const y = chartHeight - barHeight;
                return (
                  <g key={dayLabel || index}>
                    <rect x={x} y={y} width={barWidth} height={barHeight} fill="url(#barGradient)">
                      <title>{`${dayLabel}: ${fmt(dayTotal)} tokens`}</title>
                    </rect>
                    {byDay.length <= 10 && (
                      <text x={x + barWidth / 2} y={115} textAnchor="middle" fontSize="7">
                        {dayLabel.slice(8)}
                      </text>
                    )}
                  </g>
                );
              })}
            </BarChart>
          </UsageCard>
        )}

        {byModel.length > 0 && (
          <UsageCard>
            <h3>Por modelo</h3>
            <UsageTable>
              <thead>
                <tr>
                  <th>Modelo</th>
                  <th>Entrada</th>
                  <th>Saída</th>
                  <th>Total</th>
                </tr>
              </thead>
              <tbody>
                {byModel.map(m => (
                  <tr key={m.model}>
                    <td>{m.model}</td>
                    <td>{fmt(m.promptTokens)}</td>
                    <td>{fmt(m.completionTokens)}</td>
                    <td>{fmt(m.total)}</td>
                  </tr>
                ))}
              </tbody>
            </UsageTable>
          </UsageCard>
        )}

        {byChat.length > 0 && (
          <UsageCard>
            <h3>Conversas que mais consumiram</h3>
            <UsageTable>
              <thead>
                <tr>
                  <th>Conversa</th>
                  <th>Tokens</th>
                </tr>
              </thead>
              <tbody>
                {byChat.map(c => (
                  <tr key={c.chatId}>
                    <td>{c.title || `Conversa #${c.chatId}`}</td>
                    <td>{fmt(c.total)}</td>
                  </tr>
                ))}
              </tbody>
            </UsageTable>
          </UsageCard>
        )}
      </Body>
    );
  };

  const renderPreferencias = () => (
    <>
      <Body>
        {isLoadingSettings ? (
          <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>Carregando preferências...</p>
        ) : (
          <>
            <SettingRow>
              <label>
                <strong>Tema Visual</strong>
                <span>Modo escuro para ambientes com pouca luz.</span>
              </label>
              <ToggleSwitch 
                $active={isDarkMode} 
                onClick={toggleTheme}
                title={isDarkMode ? "Mudar para Claro" : "Mudar para Escuro"}
              />
            </SettingRow>
            <SettingRow>
              <label>
                <strong>Captura de Voz Local</strong>
                <span>Ativar microfone e transcrição (Whisper) no chat.</span>
              </label>
              <ToggleSwitch 
                $active={isVoiceEnabled} 
                onClick={() => handleToggleVoice(!isVoiceEnabled)}
                title={isVoiceEnabled ? "Desativar Microfone" : "Ativar Microfone"}
              />
            </SettingRow>
            <SettingRow>
              <label>
                <strong>Sintetizador de Voz (TTS)</strong>
                <span>Ativar geração local de áudio das respostas.</span>
              </label>
              <ToggleSwitch
                $active={ttsEnabled}
                onClick={() => setTtsEnabled(!ttsEnabled)}
                title={ttsEnabled ? "Desativar TTS" : "Ativar TTS"}
              />
            </SettingRow>
            <SettingRow>
              <label>
                <strong>Raciocínio (Thinking)</strong>
                <span>Mostra o modelo pensando antes de responder. Desligue para respostas mais rápidas e menos tokens gerados (bom para mockups e tarefas simples).</span>
              </label>
              <ToggleSwitch
                $active={thinkingEnabled}
                onClick={() => setThinkingEnabled(!thinkingEnabled)}
                title={thinkingEnabled ? "Desligar raciocínio" : "Ligar raciocínio"}
              />
            </SettingRow>
          </>
        )}
      </Body>
      <Footer>
        <DestructiveButton onClick={handleRestore} disabled={isLoadingSettings || isSaving}>
          Restaurar Padrões
        </DestructiveButton>
        <SaveButton onClick={handleSave} disabled={isLoadingSettings || isSaving}>
          {isSaving ? 'Salvando...' : 'Salvar preferências'}
        </SaveButton>
      </Footer>
    </>
  );

  const renderAgentes = () => (
    <Body>
      <MemoryIntro>
        Agentes especializados que o Avento usa para executar tarefas — ex.: um para backend, outro para
        testes, outro para UI. Cada agente tem sua própria persona e é escolhido por tarefa. A execução é
        sempre <strong>uma de cada vez</strong> (nunca dois ao mesmo tempo), pra não sobrecarregar a máquina.
      </MemoryIntro>

      <AgentForm onSubmit={handleCreateAgent}>
        <AgentField>
          <span>Nome</span>
          <input
            type="text"
            value={agentForm.name}
            placeholder="Ex.: Backend Java"
            onChange={(e) => setAgentForm({ ...agentForm, name: e.target.value })}
            disabled={agentBusy}
          />
        </AgentField>
        <AgentField>
          <span>Especialidade (ajuda o Avento a escolher o agente certo)</span>
          <input
            type="text"
            value={agentForm.specialty}
            placeholder="Ex.: APIs Spring Boot, JPA, segurança"
            onChange={(e) => setAgentForm({ ...agentForm, specialty: e.target.value })}
            disabled={agentBusy}
          />
        </AgentField>
        <AgentField>
          <span>Instruções (a persona — como este agente deve trabalhar)</span>
          <textarea
            value={agentForm.systemInstructions}
            placeholder="Ex.: Você é especialista em backend Java. Siga os padrões do projeto, escreva testes, mexa o mínimo possível."
            onChange={(e) => setAgentForm({ ...agentForm, systemInstructions: e.target.value })}
            disabled={agentBusy}
          />
        </AgentField>
        <AgentField>
          <span>Gatilhos (palavras-chave, separadas por vírgula — opcional)</span>
          <input
            type="text"
            value={agentForm.triggers}
            placeholder="Ex.: api, endpoint, controller, repository"
            onChange={(e) => setAgentForm({ ...agentForm, triggers: e.target.value })}
            disabled={agentBusy}
          />
        </AgentField>
        <AgentDefaultToggleRow>
          <input
            type="checkbox"
            checked={agentForm.isDefault}
            onChange={(e) => setAgentForm({ ...agentForm, isDefault: e.target.checked })}
            disabled={agentBusy}
          />
          Usar como agente padrão (fallback quando nenhum outro casa com a tarefa)
        </AgentDefaultToggleRow>
        <SaveButton type="submit" disabled={agentBusy || !agentForm.name.trim()}>
          {agentBusy ? 'Salvando...' : 'Criar agente'}
        </SaveButton>
      </AgentForm>

      <MemorySectionTitle>Seus agentes</MemorySectionTitle>
      {isLoadingAgents ? (
        <MemoryEmpty>Carregando agentes...</MemoryEmpty>
      ) : agents.length === 0 ? (
        <MemoryEmpty>Nenhum agente ainda. Crie o primeiro acima.</MemoryEmpty>
      ) : (
        <MemoryList>
          {agents.map((agent) => (
            <MemoryCard key={agent.id}>
              <div className="memory-content">
                <span className="memory-text">
                  {agent.name}
                  {agent.isDefault && <> <AgentDefaultBadge>padrão</AgentDefaultBadge></>}
                </span>
                <span className="memory-tag">{agent.specialty || 'sem especialidade'}</span>
              </div>
              <div className="memory-actions">
                <MemoryActionButton
                  $variant="delete"
                  onClick={() => handleDeleteAgent(agent.id)}
                  disabled={agentBusy}
                >
                  Excluir
                </MemoryActionButton>
              </div>
            </MemoryCard>
          ))}
        </MemoryList>
      )}
    </Body>
  );

  const renderMemoria = () => {
    const pending = memories.filter((memory) => memory.status === 'PENDING');
    const active = memories.filter((memory) => memory.status === 'ACTIVE');
    return (
      <Body>
        <MemoryIntro>
          Fatos e preferências que o Avento leva de uma conversa para outra. O modelo não aprende sozinho:
          o que estiver aqui como <strong>ativo</strong> é injetado no contexto das próximas conversas. As
          sugestões vêm do modelo e só passam a valer depois que você confirmar.
        </MemoryIntro>

        <MemoryAddRow>
          <input
            type="text"
            value={newMemory}
            placeholder="Ex.: Prefere respostas em PT-BR informal"
            onChange={(e) => setNewMemory(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleAddMemory(); }}
            disabled={memoryBusy}
          />
          <SaveButton onClick={handleAddMemory} disabled={memoryBusy || !newMemory.trim()}>
            Adicionar
          </SaveButton>
        </MemoryAddRow>

        {isLoadingMemory ? (
          <MemoryEmpty>Carregando memória...</MemoryEmpty>
        ) : (
          <>
            {pending.length > 0 && (
              <>
                <MemorySectionTitle>
                  Sugestões do modelo <span>({pending.length} aguardando você)</span>
                </MemorySectionTitle>
                <MemoryList>
                  {pending.map((memory) => (
                    <MemoryCard key={memory.id} $pending>
                      <div className="memory-content">
                        <span className="memory-text">{memory.content}</span>
                        <span className="memory-tag">{memory.category}</span>
                      </div>
                      <div className="memory-actions">
                        <MemoryActionButton
                          $variant="confirm"
                          onClick={() => handleConfirmMemory(memory.id)}
                          disabled={memoryBusy}
                        >
                          Confirmar
                        </MemoryActionButton>
                        <MemoryActionButton
                          $variant="delete"
                          onClick={() => handleDeleteMemory(memory.id)}
                          disabled={memoryBusy}
                        >
                          Descartar
                        </MemoryActionButton>
                      </div>
                    </MemoryCard>
                  ))}
                </MemoryList>
              </>
            )}

            <MemorySectionTitle>Memória ativa</MemorySectionTitle>
            {active.length === 0 ? (
              <MemoryEmpty>Nada guardado ainda. Adicione um fato acima ou espere o Avento sugerir.</MemoryEmpty>
            ) : (
              <MemoryList>
                {active.map((memory) => (
                  <MemoryCard key={memory.id}>
                    <div className="memory-content">
                      <span className="memory-text">{memory.content}</span>
                      <span className="memory-tag">
                        {memory.category}{memory.origin === 'SUGGESTED' ? ' · sugerido' : ''}
                      </span>
                    </div>
                    <div className="memory-actions">
                      <MemoryActionButton
                        $variant="delete"
                        onClick={() => handleDeleteMemory(memory.id)}
                        disabled={memoryBusy}
                      >
                        Esquecer
                      </MemoryActionButton>
                    </div>
                  </MemoryCard>
                ))}
              </MemoryList>
            )}
          </>
        )}
      </Body>
    );
  };

  const selectStyle = {
    background: 'rgba(16, 42, 38, 0.55)',
    border: '1px solid rgba(255, 255, 255, 0.12)',
    borderRadius: '8px',
    color: '#F2FFFB',
    padding: '9px 11px',
    fontSize: '0.88rem',
    outline: 'none',
  } as const;

  const PROVIDER_KINDS = [
    { kind: 'OLLAMA', icon: '🖥️', title: 'Ollama', hint: 'Local ou em outra máquina da rede', url: 'http://127.0.0.1:11434', key: false },
    { kind: 'OPENAI_COMPATIBLE', icon: '⚡', title: 'Compatível com OpenAI', hint: 'DGX, vLLM, LM Studio, TGI', url: 'http://192.168.15.2:8000', key: false },
    { kind: 'GEMINI', icon: '☁️', title: 'Google Gemini', hint: 'API do Google', url: 'https://generativelanguage.googleapis.com', key: true },
    { kind: 'ANTHROPIC', icon: '☁️', title: 'Anthropic', hint: 'API da Anthropic', url: 'https://api.anthropic.com', key: true },
  ];

  const activeKind = PROVIDER_KINDS.find(k => k.kind === providerSettings.providerKind) || PROVIDER_KINDS[0];

  // O modelo salvo entra na lista mesmo que a consulta ao provedor tenha falhado. Sem isso, o
  // <select> fica com um value que nao casa com nenhuma <option>: o navegador mostra a primeira,
  // o estado guarda outra, e salvar regravava o modelo antigo sem a pessoa perceber.
  // Remoto de verdade: tipo nao-local E, quando exige chave, com chave gravada. So o tipo nao basta
  // — sem chave nada funciona, e mostrar "em uso" ali seria a mesma mentira que perseguimos hoje.
  const providerIsRemote =
    activeKind.kind !== 'OLLAMA' && (!activeKind.key || Boolean(providerSettings.apiKeyMasked));

  const modelOptions = providerSettings.selectedModel && !providerModels.includes(providerSettings.selectedModel)
    ? [providerSettings.selectedModel, ...providerModels]
    : providerModels;

  const renderProvedores = () => (
    <Body style={{ overflowY: 'auto', paddingRight: '4px' }}>
      {isLoadingProviders ? (
        <p style={{ color: '#9FB8B1', padding: '16px 0', fontSize: '0.88rem' }}>Carregando provedores...</p>
      ) : (
        <>
          <ProviderSectionTitle>
            <h4>Provedor de IA</h4>
            {activeKind.key ? <BadgePrivate>Privado do Seu Login</BadgePrivate> : <BadgeShared>Rede / Local</BadgeShared>}
          </ProviderSectionTitle>

          {/* Estado ativo em destaque: sem isto, quem configurou nao tinha como saber de onde a
              resposta vinha — foi a confusao que custou horas de depuracao. */}
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: '10px',
              background: providerIsRemote ? 'rgba(79, 209, 180, 0.12)' : 'rgba(255, 255, 255, 0.05)',
              border: `1px solid ${providerIsRemote ? 'rgba(79, 209, 180, 0.4)' : 'rgba(255, 255, 255, 0.12)'}`,
              borderRadius: '10px', padding: '10px 14px', margin: '0 0 14px',
            }}
          >
            <span style={{ fontSize: '1.05rem' }}>{providerIsRemote ? '☁️' : '🖥️'}</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: '0.84rem', fontWeight: 700, color: providerIsRemote ? '#4FD1B4' : '#F2FFFB' }}>
                Em uso: {activeKind.title}
                {providerSettings.selectedModel ? ` · ${providerSettings.selectedModel}` : ''}
              </div>
              <div style={{ fontSize: '0.76rem', color: '#9FB8B1' }}>
                {providerIsRemote
                  ? 'As conversas saem desta máquina e vão para o provedor.'
                  : 'Tudo roda nesta máquina; nada sai daqui.'}
              </div>
            </div>
          </div>

          <p style={{ color: '#9FB8B1', fontSize: '0.8rem', margin: '0 0 12px' }}>
            Escolha de onde vêm as respostas. O sistema passa a listar os modelos desse provedor e a
            enviar as conversas para ele.
          </p>

          <ProviderGrid>
            {PROVIDER_KINDS.map(option => (
              <ProviderCard
                key={option.kind}
                $active={providerSettings.providerKind === option.kind}
                onClick={() => setProviderSettings({
                  ...providerSettings,
                  providerKind: option.kind,
                  // Troca o endereco junto: manter a URL do provedor anterior geraria erro
                  // silencioso de "nao lista modelos" sem a pessoa entender por que.
                  baseUrl: option.url,
                  selectedModel: '',
                })}
              >
                <strong>{option.icon} {option.title}</strong>
                <span>{option.hint}</span>
              </ProviderCard>
            ))}
          </ProviderGrid>

          <SettingRow style={{ borderBottom: 'none', paddingBottom: '0' }}>
            <AgentField style={{ flex: 1 }}>
              <span>Endereço do provedor</span>
              <input
                type="text"
                value={providerSettings.baseUrl}
                onChange={e => setProviderSettings({ ...providerSettings, baseUrl: e.target.value })}
                placeholder={activeKind.url}
              />
            </AgentField>
          </SettingRow>

          {activeKind.key && providerSettings.apiKeyMasked && !editingApiKey && (
            <div
              style={{
                display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap',
                background: 'rgba(79, 209, 180, 0.10)',
                border: '1px solid rgba(79, 209, 180, 0.35)',
                borderRadius: '10px', padding: '12px 14px', margin: '8px 0',
              }}
            >
              <span style={{ fontSize: '1.1rem' }}>🔒</span>
              <div style={{ flex: 1, minWidth: '180px' }}>
                <div style={{ fontSize: '0.86rem', fontWeight: 700, color: '#4FD1B4' }}>
                  Conectado a {activeKind.title}
                </div>
                <div style={{ fontSize: '0.78rem', color: '#9FB8B1', fontFamily: 'ui-monospace, monospace' }}>
                  {providerSettings.apiKeyMasked}
                </div>
              </div>
              <TestButton type="button" onClick={() => setEditingApiKey(true)}>Editar chave</TestButton>
              <TestButton
                type="button"
                onClick={handleDisconnectProvider}
                disabled={isSaving}
                style={{ borderColor: 'rgba(244, 130, 130, 0.5)', color: '#F48282' }}
              >
                Remover
              </TestButton>
            </div>
          )}

          {activeKind.key && (!providerSettings.apiKeyMasked || editingApiKey) && (
            <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
              <AgentField style={{ flex: 1 }}>
                <span>Chave de API</span>
                <input
                  type="password"
                  value={providerSettings.apiKeyInput}
                  onChange={e => setProviderSettings({ ...providerSettings, apiKeyInput: e.target.value })}
                  placeholder={providerSettings.apiKeyMasked || 'Cole sua chave de API aqui...'}
                  autoFocus={editingApiKey}
                />
                <span style={{ fontSize: '0.74rem', color: '#9FB8B1', marginTop: '4px' }}>
                  {editingApiKey
                    ? 'Deixe em branco e salve para manter a chave atual.'
                    : 'A chave fica guardada cifrada e nunca volta para a tela.'}
                </span>
              </AgentField>
            </SettingRow>
          )}

          <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
            <AgentField style={{ flex: 1 }}>
              <span>Modelo</span>
              {/* Select, nunca texto livre: os nomes validos sao os que o provedor devolve, e
                  digitar de cabeca foi como um nome inexistente acabou salvo e deu 404. */}
              <select
                value={providerSettings.selectedModel}
                onChange={e => persistSelectedModel(e.target.value)}
                disabled={modelOptions.length === 0}
                style={{ ...selectStyle, color: modelOptions.length === 0 ? '#6F8A83' : '#F2FFFB' }}
              >
                {modelOptions.length === 0 ? (
                  <option value="">Teste a conexão para carregar os modelos</option>
                ) : (
                  modelOptions.map(model => <option key={model} value={model}>{model}</option>)
                )}
              </select>
              <span style={{ fontSize: '0.74rem', color: '#9FB8B1', marginTop: '4px' }}>
                {providerModels.length > 0
                  ? `${providerModels.length} modelo(s) confirmados pelo provedor.`
                  : 'Nenhum modelo confirmado ainda — clique em "Testar conexão" para carregar a lista real.'}
              </span>
            </AgentField>
          </SettingRow>

          {/* Todo modelo do provedor sai daqui: trocar modelo de visao ou de imagem nao deveria
              exigir editar YAML e reiniciar o backend. Vazio significa "usa o padrao do sistema". */}
          {providerModels.length > 0 && (
            <>
              <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
                <AgentField style={{ flex: 1 }}>
                  <span>Modelo para ler imagem anexada</span>
                  <select
                    value={providerSettings.visionModel}
                    onChange={e => setProviderSettings({ ...providerSettings, visionModel: e.target.value })}
                    style={selectStyle}
                  >
                    <option value="">Usar o padrão do sistema</option>
                    {providerModels.map(model => <option key={model} value={model}>{model}</option>)}
                  </select>
                </AgentField>
              </SettingRow>

              <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
                <AgentField style={{ flex: 1 }}>
                  <span>Modelo para gerar imagem</span>
                  <select
                    value={providerSettings.imageModel}
                    onChange={e => setProviderSettings({ ...providerSettings, imageModel: e.target.value })}
                    style={selectStyle}
                  >
                    <option value="">Usar o padrão do sistema (ComfyUI no modo local)</option>
                    {providerModels.map(model => <option key={model} value={model}>{model}</option>)}
                  </select>
                </AgentField>
              </SettingRow>

              <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
                <AgentField style={{ flex: 1 }}>
                  <span>Modelo do planejador</span>
                  <select
                    value={providerSettings.plannerModel}
                    onChange={e => setProviderSettings({ ...providerSettings, plannerModel: e.target.value })}
                    style={selectStyle}
                  >
                    <option value="">Usar o mesmo da conversa</option>
                    {providerModels.map(model => <option key={model} value={model}>{model}</option>)}
                  </select>
                </AgentField>
              </SettingRow>

              <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
                <AgentField style={{ flex: 1 }}>
                  <span>Modelo de embedding</span>
                  <select
                    value={providerSettings.embeddingModel}
                    onChange={e => setProviderSettings({ ...providerSettings, embeddingModel: e.target.value })}
                    style={selectStyle}
                  >
                    <option value="">Usar o padrão do sistema</option>
                    {providerModels.map(model => <option key={model} value={model}>{model}</option>)}
                  </select>
                  <span style={{ fontSize: '0.74rem', color: '#9FB8B1', marginTop: '4px' }}>
                    Usado na classificação de intenção — decide quais ferramentas o modelo enxerga.
                  </span>
                </AgentField>
              </SettingRow>
            </>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginTop: '12px', marginBottom: '20px' }}>
            <TestButton type="button" onClick={handleTestProvider} disabled={testLanStatus.loading}>
              {testLanStatus.loading ? 'Testando...' : 'Testar conexão'}
            </TestButton>

            {testLanStatus.message && (
              <TestStatusPill $success={testLanStatus.success} $error={!testLanStatus.success}>
                {testLanStatus.message}
              </TestStatusPill>
            )}
          </div>

          {activeKind.kind !== 'OLLAMA' && (
            <p style={{ color: '#9FB8B1', fontSize: '0.78rem', margin: '0 0 8px' }}>
              Neste provedor as ferramentas locais (arquivos, terminal, imagem) ficam indisponíveis —
              elas ainda não foram traduzidas para o formato dele.
            </p>
          )}

          <Footer style={{ marginTop: 'auto', paddingTop: '16px' }}>
            <SaveButton onClick={handleSaveProviders} disabled={isSaving || !providerSettings.selectedModel}>
              {isSaving ? 'Salvando...' : 'Salvar provedor'}
            </SaveButton>
          </Footer>
        </>
      )}
    </Body>
  );

  return createPortal(
    <ModalBackdrop onClick={onClose}>
      <ModalContainer
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <Header>
          <h2 id="settings-modal-title">Sua Conta e Configurações</h2>
          <button onClick={onClose} title="Fechar modal">
            <X size={20} />
          </button>
        </Header>
        
        <Tabs>
          <TabButton $active={activeTab === 'conta'} onClick={() => setActiveTab('conta')}>Conta</TabButton>
          <TabButton $active={activeTab === 'uso'} onClick={() => setActiveTab('uso')}>Uso de Tokens</TabButton>
          <TabButton $active={activeTab === 'provedores'} onClick={() => setActiveTab('provedores')}>Modelos & Provedores</TabButton>
          <TabButton $active={activeTab === 'agentes'} onClick={() => setActiveTab('agentes')}>Agentes</TabButton>
          <TabButton $active={activeTab === 'memoria'} onClick={() => setActiveTab('memoria')}>Memória</TabButton>
          <TabButton $active={activeTab === 'preferencias'} onClick={() => setActiveTab('preferencias')}>Preferências</TabButton>
        </Tabs>

        {activeTab === 'conta' && renderConta()}
        {activeTab === 'uso' && renderUso()}
        {activeTab === 'provedores' && renderProvedores()}
        {activeTab === 'agentes' && renderAgentes()}
        {activeTab === 'memoria' && renderMemoria()}
        {activeTab === 'preferencias' && renderPreferencias()}

      </ModalContainer>
    </ModalBackdrop>,
    document.body
  );
}
