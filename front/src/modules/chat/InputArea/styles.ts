import styled, { keyframes, css } from 'styled-components';

const pulseRecording = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(23, 177, 200, 0.42); }
  70% { box-shadow: 0 0 0 12px rgba(23, 177, 200, 0); }
  100% { box-shadow: 0 0 0 0 rgba(23, 177, 200, 0); }
`;

const pulseStop = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.42); }
  70% { box-shadow: 0 0 0 12px rgba(239, 68, 68, 0); }
  100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0); }
`;

const speakingFloat = keyframes`
  0%, 100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-3px) scale(1.025); }
`;

const speakingGlow = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 rgba(23, 177, 200, 0.10), 0 8px 20px rgba(15, 23, 42, 0.08); }
  50% { box-shadow: 0 0 0 6px rgba(245, 158, 11, 0.10), 0 10px 26px rgba(23, 177, 200, 0.20); }
`;

const speakingWave = keyframes`
  0%, 100% { transform: scaleY(0.42); filter: brightness(0.85); }
  50% { transform: scaleY(1); filter: brightness(1.25); }
`;

const speakingMouth = keyframes`
  0%, 100% { transform: translateX(-50%) scale(0.75, 0.55); }
  38% { transform: translateX(-50%) scale(1.15, 1.4); }
  70% { transform: translateX(-50%) scale(0.82, 0.75); }
`;

export const Container = styled.div`
  position: relative;
  padding: 12px 28px 18px;
  background:
    linear-gradient(180deg, transparent, ${({ theme }) => theme.colors.bg} 42%),
    transparent;

  @media (max-width: 768px) {
    padding: 10px 14px 14px;
  }

  @media (max-width: 520px) {
    padding: 8px 10px 10px;
  }
`;

export const Box = styled.div`
  width: min(980px, 100%);
  margin: 0 auto;
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  padding: 6px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  box-shadow: 0 12px 34px rgba(15, 23, 42, 0.08);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;

  &:focus-within {
    border-color: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 42%, ${({ theme }) => theme.colors.border});
    box-shadow: 0 12px 34px rgba(15, 23, 42, 0.08);
  }

  @media (max-width: 560px) {
    gap: 6px;
  }
`;

export const AttachmentTray = styled.div`
  width: 100%;
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 2px 2px 4px;
`;

export const AttachmentChip = styled.div`
  min-width: 0;
  max-width: 220px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 7px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 68%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.8rem;
  font-weight: 650;

  img {
    width: 32px;
    height: 32px;
    object-fit: cover;
    border-radius: 6px;
    flex: 0 0 auto;
  }

  span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const TextArea = styled.textarea`
  width: 100%;
  min-width: 0;
  border: none;
  resize: none;
  padding: 10px 9px;
  font-family: inherit;
  font-size: 0.95rem;
  line-height: 1.45;
  background: transparent;
  color: ${({ theme }) => theme.colors.text};
  outline: none;
  max-height: 180px;
  min-height: 58px;

  &:focus,
  &:focus-visible {
    outline: none;
    box-shadow: none;
  }

  @media (max-width: 560px) {
    padding: 8px;
    min-height: 52px;
  }

  &::placeholder {
    color: ${({ theme }) => theme.colors.textMuted};
  }
`;

export const ActionBar = styled.div`
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;

  > :last-child {
    margin-left: auto;
  }

  @media (max-width: 560px) {
    gap: 6px;

    > :last-child {
      margin-left: 0;
    }
  }
`;

const IconButton = styled.button`
  background: transparent;
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.text};
  width: 38px;
  height: 38px;
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease, transform 0.2s ease;
  flex: 0 0 auto;

  @media (max-width: 560px) {
    flex: 1 1 0;
    min-width: 0;
  }

  &:hover:not(:disabled) {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, ${({ theme }) => theme.colors.bg});
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }
`;

export const AttachButton = styled(IconButton)<{ $disabled?: boolean }>`
  ${({ $disabled }) => $disabled && `
    opacity: 0.55;
    pointer-events: none;
  `}

  .spinning {
    animation: spin-attachment 0.9s linear infinite;
  }

  @keyframes spin-attachment {
    to { transform: rotate(360deg); }
  }

  input {
    display: none;
  }
`;

export const HiddenFileInput = styled.input`
  display: none;
`;

export const RemoveAttachmentButton = styled.button`
  width: 22px;
  height: 22px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;

  &:hover {
    color: #ef4444;
    background: color-mix(in srgb, #ef4444 8%, transparent);
    border-color: color-mix(in srgb, #ef4444 22%, transparent);
  }
`;

export const MicButton = styled(IconButton)<{ $isRecording: boolean }>`
  ${({ $isRecording, theme }) => $isRecording && css`
    color: ${theme.colors.white};
    background: ${theme.colors.accent};
    border-color: ${theme.colors.accent};
    animation: ${pulseRecording} 1.5s infinite;

    &:hover {
      background: ${theme.colors.accent};
      color: ${theme.colors.white};
    }
  `}
`;

export const RealtimeVoiceButton = styled(IconButton)<{ $isActive: boolean }>`
  ${({ $isActive, theme }) => $isActive && css`
    color: ${theme.colors.white};
    background: ${theme.colors.primary};
    border-color: ${theme.colors.accent};
    animation: ${pulseRecording} 1.5s infinite;

    &:hover {
      background: ${theme.colors.primary};
      color: ${theme.colors.white};
    }
  `}

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
`;

