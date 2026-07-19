import styled, { keyframes } from 'styled-components';

const bounce = keyframes`
  0%, 80%, 100% { 
    transform: translateY(0);
  } 
  40% { 
    transform: translateY(-8px);
  }
`;

export const DotsWrapper = styled.div`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 24px;
  padding: 0 4px;
`;

export const Dot = styled.div<{ $delay: string }>`
  width: 8px;
  height: 8px;
  background-color: ${({ theme }) => theme.colors.accent};
  border-radius: 50%;
  animation: ${bounce} 1.4s infinite ease-in-out both;
  animation-delay: ${({ $delay }) => $delay};
`;
