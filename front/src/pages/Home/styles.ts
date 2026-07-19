import styled from 'styled-components';

export const AppLayout = styled.div`
  display: flex;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: ${({ theme }) => theme.colors.bg};
`;

export const HeaderIconButton = styled.button`
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 82%, transparent);
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.text};
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease, transform 0.2s ease;

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.text} 5%, ${({ theme }) => theme.colors.surface});
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }
`;

export const MobileOnlyIconButton = styled(HeaderIconButton)`
  display: none;

  @media (max-width: 768px) {
    display: inline-flex;
  }
`;

export const HeaderLeft = styled.div`
  flex: 0 1 220px;
  min-width: 72px;
  display: flex;
  align-items: center;
  gap: 10px;

  h2 {
    min-width: 0;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    letter-spacing: 0;
    white-space: nowrap;
  }

  @container avento-header (max-width: 1150px) {
    flex: 1 1 auto;
  }
`;

export const MobileOverlay = styled.div`
  display: none;

  @media (max-width: 768px) {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.58);
    backdrop-filter: blur(4px);
    z-index: 998;
  }
`;

export const MainContent = styled.main`
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  background: ${({ theme }) => theme.colors.bg};
`;

export const Topbar = styled.header`
  container-name: avento-header;
  container-type: inline-size;
  height: 60px;
  min-height: 60px;
  padding: 10px 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 88%, transparent);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
  position: relative;
  z-index: 2;

  h2 {
    margin: 0;
    color: ${({ theme }) => theme.colors.text};
    font-size: 1.05rem;
    font-weight: 650;
    letter-spacing: 0;
  }

  @media (max-width: 560px) {
    padding: 10px 8px;
    gap: 8px;
  }
`;

export const VoiceToggleWrapper = styled.div`
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: nowrap;

  label {
    min-height: 36px;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 0.86rem;
    font-weight: 600;
    color: ${({ theme }) => theme.colors.text};
    cursor: pointer;
    border: 1px solid ${({ theme }) => theme.colors.border};
    background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 80%, transparent);
    border-radius: 8px;
    padding: 5px 8px;
    flex: 0 0 auto;
  }

  label.model-control {
    min-width: 130px;
    max-width: 280px;
    padding: 0;
    flex: 1 1 220px;
    border: 0;
    background: transparent;
  }

  label.image-model-control {
    max-width: 230px;
    flex-basis: 190px;
  }

  .model-select {
    width: 100%;
    min-width: 0;
    max-width: 100%;
    height: 36px;
    background: ${({ theme }) => theme.colors.surface};
    color: ${({ theme }) => theme.colors.text};
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    padding: 0 34px 0 12px;
    outline: none;
    cursor: pointer;
    font-weight: 600;

    &:focus {
      border-color: ${({ theme }) => theme.colors.accent};
      box-shadow: 0 0 0 3px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 18%, transparent);
    }
  }

  input[type="checkbox"] {
    appearance: none;
    width: 38px;
    height: 22px;
    background-color: ${({ theme }) => theme.colors.border};
    border-radius: 999px;
    position: relative;
    cursor: pointer;
    outline: none;
    transition: background-color 0.2s ease;
    flex-shrink: 0;

    &:checked {
      background-color: ${({ theme }) => theme.colors.accent};
    }

    &::before {
      content: '';
      position: absolute;
      top: 3px;
      left: 3px;
      width: 16px;
      height: 16px;
      background-color: ${({ theme }) => theme.colors.white};
      border-radius: 50%;
      transition: transform 0.2s ease;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.24);
    }

    &:checked::before {
      transform: translateX(16px);
    }
  }

  @container avento-header (max-width: 1350px) {
    .desktop-header-control span {
      display: none;
    }
  }

  @container avento-header (max-width: 1150px) {
    flex: 0 0 auto;

    .model-control,
    .desktop-header-control,
    .secondary-header-action {
      display: none;
    }
  }

  @media (max-width: 1280px) {
    .model-control,
    .desktop-header-control,
    .secondary-header-action {
      display: none;
    }
  }
`;

export const HeaderCompactMenu = styled.div`
  position: relative;
  display: block;
  flex: 0 0 auto;
`;

