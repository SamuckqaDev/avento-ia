import { useMemo, useState } from 'react';
import {
  Container,
  Box,
  TextArea,
  ActionBar,
  MicButton,
  RealtimeVoiceButton,
  SendButton,
  VoiceStatus,
  AttachButton,
  HiddenFileInput,
  AttachmentTray,
  AttachmentChip,
  RemoveAttachmentButton,
  AudioLevelMeter,
  AudioLevelBar,
  QueueBar,
  QueueLabel,
  QueueChip,
  SkillSuggestions,
  SkillSuggestionItem,
  SkillSuggestionsHint,
} from './styles';
import { FileText, Microphone, Paperclip, SpinnerGap, StopCircle, PaperPlaneRight, ChatCircleDots, ImageSquare, X } from '@phosphor-icons/react';
import type { DocumentAttachment, ImageAttachment } from '../MessageBubble';
import type { QueuedMessage } from '../../../pages/Home';
import type { Skill } from '../../../hooks/useSkills';

const AUDIO_LEVEL_BAR_MULTIPLIERS = [0.6, 1, 1.3, 1, 0.6];
const QUEUE_PREVIEW_LENGTH = 60;

interface InputAreaProps {
  inputValue: string;
  setInputValue: (val: string) => void;
  handleSend: (text?: string) => void;
  onStop: () => void;
  isGenerating: boolean;
  isRecording: boolean;
  isRealtimeVoiceActive: boolean;
  isRealtimeListening: boolean;
  realtimeTranscript: string;
  audioLevel: number;
  speechRecognitionSupported: boolean;
  imageAttachments: ImageAttachment[];
  documentAttachments: DocumentAttachment[];
  isAttachingDocuments: boolean;
  toggleRecording: () => void;
  toggleRealtimeVoice: () => void;
  onAttachImages: (files: FileList | null) => void;
  onAttachDocuments: (files: FileList | null) => void;
  onRemoveImageAttachment: (id: string) => void;
  onRemoveDocumentAttachment: (id: string) => void;
  messageQueue: QueuedMessage[];
  onRemoveFromQueue: (id: string) => void;
  skills: Skill[];
}

