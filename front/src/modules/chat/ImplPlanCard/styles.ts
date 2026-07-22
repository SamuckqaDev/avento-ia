import styled from 'styled-components';

export const Card = styled.div`
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, ${({ theme }) => theme.colors.border});
  border-radius: 12px;
  background: ${({ theme }) => theme.colors.surface};
  margin: 10px 0;
  overflow: hidden;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &:hover,
  &:focus-within {
    border-color: ${({ theme }) => theme.colors.accent};
    box-shadow: 0 8px 24px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 12%, transparent);
  }
`;

export const PreviewButton = styled.button`
  width: 100%;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;

  &:focus-visible {
    box-shadow: inset 0 0 0 2px ${({ theme }) => theme.colors.accent};
  }
`;

export const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  font-weight: 700;
  color: ${({ theme }) => theme.colors.accent};
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent);
  border-bottom: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 18%, ${({ theme }) => theme.colors.border});

  > span:nth-child(2) {
    flex: 1;
    min-width: 0;
  }
`;

export const Status = styled.span<{ $ready: boolean }>`
  padding: 3px 7px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 24%, ${({ theme }) => theme.colors.border});
  border-radius: 999px;
  color: ${({ theme, $ready }) => ($ready ? theme.colors.accent : theme.colors.textMuted)};
  font-size: 0.68rem;
  font-weight: 650;
  white-space: nowrap;
`;

export const Content = styled.div`
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 0.9rem;
  color: ${({ theme }) => theme.colors.text};

  strong {
    line-height: 1.4;
    overflow-wrap: anywhere;
  }

  > span {
    font-size: 0.78rem;
    color: ${({ theme }) => theme.colors.textMuted};
  }

  .open-hint {
    margin-top: 4px;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    color: ${({ theme }) => theme.colors.accent};
    font-weight: 650;
  }
`;

export const Actions = styled.div`
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid ${({ theme }) => theme.colors.border};

  @media (max-width: 520px) {
    flex-direction: column;

    button {
      justify-content: center;
      width: 100%;
    }
  }
`;

export const ApproveButton = styled.button`
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 700;
  background: ${({ theme }) => theme.colors.accent};
  color: ${({ theme }) => theme.colors.white};
  transition: transform 0.15s ease, opacity 0.15s ease;

  &:hover {
    transform: translateY(-1px);
    opacity: 0.92;
  }

  &:disabled {
    cursor: default;
    opacity: 0.6;
    transform: none;
  }
`;

export const AdjustButton = styled.button`
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  background: transparent;
  color: ${({ theme }) => theme.colors.text};

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  &:disabled {
    cursor: default;
    opacity: 0.55;
  }
`;
