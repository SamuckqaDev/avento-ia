import { useState, useEffect, useRef, ChangeEvent } from 'react';
import { X } from '@phosphor-icons/react';
import { 
  ModalBackdrop, ModalContainer, Header, Body, SettingRow, 
  ToggleSwitch, Footer, DestructiveButton, SaveButton,
  Tabs, TabButton, UsageCard, BarChart, UsageTable
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
  date: string;
  totalTokens: number;
}

interface ModelTotal {
  model: string;
  totalTokens: number;
}

interface UsageSummary {
  total: number;
  byModel: ModelTotal[];
  byDay: DayTotal[];
}

export function SettingsModal({ 
  onClose,
  isDarkMode,
  toggleTheme,
  isVoiceEnabled,
  handleToggleVoice
}: SettingsModalProps) {
  const [activeTab, setActiveTab] = useState<'conta' | 'uso' | 'preferencias'>('conta');
  const [ttsEnabled, setTtsEnabled] = useState(false);
  const [isLoadingSettings, setIsLoadingSettings] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  
  const [usageData, setUsageData] = useState<UsageSummary | null>(null);
  const [isLoadingUsage, setIsLoadingUsage] = useState(true);
  
  const [avatarUrl, setAvatarUrl] = useState<string>(() => localStorage.getItem('avento_avatar_url') || '');
  const fileInputRef = useRef<HTMLInputElement>(null);
  
  const { user, logout } = useAuth();

  useEffect(() => {
    if (activeTab === 'preferencias') {
      const loadSettings = async () => {
        try {
          const { data } = await api.get<{ ttsEnabled: boolean }>('/api/settings');
          setTtsEnabled(data.ttsEnabled || false);
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
          const { data } = await api.get<UsageSummary>('/api/usage/summary?range=7d');
          setUsageData(data);
        } catch (error) {
          console.error("Erro ao carregar uso de tokens", error);
        } finally {
          setIsLoadingUsage(false);
        }
      };
      loadUsage();
    }
  }, [activeTab]);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await api.put('/api/settings', { ttsEnabled });
      onClose();
    } catch (error) {
      console.error("Erro ao salvar configurações", error);
    } finally {
      setIsSaving(false);
    }
  };

  const handleRestore = () => {
    setTtsEnabled(false);
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

  const renderUso = () => {
    if (isLoadingUsage) {
      return <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>Carregando métricas...</p>;
    }
    
    if (!usageData) {
      return <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>Nenhum dado encontrado.</p>;
    }

    const maxTokens = Math.max(1, ...usageData.byDay.map(d => d.totalTokens));
    const chartHeight = 100;

    return (
      <Body>
        <UsageCard>
          <h3>Uso Total</h3>
          <p>{usageData.total.toLocaleString()} tokens consumidos nos últimos 7 dias</p>
        </UsageCard>

        {usageData.byDay.length > 0 && (
          <UsageCard>
            <h3>Uso Diário</h3>
            <BarChart viewBox="0 0 400 120" preserveAspectRatio="none">
              <defs>
                <linearGradient id="barGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#66E6C8" />
                  <stop offset="100%" stopColor="#104E45" />
                </linearGradient>
              </defs>
              {usageData.byDay.map((day, index) => {
                const barHeight = (day.totalTokens / maxTokens) * chartHeight;
                const barWidth = 40;
                const x = index * (barWidth + 10);
                const y = chartHeight - barHeight;
                return (
                  <g key={day.date}>
                    <rect x={x} y={y} width={barWidth} height={barHeight}>
                      <title>{`${day.date}: ${day.totalTokens} tokens`}</title>
                    </rect>
                    <text x={x + barWidth / 2} y={115} textAnchor="middle">{day.date.substring(5)}</text>
                  </g>
                );
              })}
            </BarChart>
          </UsageCard>
        )}

        {usageData.byModel.length > 0 && (
          <UsageCard>
            <h3>Uso por Modelo</h3>
            <UsageTable>
              <thead>
                <tr>
                  <th>Modelo</th>
                  <th>Tokens</th>
                </tr>
              </thead>
              <tbody>
                {usageData.byModel.map(model => (
                  <tr key={model.model}>
                    <td>{model.model}</td>
                    <td>{model.totalTokens.toLocaleString()}</td>
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

  return (
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
          <TabButton $active={activeTab === 'preferencias'} onClick={() => setActiveTab('preferencias')}>Preferências</TabButton>
        </Tabs>
        
        {activeTab === 'conta' && renderConta()}
        {activeTab === 'uso' && renderUso()}
        {activeTab === 'preferencias' && renderPreferencias()}

      </ModalContainer>
    </ModalBackdrop>
  );
}