export const HeaderMenuPanel = styled.div`
  position: absolute;
  top: 44px;
  right: 0;
  z-index: 30;
  width: min(350px, calc(100vw - 24px));
  max-height: min(70vh, 520px);
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 20px 52px rgba(8, 18, 16, 0.24);

  .menu-heading {
    margin: 0 0 7px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.68rem;
    font-weight: 750;
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }

  .menu-model-control {
    display: block;
    min-width: 0;
    padding: 0;
    border: 0;
    background: transparent;
  }

  .menu-model-control + .menu-model-control {
    margin-top: 7px;
  }

  .model-select {
    width: 100%;
    min-width: 0;
    height: 38px;
    padding: 0 32px 0 10px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    outline: 0;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.8rem;
    font-weight: 650;
  }

  .image-quality-segments {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 4px;
    padding: 3px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: ${({ theme }) => theme.colors.bg};
  }

  .image-quality-segments button {
    min-width: 0;
    height: 32px;
    padding: 0 5px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: ${({ theme }) => theme.colors.textMuted};
    font: inherit;
    font-size: 0.7rem;
    font-weight: 700;
    cursor: pointer;
  }

  .image-quality-segments button.active {
    background: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.white};
  }

  .image-settings-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 7px;
    margin-top: 8px;
  }

  .image-settings-grid label,
  .image-seed-control {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 5px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.68rem;
    font-weight: 700;
  }

  .image-settings-grid select,
  .image-settings-grid input,
  .image-seed-control input {
    width: 100%;
    min-width: 0;
    height: 34px;
    padding: 0 8px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    outline: 0;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.76rem;
  }

  .image-option-toggles {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 7px;
    margin-top: 8px;
  }

  .image-option-toggles label {
    min-width: 0;
    min-height: 34px;
    padding: 5px 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.68rem;
    font-weight: 700;
  }

  .image-option-toggles input {
    width: 30px;
    height: 18px;
  }

  .image-option-toggles input::before {
    top: 3px;
    left: 3px;
    width: 12px;
    height: 12px;
  }

  .image-option-toggles input:checked::before {
    transform: translateX(12px);
  }

  .image-seed-control {
    margin-top: 8px;
  }

  .image-range-control {
    min-width: 0;
    margin-top: 9px;
    padding: 0;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
    border: 0;
    background: transparent;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.68rem;
    font-weight: 700;
  }

  .image-range-control span {
    display: flex;
    justify-content: space-between;
    gap: 8px;
  }

  .image-range-control output {
    color: ${({ theme }) => theme.colors.text};
    font-variant-numeric: tabular-nums;
  }

  .image-range-control input[type='range'] {
    width: 100%;
    height: 20px;
    accent-color: ${({ theme }) => theme.colors.accent};
  }

  .pose-reference-control {
    min-width: 0;
    margin-top: 9px;
    display: flex;
    align-items: stretch;
    gap: 6px;
  }

  .pose-upload-button {
    min-width: 0;
    min-height: 36px;
    padding: 0 9px;
    flex: 1;
    display: flex;
    align-items: center;
    gap: 7px;
    overflow: hidden;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.72rem;
    font-weight: 700;
    cursor: pointer;
  }

  .pose-upload-button span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .pose-upload-button input {
    display: none;
  }

  .pose-remove-button {
    width: 36px;
    min-width: 36px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.warning};
    cursor: pointer;
  }
`;

export const HeaderMenuSection = styled.section`
  min-width: 0;

  & + & {
    padding-top: 11px;
    border-top: 1px solid ${({ theme }) => theme.colors.border};
  }

  .menu-toggle-row {
    display: flex;
    gap: 7px;
  }

  .menu-toggle {
    min-width: 0;
    min-height: 38px;
    padding: 7px 9px;
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 7px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.76rem;
    font-weight: 700;
    cursor: pointer;
  }
`;

export const HeaderMenuActions = styled.div`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;

  a,
  button {
    min-width: 0;
    min-height: 56px;
    padding: 7px 5px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 5px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.7rem;
    font-weight: 700;
    text-decoration: none;
    cursor: pointer;
  }
`;

