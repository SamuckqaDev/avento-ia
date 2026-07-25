import styled from 'styled-components';

export const StyledSelect = styled.select`
  width: 100%;
  height: 42px;
  padding: 0 36px 0 14px;
  background-color: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.875rem;
  font-weight: 500;
  outline: none;
  appearance: none;
  cursor: pointer;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='%239DA9A5' viewBox='0 0 256 256'%3E%3Cpath d='M213.66,101.66l-80,80a8,8,0,0,1-11.32,0l-80-80A8,8,0,0,1,53.66,90.34L128,164.69l74.34-74.35a8,8,0,0,1,11.32,11.32Z'%3E%3C/path%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 12px center;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;

  &:focus {
    border-color: ${({ theme }) => theme.colors.accent};
    box-shadow: 0 0 0 2px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, transparent);
  }

  option {
    background-color: ${({ theme }) => theme.colors.surface};
    color: ${({ theme }) => theme.colors.text};
    padding: 8px 12px;
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
`;
