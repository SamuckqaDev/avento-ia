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

  const [providerSettings, setProviderSettings] = useState({
    systemServerUrl: 'http://127.0.0.1:11434',
    systemServerType: 'OLLAMA',
    systemDefaultModel: 'granite4.1:8b',
    usePersonalCloud: false,
    personalCloudProvider: 'GEMINI',
    personalCloudApiKeyMasked: '',
    personalCloudApiKeyInput: '',
    personalCloudModel: 'gemini-2.5-flash',
  });
  const [isLoadingProviders, setIsLoadingProviders] = useState(false);
  const [testLanStatus, setTestLanStatus] = useState<{ success?: boolean; message?: string; loading?: boolean }>({});
  const [testCloudStatus, setTestCloudStatus] = useState<{ success?: boolean; message?: string; loading?: boolean }>({});
  
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
        systemServerUrl: string;
        systemServerType: string;
        systemDefaultModel: string;
        usePersonalCloud: boolean;
        personalCloudProvider: string;
        personalCloudApiKeyMasked: string;
        personalCloudModel: string;
      }>('/api/ai/providers');
      if (data) {
        setProviderSettings(prev => ({
          ...prev,
          systemServerUrl: data.systemServerUrl || 'http://127.0.0.1:11434',
          systemServerType: data.systemServerType || 'OLLAMA',
          systemDefaultModel: data.systemDefaultModel || 'granite4.1:8b',
          usePersonalCloud: data.usePersonalCloud || false,
          personalCloudProvider: data.personalCloudProvider || 'GEMINI',
          personalCloudApiKeyMasked: data.personalCloudApiKeyMasked || '',
          personalCloudModel: data.personalCloudModel || 'gemini-2.5-flash',
        }));
      }
    } catch (error) {
      console.error("Erro ao carregar configurações de provedores", error);
    } finally {
      setIsLoadingProviders(false);
    }
  };

  const handleTestLan = async () => {
    setTestLanStatus({ loading: true });
    try {
      const { data } = await api.post<{ success: boolean; message: string; latencyMs: number }>('/api/ai/providers/test', {
        targetType: 'SYSTEM_LAN',
        serverUrl: providerSettings.systemServerUrl,
        serverType: providerSettings.systemServerType,
        modelName: providerSettings.systemDefaultModel,
      });
      setTestLanStatus({ success: data.success, message: data.message, loading: false });
    } catch (error: any) {
      setTestLanStatus({ success: false, message: 'Falha no teste de rede local.', loading: false });
    }
  };

  const handleTestCloud = async () => {
    setTestCloudStatus({ loading: true });
    try {
      const apiKey = providerSettings.personalCloudApiKeyInput || providerSettings.personalCloudApiKeyMasked;
      const { data } = await api.post<{ success: boolean; message: string; latencyMs: number }>('/api/ai/providers/test', {
        targetType: 'PERSONAL_CLOUD',
        serverUrl: providerSettings.personalCloudProvider,
        apiKey,
        modelName: providerSettings.personalCloudModel,
      });
      setTestCloudStatus({ success: data.success, message: data.message, loading: false });
    } catch (error: any) {
      setTestCloudStatus({ success: false, message: 'Falha na validação da chave cloud.', loading: false });
    }
  };

  const handleSaveProviders = async () => {
    setIsSaving(true);
    try {
      await api.put('/api/ai/providers', {
        systemServerUrl: providerSettings.systemServerUrl,
        systemServerType: providerSettings.systemServerType,
        systemDefaultModel: providerSettings.systemDefaultModel,
        usePersonalCloud: providerSettings.usePersonalCloud,
        personalCloudProvider: providerSettings.personalCloudProvider,
        personalCloudApiKey: providerSettings.personalCloudApiKeyInput || undefined,
        personalCloudModel: providerSettings.personalCloudModel,
      });
      await loadProviders();
    } catch (error) {
      console.error("Erro ao salvar configurações de provedor", error);
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

  const renderProvedores = () => (
    <Body style={{ overflowY: 'auto', paddingRight: '4px' }}>
      {isLoadingProviders ? (
        <p style={{ color: '#9FB8B1', padding: '16px 0', fontSize: '0.88rem' }}>Carregando provedores e servidores de IA...</p>
      ) : (
        <>
          <ProviderSectionTitle>
            <h4>🖥️⚡ Servidor da Casa / Rede Local</h4>
            <BadgeShared>Compartilhado com Todos os Logins</BadgeShared>
          </ProviderSectionTitle>

      <ProviderGrid>
        <ProviderCard
          $active={providerSettings.systemServerUrl.includes('127.0.0.1') || providerSettings.systemServerUrl.includes('localhost')}
          onClick={() => setProviderSettings({ ...providerSettings, systemServerUrl: 'http://127.0.0.1:11434' })}
        >
          <strong>🖥️ Host Local</strong>
          <span>Ollama neste computador (127.0.0.1:11434)</span>
        </ProviderCard>

        <ProviderCard
          $active={!providerSettings.systemServerUrl.includes('127.0.0.1') && !providerSettings.systemServerUrl.includes('localhost')}
          onClick={() => setProviderSettings({ ...providerSettings, systemServerUrl: 'http://192.168.1.100:11434' })}
        >
          <strong>⚡ Servidor LAN Dedicado</strong>
          <span>AMD AI Max / NVIDIA DGX na rede</span>
        </ProviderCard>
      </ProviderGrid>

      <SettingRow style={{ borderBottom: 'none', paddingBottom: '0' }}>
        <AgentField style={{ flex: 1 }}>
          <span>URL do Servidor Ollama ou Workstation LAN</span>
          <input
            type="text"
            value={providerSettings.systemServerUrl}
            onChange={e => setProviderSettings({ ...providerSettings, systemServerUrl: e.target.value })}
            placeholder="http://127.0.0.1:11434 ou http://192.168.1.100:11434"
          />
        </AgentField>
      </SettingRow>

      <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
        <AgentField style={{ flex: 1 }}>
          <span>Modelo Padrão da Máquina Local/Rede</span>
          <input
            type="text"
            value={providerSettings.systemDefaultModel}
            onChange={e => setProviderSettings({ ...providerSettings, systemDefaultModel: e.target.value })}
            placeholder="granite4.1:8b, llama3.3:70b, deepseek-r1:70b..."
          />
        </AgentField>
      </SettingRow>

      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginTop: '8px', marginBottom: '20px' }}>
        <TestButton type="button" onClick={handleTestLan} disabled={testLanStatus.loading}>
          {testLanStatus.loading ? 'Testando...' : 'Testar Conexão da Rede Local'}
        </TestButton>

        {testLanStatus.message && (
          <TestStatusPill $success={testLanStatus.success} $error={!testLanStatus.success}>
            {testLanStatus.message}
          </TestStatusPill>
        )}
      </div>

      <ProviderSectionTitle style={{ borderTop: '1px solid rgba(255, 255, 255, 0.08)', paddingTop: '16px' }}>
        <h4>☁️ Provedor na Nuvem Pessoal</h4>
        <BadgePrivate>Privado do Seu Login</BadgePrivate>
      </ProviderSectionTitle>

      <SettingRow>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
          <strong style={{ fontSize: '0.9rem', color: '#F2FFFB' }}>Usar meu Provedor Cloud Pessoal</strong>
          <span style={{ fontSize: '0.78rem', color: '#9FB8B1' }}>
            Sobrepõe o servidor da casa usando sua chave pessoal de API da Nuvem (ex: Google Gemini).
          </span>
        </div>
        <ToggleSwitch
          type="button"
          $active={providerSettings.usePersonalCloud}
          onClick={() => setProviderSettings({ ...providerSettings, usePersonalCloud: !providerSettings.usePersonalCloud })}
        >
          <span />
        </ToggleSwitch>
      </SettingRow>

      {providerSettings.usePersonalCloud && (
        <>
          <SettingRow style={{ borderBottom: 'none', paddingBottom: '0' }}>
            <AgentField style={{ flex: 1 }}>
              <span>Provedor Cloud</span>
              <select
                value={providerSettings.personalCloudProvider}
                onChange={e => setProviderSettings({ ...providerSettings, personalCloudProvider: e.target.value })}
                style={{
                  background: 'rgba(16, 42, 38, 0.55)',
                  border: '1px solid rgba(255, 255, 255, 0.12)',
                  borderRadius: '8px',
                  color: '#F2FFFB',
                  padding: '9px 11px',
                  fontSize: '0.88rem',
                  outline: 'none',
                }}
              >
                <option value="GEMINI">Google Gemini</option>
                <option value="OPENAI">OpenAI</option>
              </select>
            </AgentField>
          </SettingRow>

          <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
            <AgentField style={{ flex: 1 }}>
              <span>Chave de API Cloud (API Key)</span>
              <input
                type="password"
                value={providerSettings.personalCloudApiKeyInput}
                onChange={e => setProviderSettings({ ...providerSettings, personalCloudApiKeyInput: e.target.value })}
                placeholder={providerSettings.personalCloudApiKeyMasked || 'Cole sua chave de API aqui...'}
              />
            </AgentField>
          </SettingRow>

          <SettingRow style={{ borderBottom: 'none', paddingTop: '8px' }}>
            <AgentField style={{ flex: 1 }}>
              <span>Modelo Cloud Desejado</span>
              <input
                type="text"
                value={providerSettings.personalCloudModel}
                onChange={e => setProviderSettings({ ...providerSettings, personalCloudModel: e.target.value })}
                placeholder="gemini-2.5-flash, gemini-2.5-pro, gpt-4o..."
              />
            </AgentField>
          </SettingRow>

          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginTop: '8px', marginBottom: '12px' }}>
            <TestButton type="button" onClick={handleTestCloud} disabled={testCloudStatus.loading}>
              {testCloudStatus.loading ? 'Testando...' : 'Testar Chave Cloud'}
            </TestButton>

            {testCloudStatus.message && (
              <TestStatusPill $success={testCloudStatus.success} $error={!testCloudStatus.success}>
                {testCloudStatus.message}
              </TestStatusPill>
            )}
          </div>
        </>
      )}

      <Footer style={{ marginTop: 'auto', paddingTop: '16px' }}>
        <SaveButton onClick={handleSaveProviders} disabled={isSaving}>
          {isSaving ? 'Salvando...' : 'Salvar Provedores'}
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
