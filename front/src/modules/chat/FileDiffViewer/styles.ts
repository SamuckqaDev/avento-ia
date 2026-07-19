import styled from 'styled-components';

export const Container = styled.div`
  margin: 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  background:
    linear-gradient(180deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 5%, transparent), transparent 34%),
    ${({ theme }) => theme.colors.surface};
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.08);
`;

export const Header = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 58%, ${({ theme }) => theme.colors.surface});
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
`;

export const Title = styled.div`
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  color: ${({ theme }) => theme.colors.text};
  min-width: 0;

  svg {
    color: ${({ theme }) => theme.colors.accent};
    flex-shrink: 0;
  }
`;

export const Actions = styled.div`
  display: flex;
  align-items: center;
  gap: 12px;
`;

export const ApplyButton = styled.button`
  background-color: ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 28%, ${({ theme }) => theme.colors.primary});
  border-radius: 8px;
  padding: 7px 12px;
  font-size: 13px;
  font-weight: 750;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease, opacity 0.2s ease;
  white-space: nowrap;

  &:hover:not(:disabled) {
    background-color: ${({ theme }) => theme.colors.accent};
    transform: translateY(-1px);
  }

  &:disabled {
    background-color: ${({ theme }) => theme.colors.border};
    color: ${({ theme }) => theme.colors.textMuted};
    cursor: not-allowed;
  }
`;

export const DiffContainer = styled.div`
  max-height: 500px;
  overflow-y: auto;

  &::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }
  &::-webkit-scrollbar-track {
    background: ${({ theme }) => theme.colors.bg};
  }
  &::-webkit-scrollbar-thumb {
    background: ${({ theme }) => theme.colors.border};
    border-radius: 4px;
  }
  &::-webkit-scrollbar-thumb:hover {
    background: ${({ theme }) => theme.colors.textMuted};
  }
`;

export const LoadingMessage = styled.div`
  padding: 32px 18px;
  text-align: center;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 14px;
`;

export const ErrorMessage = styled.div`
  padding: 32px 18px;
  text-align: center;
  color: #ef4444;
  font-size: 14px;
`;

export const AppliedStatus = styled.span`
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #0f9f68;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
`;
