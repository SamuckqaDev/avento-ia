import styled, { keyframes } from 'styled-components';

const slideUp = keyframes`
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
`;

const pulse = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, #f59e0b 38%, transparent); }
  60%       { box-shadow: 0 0 0 8px color-mix(in srgb, #f59e0b 0%, transparent); }
`;

export const Bar = styled.div`
  width: min(980px, 100%);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, #f59e0b 26%, ${({ theme }) => theme.colors.border});
  border-left: 3px solid #f59e0b;
  border-radius: 8px;
  background:
    linear-gradient(135deg, color-mix(in srgb, #f59e0b 8%, transparent), transparent 42%),
    ${({ theme }) => theme.colors.surface};
  animation: ${slideUp} 0.22s ease, ${pulse} 2s ease 0.3s;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.08);
`;

export const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;

  svg {
    color: #f59e0b;
    flex-shrink: 0;
  }
`;

export const Title = styled.span`
  flex: 1;
  font-size: 0.88rem;
  font-weight: 750;
  color: ${({ theme }) => theme.colors.text};
  min-width: 0;
`;

export const ToolBadge = styled.span`
  max-width: 42%;
  background: color-mix(in srgb, #f59e0b 10%, ${({ theme }) => theme.colors.surface});
  border: 1px solid color-mix(in srgb, #f59e0b 36%, ${({ theme }) => theme.colors.border});
  color: #92400e;
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;

  [data-theme='dark'] &,
  .dark & {
    color: #fde68a;
  }
`;

export const ArgumentsBox = styled.div`
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 72%, ${({ theme }) => theme.colors.surface});
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  border-radius: 8px;
  padding: 9px 10px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.78rem;
`;

export const ArgumentRow = styled.div`
  display: grid;
  grid-template-columns: minmax(60px, auto) 1fr;
  gap: 8px;
  align-items: start;
`;

export const ArgKey = styled.span`
  color: ${({ theme }) => theme.colors.textMuted};
  font-weight: 700;
  white-space: nowrap;
  padding-top: 1px;
`;

export const ArgValue = styled.span`
  color: ${({ theme }) => theme.colors.text};
  word-break: break-all;
  line-height: 1.4;
`;

export const VoiceHint = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.78rem;
  line-height: 1.45;
`;

export const Actions = styled.div`
  display: flex;
  gap: 8px;

  @media (max-width: 640px) {
    flex-direction: column;
  }
`;

export const CommentInput = styled.textarea`
  min-height: 56px;
  resize: vertical;
  width: 100%;
  border-radius: 8px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.text};
  padding: 9px 11px;
  font: inherit;
  line-height: 1.35;
  outline: none;

  &:focus {
    border-color: ${({ theme }) => theme.colors.accent};
    box-shadow: 0 0 0 3px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 14%, transparent);
  }

  &:disabled {
    opacity: 0.65;
  }
`;

const BaseButton = styled.button`
  height: 36px;
  border-radius: 8px;
  font-weight: 700;
  font-size: 0.84rem;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 16px;
  transition: background 0.18s ease, border-color 0.18s ease, transform 0.18s ease, opacity 0.18s ease;

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  &:hover:not(:disabled) {
    transform: translateY(-1px);
  }
`;

export const ApproveButton = styled(BaseButton)`
  background: #12803a;
  border: 1px solid #0f6d31;
  color: #fff;
  flex: 1;

  &:hover:not(:disabled) {
    background: #0f6d31;
  }

  svg {
    animation: inherit;
  }
`;

export const RejectButton = styled(BaseButton)`
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.textMuted};

  &:hover:not(:disabled) {
    background: color-mix(in srgb, #ef4444 8%, ${({ theme }) => theme.colors.surface});
    border-color: color-mix(in srgb, #ef4444 40%, ${({ theme }) => theme.colors.border});
    color: #ef4444;
  }
`;
