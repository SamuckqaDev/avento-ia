import styled, { css, keyframes } from 'styled-components';

const spin = keyframes`
  to { transform: rotate(360deg); }
`;

export const Card = styled.section`
  width: min(100%, 620px);
  margin: 12px 0 4px;
  padding: 14px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 88%, ${({ theme }) => theme.colors.bg});
`;

export const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 10px;

  & > div:nth-child(2) {
    min-width: 0;
    display: flex;
    flex: 1;
    flex-direction: column;
  }

  strong { font-size: 0.9rem; }
  span { color: ${({ theme }) => theme.colors.textMuted}; font-size: 0.78rem; }
`;

export const StatusIcon = styled.div<{ $status: string }>`
  display: inline-flex;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  color: ${({ theme }) => theme.colors.accent};
  background: color-mix(in srgb, currentColor 10%, transparent);

  ${({ $status }) => !['completed', 'failed', 'cancelled'].includes($status) && css`
    svg { animation: ${spin} 1.1s linear infinite; }
  `}
`;

export const Actions = styled.div`
  display: flex;
  align-items: center;
  gap: 6px;
`;

export const CancelButton = styled.button`
  display: inline-flex;
  width: 34px;
  height: 34px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 7px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;

  &:hover:not(:disabled) {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  &:disabled { opacity: 0.55; cursor: wait; }
`;

export const ProgressBar = styled.div`
  height: 6px;
  margin-top: 14px;
  overflow: hidden;
  border-radius: 3px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.border} 70%, transparent);
`;

export const ProgressFill = styled.div<{ $progress: number }>`
  width: ${({ $progress }) => Math.max(0, Math.min(100, $progress))}%;
  height: 100%;
  border-radius: inherit;
  background: ${({ theme }) => theme.colors.accent};
  transition: width 0.35s ease;
`;

export const TimeGrid = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 11px;
`;

export const Detail = styled.div`
  display: flex;
  min-width: 88px;
  flex-direction: column;

  span { color: ${({ theme }) => theme.colors.textMuted}; font-size: 0.7rem; }
  strong { font-size: 0.79rem; }
`;

export const Media = styled.figure<{ $open: boolean }>`
  display: ${({ $open }) => ($open ? 'block' : 'none')};
  position: relative;
  margin: 12px 0 0;
  overflow: hidden;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;

  img {
    display: block;
    width: 100%;
    height: auto;
    max-height: 520px;
    object-fit: contain;
    background: #0b0f0e;
  }
`;

export const MediaAction = styled(CancelButton)`
  color: ${({ theme }) => theme.colors.textMuted};

  &:hover:not(:disabled) {
    color: ${({ theme }) => theme.colors.accent};
    border-color: ${({ theme }) => theme.colors.accent};
  }
`;

export const MediaToggle = styled(CancelButton)<{ $open: boolean }>`
  svg {
    transition: transform 0.2s ease;
    transform: rotate(${({ $open }) => ($open ? '180deg' : '0deg')});
  }
`;

export const ErrorText = styled.p`
  margin: 10px 0 0;
  color: ${({ theme }) => theme.colors.accent};
  font-size: 0.78rem;
`;