export const SendButton = styled(IconButton)<{ $isStop?: boolean }>`
  background: ${({ theme }) => theme.colors.primary};
  border-color: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 26%, ${({ theme }) => theme.colors.primary});
  color: ${({ theme }) => theme.colors.white};

  &:hover:not(:disabled) {
    background: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.white};
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  ${({ $isStop }) => $isStop && css`
    background: #ef4444;
    border-color: #ef4444;
    animation: ${pulseStop} 1.5s infinite;

    &:hover:not(:disabled) {
      background: #dc2626;
      color: ${({ theme }) => theme.colors.white};
    }
  `}
`;

export const AudioLevelMeter = styled.div`
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 38px;
  padding: 0 4px;
  flex: 0 0 auto;
`;

export const AudioLevelBar = styled.span<{ $scale: number }>`
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: ${({ theme }) => theme.colors.accent};
  transform: scaleY(${({ $scale }) => Math.max(0.18, $scale)});
  transform-origin: center;
  transition: transform 0.08s ease-out;
`;

export const QueueBar = styled.div`
  width: min(980px, 100%);
  margin: 8px auto 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
`;

export const QueueLabel = styled.span`
  font-size: 0.76rem;
  font-weight: 650;
  color: ${({ theme }) => theme.colors.textMuted};
  padding-left: 2px;
`;

export const QueueChip = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border: 1px dashed ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 68%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.82rem;

  span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const VoiceStatus = styled.div`
  width: min(980px, 100%);
  margin: 8px auto 0;
  padding: 7px 9px;
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 8%, ${({ theme }) => theme.colors.surface});
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, ${({ theme }) => theme.colors.border});
  color: ${({ theme }) => theme.colors.text};
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 0.84rem;

  strong {
    color: ${({ theme }) => theme.colors.accent};
    flex-shrink: 0;
  }

  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  @media (max-width: 560px) {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;

    span {
      white-space: normal;
    }
  }
`;

export const SpeakingMascot = styled.div`
  position: absolute;
  z-index: 5;
  right: max(28px, calc((100% - 980px) / 2));
  bottom: calc(100% - 4px);
  width: min(260px, calc(100% - 56px));
  min-height: 82px;
  padding: 7px 12px 7px 7px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 26%, ${({ theme }) => theme.colors.border});
  border-radius: 999px;
  background: linear-gradient(105deg,
    color-mix(in srgb, ${({ theme }) => theme.colors.surface} 86%, #14b8a6),
    color-mix(in srgb, ${({ theme }) => theme.colors.surface} 82%, #f59e0b),
    color-mix(in srgb, ${({ theme }) => theme.colors.surface} 88%, #8b5cf6));
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.78rem;
  font-weight: 650;
  animation: ${speakingGlow} 1.35s ease-in-out infinite;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.16);

  @media (max-width: 768px) {
    right: 14px;
    bottom: calc(100% - 2px);
    width: min(230px, calc(100% - 28px));
  }

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

export const MascotCharacter = styled.div`
  position: relative;
  flex: 0 0 auto;
  width: 76px;
  height: 76px;
  animation: ${speakingFloat} 1.2s ease-in-out infinite;

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

export const MascotMouth = styled.span`
  position: absolute;
  top: 54%;
  left: 50%;
  width: 12px;
  height: 5px;
  border-radius: 45% 45% 55% 55%;
  background: radial-gradient(ellipse at 50% 0%, #f7c878 0%, #d9784b 72%);
  box-shadow: 0 0 5px rgba(247, 200, 120, 0.65);
  animation: ${speakingMouth} 480ms ease-in-out infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

export const MascotSpeech = styled.div`
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;

  strong {
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.82rem;
  }

  span {
    overflow: hidden;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.7rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const MascotSoundWave = styled.div`
  height: 22px;
  display: flex;
  align-items: center;
  gap: 3px;
  padding-left: 2px;
  flex: 0 0 auto;

  i {
    display: block;
    width: 3px;
    height: 100%;
    border-radius: 999px;
    transform-origin: center;
    background: linear-gradient(#14b8a6, #f59e0b, #8b5cf6);
    animation: ${speakingWave} 760ms ease-in-out infinite;
  }

  i:nth-child(2) { animation-delay: 100ms; }
  i:nth-child(3) { animation-delay: 220ms; }
  i:nth-child(4) { animation-delay: 340ms; }
  i:nth-child(5) { animation-delay: 460ms; }

  @media (prefers-reduced-motion: reduce) {
    i { animation: none; transform: scaleY(0.65); }
  }
`;

export const SkillSuggestions = styled.div`
  position: absolute;
  bottom: calc(100% - 4px);
  left: 50%;
  transform: translateX(-50%);
  width: min(980px, calc(100% - 56px));
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  /* Abre para cima: sem teto, a lista de skills estoura pro topo da tela e some.
     Limita a altura e deixa rolar para ver TODAS as skills. */
  max-height: min(60vh, 440px);
  overflow-y: auto;
  box-shadow: 0 -8px 30px rgba(15, 23, 42, 0.14);
  z-index: 40;

  @media (max-width: 768px) {
    width: min(980px, calc(100% - 28px));
  }
`;

export const SkillSuggestionItem = styled.button<{ $active: boolean }>`
  display: flex;
  align-items: baseline;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  text-align: left;
  cursor: pointer;
  background: ${({ theme, $active }) =>
    $active ? `color-mix(in srgb, ${theme.colors.accent} 14%, ${theme.colors.surface})` : 'transparent'};
  color: ${({ theme }) => theme.colors.text};

  strong {
    font-size: 0.86rem;
    white-space: nowrap;
  }

  span {
    font-size: 0.8rem;
    color: ${({ theme }) => theme.colors.textMuted};
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, ${({ theme }) => theme.colors.surface});
  }
`;

export const SkillSuggestionsHint = styled.div`
  padding: 7px 14px;
  font-size: 0.72rem;
  color: ${({ theme }) => theme.colors.textMuted};
  border-top: 1px solid ${({ theme }) => theme.colors.border};
`;
