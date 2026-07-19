import { useState, useEffect } from 'react';
import styled, { keyframes, css } from 'styled-components';
import { Brain, CaretDown } from '@phosphor-icons/react';
import ReactMarkdown from 'react-markdown';

interface ThinkingBlockProps {
  thinking: string;
  isStreaming: boolean;
}

const pulse = keyframes`
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.45; }
`;

const Container = styled.div`
  margin-bottom: 12px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, ${({ theme }) => theme.colors.border});
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 4%, ${({ theme }) => theme.colors.surface});
`;

const Header = styled.button<{ $open: boolean; $streaming: boolean }>`
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 12px;
  background: none;
  border: none;
  cursor: pointer;
  color: ${({ theme }) => theme.colors.accent};
  font-size: 0.82rem;
  font-weight: 700;
  text-align: left;
  user-select: none;
  transition: background 0.15s;

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 8%, transparent);
  }

  svg.brain-icon {
    flex-shrink: 0;
    ${({ $streaming }) =>
      $streaming &&
      css`
        animation: ${pulse} 1.4s ease-in-out infinite;
      `}
  }

  span.label {
    flex: 1;
  }

  svg.caret {
    flex-shrink: 0;
    transition: transform 0.2s ease;
    transform: rotate(${({ $open }) => ($open ? '180deg' : '0deg')});
  }
`;

const Body = styled.div<{ $open: boolean }>`
  display: ${({ $open }) => ($open ? 'block' : 'none')};
  padding: 10px 14px 12px;
  border-top: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 16%, ${({ theme }) => theme.colors.border});
  font-size: 0.83rem;
  line-height: 1.65;
  color: ${({ theme }) => theme.colors.textMuted};
  max-height: 380px;
  overflow-y: auto;

  & p { margin-bottom: 0.4rem; }
  & p:last-child { margin-bottom: 0; }
  & code {
    font-family: monospace;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent);
    padding: 0.15rem 0.35rem;
    border-radius: 4px;
  }
`;

export function ThinkingBlock({ thinking, isStreaming }: ThinkingBlockProps) {
  // Auto-expand while streaming, auto-collapse when done
  const [open, setOpen] = useState(true);

  useEffect(() => {
    if (!isStreaming && thinking) {
      setOpen(false);
    }
  }, [isStreaming, thinking]);

  if (!thinking) return null;

  const wordCount = thinking.split(/\s+/).filter(Boolean).length;
  const label = isStreaming ? 'Pensando...' : `Raciocínio (${wordCount} palavras)`;

  return (
    <Container>
      <Header $open={open} $streaming={isStreaming} onClick={() => setOpen(o => !o)}>
        <Brain size={15} weight="duotone" className="brain-icon" />
        <span className="label">{label}</span>
        <CaretDown size={13} className="caret" />
      </Header>
      <Body $open={open}>
        <ReactMarkdown>{thinking}</ReactMarkdown>
      </Body>
    </Container>
  );
}
