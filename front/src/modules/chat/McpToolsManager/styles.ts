import styled from 'styled-components';

export const Backdrop = styled.div`
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(8, 18, 16, 0.5);
  backdrop-filter: blur(5px);

  @media (max-width: 640px) {
    align-items: stretch;
    padding: 0;
  }
`;

export const Modal = styled.div`
  width: min(860px, 100%);
  max-height: min(86vh, 820px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 28px 80px rgba(8, 18, 16, 0.28);

  @media (max-width: 640px) {
    width: 100%;
    max-height: 100dvh;
    border: 0;
    border-radius: 0;
  }
`;

export const Header = styled.header`
  flex: 0 0 auto;
  min-height: 70px;
  padding: 15px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  > div {
    min-width: 0;
  }

  span {
    display: block;
    margin-bottom: 3px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.68rem;
    font-weight: 750;
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }

  h2 {
    margin: 0;
    font-size: 1.08rem;
    letter-spacing: 0;
  }
`;

export const Controls = styled.div`
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  min-width: 0;
  background: ${({ theme }) => theme.colors.surface};
`;

export const CloseButton = styled.button`
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }
`;

export const Summary = styled.div`
  padding: 10px 18px 0;
  display: flex;
  align-items: center;
  gap: 14px;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.78rem;
  font-weight: 650;

  span {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }

  span:first-child {
    color: ${({ theme }) => theme.colors.primary};
  }
`;

export const Toolbar = styled.div`
  padding: 12px 18px 10px;
  display: flex;
  align-items: center;
  gap: 8px;

  > button {
    width: 38px;
    height: 38px;
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.textMuted};
    cursor: pointer;

    &:hover:not(:disabled) {
      border-color: ${({ theme }) => theme.colors.accent};
      color: ${({ theme }) => theme.colors.accent};
    }

    &:disabled {
      opacity: 0.55;
      cursor: wait;
    }
  }

  .spinning {
    animation: mcp-spin 0.8s linear infinite;
  }

  @keyframes mcp-spin {
    to { transform: rotate(360deg); }
  }
`;

export const SearchField = styled.label`
  min-width: 0;
  height: 38px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.textMuted};

  &:focus-within {
    border-color: ${({ theme }) => theme.colors.accent};
  }

  input {
    width: 100%;
    min-width: 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.84rem;
    letter-spacing: 0;
  }
`;

export const FilterBar = styled.div`
  padding: 0 18px 12px;
  display: flex;
  gap: 6px;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  scrollbar-width: thin;
`;

export const ProfileControl = styled.button<{ $active: boolean }>`
  min-height: 32px;
  padding: 6px 10px;
  flex: 0 0 auto;
  border: 1px solid ${({ $active, theme }) => $active ? theme.colors.primary : theme.colors.border};
  border-radius: 8px;
  background: ${({ $active, theme }) => $active
    ? `color-mix(in srgb, ${theme.colors.primary} 12%, ${theme.colors.surface})`
    : theme.colors.surface};
  color: ${({ $active, theme }) => $active ? theme.colors.primary : theme.colors.textMuted};
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;
`;

export const ErrorNotice = styled.div`
  margin: 0 18px 10px;
  padding: 9px 11px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  border: 1px solid color-mix(in srgb, #dc2626 28%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: color-mix(in srgb, #dc2626 7%, ${({ theme }) => theme.colors.surface});
  color: #dc2626;
  font-size: 0.78rem;
  line-height: 1.4;

  svg {
    flex: 0 0 auto;
    margin-top: 1px;
  }
`;

export const List = styled.div`
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 12px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 1px solid ${({ theme }) => theme.colors.border};

  @media (max-width: 640px) {
    padding: 10px 12px 18px;
  }
`;

export const ServerRow = styled.article<{ $connected: boolean }>`
  min-width: 0;
  padding: 11px 12px;
  display: flex;
  align-items: center;
  gap: 11px;
  border: 1px solid ${({ $connected, theme }) => $connected
    ? `color-mix(in srgb, ${theme.colors.primary} 34%, ${theme.colors.border})`
    : theme.colors.border};
  border-radius: 8px;
  background: ${({ $connected, theme }) => $connected
    ? `color-mix(in srgb, ${theme.colors.primary} 5%, ${theme.colors.surface})`
    : theme.colors.surface};

  @media (max-width: 560px) {
    align-items: flex-start;
    flex-wrap: wrap;
  }
`;

export const ServerState = styled.span<{ $connected: boolean; $available: boolean }>`
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: ${({ $connected, $available, theme }) => $connected
    ? theme.colors.accentLight
    : $available
      ? theme.colors.warning
      : theme.colors.textMuted};
  box-shadow: ${({ $connected, theme }) => $connected
    ? `0 0 0 4px color-mix(in srgb, ${theme.colors.accentLight} 16%, transparent)`
    : 'none'};
`;

export const ServerMeta = styled.div`
  min-width: 0;
  flex: 1;

  .server-title {
    min-width: 0;
    display: flex;
    align-items: baseline;
    gap: 8px;
  }

  strong {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.86rem;
  }

  code {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.66rem;
  }

  p {
    margin: 3px 0 0;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.76rem;
    line-height: 1.4;
  }

  .unavailable {
    display: block;
    margin-top: 4px;
    color: ${({ theme }) => theme.colors.warning};
    font-size: 0.7rem;
  }

  @media (max-width: 560px) {
    flex-basis: calc(100% - 24px);

    .server-title {
      align-items: flex-start;
      flex-direction: column;
      gap: 2px;
    }
  }
`;

export const ActionButton = styled.button<{ $connected: boolean }>`
  min-width: 116px;
  min-height: 36px;
  padding: 7px 10px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid ${({ $connected, theme }) => $connected ? theme.colors.border : theme.colors.primary};
  border-radius: 8px;
  background: ${({ $connected, theme }) => $connected ? 'transparent' : theme.colors.primary};
  color: ${({ $connected, theme }) => $connected ? theme.colors.text : theme.colors.surface};
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;

  &:hover:not(:disabled) {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ $connected, theme }) => $connected ? theme.colors.accent : theme.colors.surface};
    background: ${({ $connected, theme }) => $connected ? 'transparent' : theme.colors.accent};
  }

  &:disabled {
    opacity: 0.46;
    cursor: not-allowed;
  }

  @media (max-width: 560px) {
    width: calc(100% - 20px);
    margin-left: 20px;
  }
`;

export const EmptyState = styled.div`
  min-height: 120px;
  display: grid;
  place-items: center;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.82rem;
`;