export const Snackbar = styled.div`
  position: fixed;
  right: 22px;
  top: 78px;
  z-index: 20;
  max-width: min(420px, calc(100vw - 32px));
  padding: 12px 16px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 42%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 14px 30px rgba(15, 23, 42, 0.18);
  font-size: 0.84rem;
  font-weight: 650;
  animation: avento-snackbar-in 0.2s ease-out;

  @keyframes avento-snackbar-in {
    from { opacity: 0; transform: translateY(8px); }
    to { opacity: 1; transform: translateY(0); }
  }

  @media (max-width: 560px) {
    right: 16px;
    top: 72px;
    left: 16px;
    max-width: none;
  }
`;

export const ChatContainer = styled.div`
  flex: 1;
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
  overflow-y: auto;
  padding: 28px 28px 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;

  @media (max-width: 768px) {
    padding: 20px 14px 14px;
  }

  @media (max-width: 520px) {
    padding: 16px 10px 12px;
    gap: 10px;
  }
`;

export const WelcomeState = styled.div`
  width: min(680px, 100%);
  margin: auto;
  text-align: center;
  color: ${({ theme }) => theme.colors.textMuted};
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  padding: 34px 24px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 82%, transparent);
  border-radius: 8px;
  background:
    linear-gradient(135deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, transparent), transparent 45%),
    ${({ theme }) => theme.colors.surface};
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.08);

  h2 {
    margin: 0;
    color: ${({ theme }) => theme.colors.text};
    font-size: clamp(1.5rem, 3vw, 2.25rem);
    letter-spacing: -0.03em;
  }

  .project-select-btn {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    padding: 12px 18px;
    background: ${({ theme }) => theme.colors.primary};
    color: ${({ theme }) => theme.colors.white};
    border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 28%, ${({ theme }) => theme.colors.primary});
    border-radius: 8px;
    cursor: pointer;
    font-weight: 700;
    transition: background 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
    box-shadow: 0 12px 22px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 20%, transparent);

    &:hover {
      background: ${({ theme }) => theme.colors.accent};
      transform: translateY(-1px);
    }
  }

  @media (max-width: 560px) {
    margin: 20px auto auto;
    padding: 24px 18px;

    h2 {
      font-size: 1.45rem;
    }

    .project-select-btn {
      width: 100%;
      justify-content: center;
    }
  }
`;

export const RightPanel = styled.aside`
  width: min(430px, 38vw);
  min-width: 360px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 94%, ${({ theme }) => theme.colors.bg});
  border-left: 1px solid ${({ theme }) => theme.colors.border};
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  box-shadow: -18px 0 44px rgba(15, 23, 42, 0.08);
  overflow: hidden;

  @media (max-width: 1024px) {
    position: fixed;
    right: 0;
    top: 0;
    z-index: 1000;
    width: min(430px, 92vw);
  }

  @media (max-width: 768px) {
    width: 100vw;
    min-width: 0;
  }
`;

export const RightPanelHeader = styled.div`
  min-height: 58px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 16px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 86%, transparent);
  backdrop-filter: blur(12px);

  h3 {
    margin: 0;
    font-size: 0.92rem;
    color: ${({ theme }) => theme.colors.text};
    display: flex;
    align-items: center;
    gap: 8px;
    letter-spacing: -0.01em;
  }
`;

export const CloseButton = styled.button`
  width: 34px;
  height: 34px;
  background: transparent;
  border: 1px solid transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;

  &:hover {
    background-color: ${({ theme }) => theme.colors.bg};
    border-color: ${({ theme }) => theme.colors.border};
    color: ${({ theme }) => theme.colors.text};
  }
`;

export const PanelContent = styled.div`
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;

  @media (max-width: 560px) {
    padding: 10px;
    gap: 10px;
  }
`;

export const AnalysisSection = styled.section`
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 45%, ${({ theme }) => theme.colors.surface});

  h4 {
    margin: 0;
    font-size: 0.74rem;
    color: ${({ theme }) => theme.colors.text};
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }

  p {
    margin: 0;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.82rem;
    line-height: 1.5;
  }

  @media (max-width: 560px) {
    padding: 10px;
  }
`;

export const ChipList = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
`;

export const Chip = styled.span`
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, ${({ theme }) => theme.colors.border});
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 8%, ${({ theme }) => theme.colors.bg});
  color: ${({ theme }) => theme.colors.text};
  border-radius: 999px;
  padding: 5px 9px;
  font-size: 0.76rem;
  font-weight: 600;