export function InputArea({
  inputValue,
  setInputValue,
  handleSend,
  onStop,
  isGenerating,
  isRecording,
  isRealtimeVoiceActive,
  isRealtimeListening,
  realtimeTranscript,
  audioLevel,
  speechRecognitionSupported,
  imageAttachments,
  documentAttachments,
  isAttachingDocuments,
  toggleRecording,
  toggleRealtimeVoice,
  onAttachImages,
  onAttachDocuments,
  onRemoveImageAttachment,
  onRemoveDocumentAttachment,
  messageQueue,
  onRemoveFromQueue,
  skills
}: InputAreaProps) {

  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState<number>(-1);
  const [suggestionIndex, setSuggestionIndex] = useState(0);
  const [dismissedQuery, setDismissedQuery] = useState<string | null>(null);

  // Digitar "/" no começo abre a lista de skills; some quando um espaço entra
  // (nome já escolhido, usuário digitando o argumento) ou ao apertar Escape.
  const slashQuery = useMemo(() => {
    if (!inputValue.startsWith('/') || inputValue.includes('\n')) return null;
    const afterSlash = inputValue.slice(1);
    if (afterSlash.includes(' ')) return null;
    return afterSlash.toLowerCase();
  }, [inputValue]);

  const skillSuggestions = useMemo(() => {
    if (slashQuery === null || slashQuery === dismissedQuery) return [];
    return skills.filter(skill => skill.name.toLowerCase().startsWith(slashQuery));
  }, [skills, slashQuery, dismissedQuery]);

  const clampedSuggestionIndex = Math.min(suggestionIndex, Math.max(0, skillSuggestions.length - 1));

  const pickSkill = (skill: Skill) => {
    setInputValue(`/${skill.name} `);
    setSuggestionIndex(0);
  };

  const internalHandleSend = () => {
    if (isAttachingDocuments) return;
    if (inputValue.trim()) {
      setHistory(prev => [...prev, inputValue]);
      setHistoryIndex(-1);
    }
    handleSend();
  };

  return (
    <Container>
      {skillSuggestions.length > 0 && (
        <SkillSuggestions role="listbox" aria-label="Skills disponíveis">
          {skillSuggestions.map((skill, index) => (
            <SkillSuggestionItem
              key={skill.name}
              type="button"
              role="option"
              aria-selected={index === clampedSuggestionIndex}
              $active={index === clampedSuggestionIndex}
              onMouseDown={(e) => {
                e.preventDefault();
                pickSkill(skill);
              }}
            >
              <strong>/{skill.name}</strong>
              <span>{skill.description}</span>
            </SkillSuggestionItem>
          ))}
          <SkillSuggestionsHint>
            ↑↓ navega · Enter/Tab escolhe · Esc fecha — skills também ativam sozinhas quando o pedido bate com o gatilho
          </SkillSuggestionsHint>
        </SkillSuggestions>
      )}
      <Box>
        {(imageAttachments.length > 0 || documentAttachments.length > 0) && (
          <AttachmentTray>
            {imageAttachments.map((attachment) => (
              <AttachmentChip key={attachment.id} title={attachment.name}>
                <img src={attachment.dataUrl} alt="" />
                <span>{attachment.name}</span>
                <RemoveAttachmentButton
                  type="button"
                  onClick={() => onRemoveImageAttachment(attachment.id)}
                  title="Remover imagem"
                  aria-label={`Remover ${attachment.name}`}
                >
                  <X size={13} weight="bold" />
                </RemoveAttachmentButton>
              </AttachmentChip>
            ))}
            {documentAttachments.map((attachment) => (
              <AttachmentChip key={attachment.id} title={attachment.truncated ? `${attachment.name} (contexto resumido)` : attachment.name}>
                <FileText size={22} weight="duotone" />
                <span>{attachment.name}</span>
                <RemoveAttachmentButton
                  type="button"
                  onClick={() => onRemoveDocumentAttachment(attachment.id)}
                  title="Remover documento"
                  aria-label={`Remover ${attachment.name}`}
                >
                  <X size={13} weight="bold" />
                </RemoveAttachmentButton>
              </AttachmentChip>
            ))}
          </AttachmentTray>
        )}
        <TextArea
          placeholder="Envie uma mensagem..."
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={(e) => {
            if (skillSuggestions.length > 0) {
              if (e.key === 'ArrowDown') {
                e.preventDefault();
                setSuggestionIndex((clampedSuggestionIndex + 1) % skillSuggestions.length);
                return;
              }
              if (e.key === 'ArrowUp') {
                e.preventDefault();
                setSuggestionIndex((clampedSuggestionIndex - 1 + skillSuggestions.length) % skillSuggestions.length);
                return;
              }
              if (e.key === 'Enter' || e.key === 'Tab') {
                e.preventDefault();
                pickSkill(skillSuggestions[clampedSuggestionIndex]);
                return;
              }
              if (e.key === 'Escape') {
                e.preventDefault();
                setDismissedQuery(slashQuery);
                return;
              }
            }
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              internalHandleSend();
            } else if (e.key === 'ArrowUp') {
              if (history.length > 0) {
                e.preventDefault();
                const nextIndex = historyIndex + 1;
                if (nextIndex < history.length) {
                  setHistoryIndex(nextIndex);
                  setInputValue(history[history.length - 1 - nextIndex]);
                }
              }
            } else if (e.key === 'ArrowDown') {
              if (historyIndex >= 0) {
                e.preventDefault();
                const nextIndex = historyIndex - 1;
                setHistoryIndex(nextIndex);
                if (nextIndex >= 0) {
                  setInputValue(history[history.length - 1 - nextIndex]);
                } else {
                  setInputValue('');
                }
              }
            }
          }}
        />
        <ActionBar>
          <AttachButton as="label" title="Anexar imagem para o Avento ler">
            <ImageSquare size={22} />
            <HiddenFileInput
              type="file"
              accept="image/png,image/jpeg,image/webp"
              multiple
              onChange={(event) => {
                onAttachImages(event.target.files);
                event.target.value = '';
              }}
            />
          </AttachButton>
          <AttachButton as="label" $disabled={isAttachingDocuments} title="Anexar PDF, documento, planilha, apresentação ou arquivo de texto">
            {isAttachingDocuments ? <SpinnerGap size={22} className="spinning" /> : <Paperclip size={22} />}
            <HiddenFileInput
              type="file"
              accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.epub,.zip,.txt,.md,.csv,.json,.jsonl,.xml,.yaml,.yml,.toml,.ini,.log,.java,.kt,.kts,.js,.jsx,.ts,.tsx,.css,.scss,.html,.sql,.sh,.zsh,.bash,.py,.rb,.go,.rs,.c,.h,.cpp,.hpp,.gradle,.properties"
              multiple
              disabled={isAttachingDocuments}
              onChange={(event) => {
                onAttachDocuments(event.target.files);
                event.target.value = '';
              }}
            />
          </AttachButton>
          <MicButton
            $isRecording={isRecording}
            onClick={toggleRecording}
            title="Gravar áudio e transcrever com Whisper"
          >
            {isRecording ? <StopCircle size={24} weight="fill" /> : <Microphone size={24} />}
          </MicButton>
          <RealtimeVoiceButton
            $isActive={isRealtimeVoiceActive}
            disabled={!speechRecognitionSupported}
            onClick={toggleRealtimeVoice}
            title={speechRecognitionSupported ? 'Conversar em tempo real' : 'Reconhecimento em tempo real indisponível neste navegador'}
          >
            {isRealtimeVoiceActive ? <StopCircle size={24} weight="fill" /> : <ChatCircleDots size={24} weight="fill" />}
          </RealtimeVoiceButton>
          {(isRecording || isRealtimeVoiceActive) && (
            <AudioLevelMeter title="Nível de áudio captado pelo microfone" aria-hidden="true">
              {AUDIO_LEVEL_BAR_MULTIPLIERS.map((multiplier, index) => (
                <AudioLevelBar key={index} $scale={audioLevel * multiplier} />
              ))}
            </AudioLevelMeter>
          )}
          <SendButton
            $isStop={isGenerating}
            onClick={() => (isGenerating ? onStop() : internalHandleSend())}
            disabled={!isGenerating && (isAttachingDocuments || (!inputValue.trim() && imageAttachments.length === 0 && documentAttachments.length === 0 && !isRecording && !isRealtimeVoiceActive))}
            title={isGenerating ? 'Parar geração' : isAttachingDocuments ? 'Aguarde a leitura do documento' : 'Enviar mensagem'}
          >
            {isGenerating ? <StopCircle size={20} weight="fill" /> : <PaperPlaneRight size={20} weight="fill" />}
          </SendButton>
        </ActionBar>
      </Box>
      {(isRealtimeVoiceActive || realtimeTranscript) && (
        <VoiceStatus>
          <strong>{isRealtimeListening ? 'Ouvindo' : 'Reconectando microfone'}</strong>
          <span>{realtimeTranscript || 'Fale naturalmente. O Avento envia sua fala quando detectar o fim da frase.'}</span>
        </VoiceStatus>
      )}
      {messageQueue.length > 0 && (
        <QueueBar>
          <QueueLabel>
            Na fila ({messageQueue.length}) — enviadas assim que o Avento terminar a resposta atual
          </QueueLabel>
          {messageQueue.map((item, index) => (
            <QueueChip key={item.id}>
              <span>
                {index + 1}. {item.text.trim()
                  ? item.text.length > QUEUE_PREVIEW_LENGTH
                    ? `${item.text.slice(0, QUEUE_PREVIEW_LENGTH)}…`
                    : item.text
                  : `${item.images.length + item.documents.length} anexo(s)`}
              </span>
              <RemoveAttachmentButton
                type="button"
                onClick={() => onRemoveFromQueue(item.id)}
                title="Remover da fila"
                aria-label={`Remover mensagem ${index + 1} da fila`}
              >
                <X size={13} weight="bold" />
              </RemoveAttachmentButton>
            </QueueChip>
          ))}
        </QueueBar>
      )}
    </Container>
  );
}
