import styled from 'styled-components';

export const Card = styled.div`
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, ${({ theme }) => theme.colors.border});
  border-radius: 12px;
  background: ${({ theme }) => theme.colors.surface};
  margin: 10px 0;
  overflow: hidden;
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
`;

export const Content = styled.div`
  padding: 4px 16px;
  font-size: 0.9rem;
  color: ${({ theme }) => theme.colors.text};

  h2 {
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: ${({ theme }) => theme.colors.textMuted};
    margin: 14px 0 6px;
  }

  ul, ol {
    margin: 4px 0 4px 18px;
  }

  code {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 12%, transparent);
    padding: 1px 5px;
    border-radius: 4px;
    font-size: 0.85em;
  }
`;

export const Actions = styled.div`
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid ${({ theme }) => theme.colors.border};
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
