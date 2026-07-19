import { useState, useEffect } from 'react';
import { X } from '@phosphor-icons/react';
import { 
  ModalBackdrop, ModalContainer, Header, Body, SettingRow, 
  ToggleSwitch, Footer, DestructiveButton, SaveButton 
} from './styles';
import { api } from '../../../services/apiClient';

interface SettingsModalProps {
  onClose: () => void;
}

export function SettingsModal({ onClose }: SettingsModalProps) {
  const [ttsEnabled, setTtsEnabled] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    const loadSettings = async () => {
      try {
        const { data } = await api.get<{ ttsEnabled: boolean }>('/api/settings');
        setTtsEnabled(data.ttsEnabled || false);
        setIsLoading(false);
      } catch (error) {
        console.error("Erro ao carregar configurações", error);
        setIsLoading(false);
      }
    };
    loadSettings();
  }, []);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await api.put('/api/settings', { ttsEnabled });
      setIsSaving(false);
      onClose();
    } catch (error) {
      console.error("Erro ao salvar configurações", error);
      setIsSaving(false);
    }
  };

  const handleRestore = () => {
    setTtsEnabled(false);
  };

  return (
    <ModalBackdrop onClick={onClose}>
      <ModalContainer onClick={(e) => e.stopPropagation()}>
        <Header>
          <h2>Preferências Locais</h2>
          <button onClick={onClose} title="Fechar modal">
            <X size={20} />
          </button>
        </Header>
        
        <Body>
          {isLoading ? (
            <p style={{ color: '#9FB8B1', fontSize: '0.9rem' }}>Carregando preferências...</p>
          ) : (
            <>
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
          <DestructiveButton onClick={handleRestore} disabled={isLoading || isSaving}>
            Restaurar Padrões
          </DestructiveButton>
          <SaveButton onClick={handleSave} disabled={isLoading || isSaving}>
            {isSaving ? 'Salvando...' : 'Salvar preferências'}
          </SaveButton>
        </Footer>
      </ModalContainer>
    </ModalBackdrop>
  );
}
