import { useState, useEffect, useRef, ChangeEvent } from 'react';
import { createPortal } from 'react-dom';
import { X } from '@phosphor-icons/react';
import {
  ModalBackdrop, ModalContainer, Header, Body, SettingRow,
  ToggleSwitch, Footer, DestructiveButton, SaveButton,
  Tabs, TabButton, UsageCard, BarChart, UsageTable,
  RangeSelector, RangeButton, StatGrid, StatBox,
  MemoryIntro, MemoryAddRow, MemorySectionTitle, MemoryList,
  MemoryCard, MemoryActionButton, MemoryEmpty
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
  const [activeTab, setActiveTab] = useState<'conta' | 'uso' | 'preferencias' | 'memoria'>('conta');
  const [ttsEnabled, setTtsEnabled] = useState(false);
  const [thinkingEnabled, setThinkingEnabled] = useState(true);
  const [isLoadingSettings, setIsLoadingSettings] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  
  const [usageData, setUsageData] = useState<UsageSummary | null>(null);
  const [isLoadingUsage, setIsLoadingUsage] = useState(true);
  const [usageRange, setUsageRange] = useState<UsageRange>('7d');
  
  const [avatarUrl, setAvatarUrl] = useState<string>(() => localStorage.getItem('avento_avatar_url') || '');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [memories, setMemories] = useState<MemoryItem[]>([]);
  const [isLoadingMemory, setIsLoadingMemory] = useState(true);
  const [newMemory, setNewMemory] = useState('');
  const [memoryBusy, setMemoryBusy] = useState(false);
  
  const { user, logout } = useAuth();

  useEffect(() => {
    if (activeTab === 'preferencias') {
      const loadSettings = async () => {
        try {
          const { data } = await api.get<{ ttsEnabled: boolean; thinkingEnabled: boolean }>('/api/settings');
          setTtsEnabled(data.ttsEnabled || false);
          setThinkingEnabled(data.thinkingEnabled ?? true);
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

  return createPortal(
    <ModalBackdrop onClick={onClose}>
      <ModalContainer onClick={(e) => e.stopPropagation()}>
        <Header>
          <h2>Sua Conta e Configurações</h2>
          <button onClick={onClose} title="Fechar modal">
            <X size={20} />
          </button>
        </Header>
        
        <Tabs>
          <TabButton $active={activeTab === 'conta'} onClick={() => setActiveTab('conta')}>Conta</TabButton>
          <TabButton $active={activeTab === 'uso'} onClick={() => setActiveTab('uso')}>Uso de Tokens</TabButton>
          <TabButton $active={activeTab === 'memoria'} onClick={() => setActiveTab('memoria')}>Memória</TabButton>
          <TabButton $active={activeTab === 'preferencias'} onClick={() => setActiveTab('preferencias')}>Preferências</TabButton>
        </Tabs>

        {activeTab === 'conta' && renderConta()}
        {activeTab === 'uso' && renderUso()}
        {activeTab === 'memoria' && renderMemoria()}
        {activeTab === 'preferencias' && renderPreferencias()}

      </ModalContainer>
    </ModalBackdrop>,
    document.body
  );
}
