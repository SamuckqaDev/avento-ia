import styled from 'styled-components';

export const StyledInput = styled.input`
  width: 100%;
  height: 42px;
  padding: 0 14px;
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.875rem;
  font-weight: 500;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;

  &::placeholder {
    color: ${({ theme }) => theme.colors.textMuted};
    opacity: 0.75;
  }

  &:focus {
    border-color: ${({ theme }) => theme.colors.accent};
    background: ${({ theme }) => theme.colors.surface};
    box-shadow: 0 0 0 2px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, transparent);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
`;