`;

export const FindingList = styled.ul`
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

export const FindingItem = styled.li<{ $severity: string }>`
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  border-left: 3px solid ${({ $severity, theme }) => {
    if ($severity === 'high') return '#ef4444';
    if ($severity === 'warning') return theme.colors.warning;
    return theme.colors.accent;
  }};
  border-radius: 8px;
  padding: 10px;
  background: ${({ theme }) => theme.colors.surface};

  strong {
    display: block;
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.82rem;
    margin-bottom: 4px;
  }

  span {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.76rem;
    line-height: 1.45;
  }
`;

export const ScriptList = styled.ul`
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;

  li {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.78rem;
    line-height: 1.45;
    word-break: break-word;
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
  }

  code {
    color: ${({ theme }) => theme.colors.text};
    font-weight: 700;
  }
`;

export const ScriptRow = styled.li`
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;

  @media (max-width: 520px) {
    align-items: stretch;
    flex-direction: column;
  }
`;

export const ProcessList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 10px;
`;

export const ProcessCard = styled.article`
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
`;

export const ProcessHeader = styled.div`
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: start;
  gap: 10px;

  @media (max-width: 520px) {
    grid-template-columns: 1fr;
  }
`;

export const ProcessCommand = styled.div`
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;

  code {
    color: ${({ theme }) => theme.colors.text};
    font-weight: 750;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const ProcessStatus = styled.span<{ $running: boolean }>`
  width: fit-content;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: ${({ $running, theme }) => ($running ? theme.colors.warning : theme.colors.textMuted)};
  font-size: 0.74rem;
  font-weight: 750;

  &::before {
    content: '';
    width: 7px;
    height: 7px;
    border-radius: 999px;
    background: ${({ $running }) => ($running ? '#f59e0b' : '#10b981')};
    box-shadow: 0 0 0 3px color-mix(in srgb, ${({ $running }) => ($running ? '#f59e0b' : '#10b981')} 12%, transparent);
  }
`;

export const ScriptMeta = styled.span`
  display: block;
  margin-top: 2px;
  color: ${({ theme }) => theme.colors.textMuted};
`;

export const AttentionText = styled.span`
  display: block;
  color: #ef4444;
  margin-top: 2px;
  font-weight: 700;
`;

export const EmptyPanelState = styled.p`
  color: ${({ theme }) => theme.colors.textMuted};
  text-align: center;
  margin: 40px 0 0;
  line-height: 1.5;
`;

export const ActivityList = styled.ol`
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
`;

export const ActivityItem = styled.li<{ $type: string }>`
  display: grid;
  grid-template-columns: 10px 1fr;
  gap: 10px;

  &::before {
    content: '';
    width: 10px;
    height: 10px;
    border-radius: 999px;
    margin-top: 4px;
    background: ${({ $type, theme }) => {
      if ($type.includes('failed') || $type.includes('limit')) return '#ef4444';
      if ($type.includes('completed')) return '#10b981';
      if ($type.includes('tool')) return '#f59e0b';
      return theme.colors.accent;
    }};
    box-shadow: 0 0 0 4px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent);
  }

  strong {
    display: block;
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.82rem;
    margin-bottom: 3px;
  }

  span {
    display: block;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.76rem;
    line-height: 1.4;
    word-break: break-word;
    white-space: pre-wrap;
    max-height: 220px;
    overflow: auto;
    font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  }
`;

export const CommandButton = styled.button`
  border: 1px solid ${({ theme }) => theme.colors.border};
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 72%, ${({ theme }) => theme.colors.bg});
  color: ${({ theme }) => theme.colors.text};
  border-radius: 8px;
  padding: 5px 9px;
  cursor: pointer;
  font-size: 0.75rem;
  font-weight: 700;
  flex-shrink: 0;
  min-height: 30px;

  &:hover:not(:disabled) {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
`;

export const CommandOutput = styled.pre`
  margin: 0;
  max-height: 220px;
  overflow: auto;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 88%, ${({ theme }) => theme.colors.surface});
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  color: ${({ theme }) => theme.colors.text};
  padding: 10px;
  font-size: 0.76rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
`;
