import styled from 'styled-components';

export const Wrapper = styled.div<{ $isUser: boolean }>`
  display: flex;
  width: 100%;
  justify-content: ${({ $isUser }) => ($isUser ? 'flex-end' : 'flex-start')};
  padding: ${({ $isUser }) => ($isUser ? '0' : '4px 10px 4px 0')};
`;

export const BubbleContainer = styled.div<{ $isUser: boolean; $hasWideArtifact?: boolean }>`
  width: ${({ $hasWideArtifact }) => ($hasWideArtifact ? 'min(100%, 1120px)' : 'auto')};
  max-width: ${({ $isUser, $hasWideArtifact }) => (
    $hasWideArtifact ? 'min(100%, 1120px)' : ($isUser ? 'min(72%, 700px)' : 'min(86%, 820px)')
  )};
  padding: ${({ $isUser }) => ($isUser ? '11px 13px' : '12px 18px')};
  border-radius: 8px;
  line-height: 1.58;
  box-shadow: none;
  overflow: hidden;
  
  ${({ $isUser, theme }) => $isUser && `
    background: ${theme.colors.primary};
    color: ${theme.colors.white};
    border: 1px solid color-mix(in srgb, ${theme.colors.primary} 82%, ${theme.colors.accent});
  `}

  ${({ $isUser, theme }) => !$isUser && `
    background: color-mix(in srgb, ${theme.colors.surface} 72%, transparent);
    color: ${theme.colors.text};
    border: 1px solid color-mix(in srgb, ${theme.colors.border} 78%, transparent);
    border-left: 3px solid color-mix(in srgb, ${theme.colors.accent} 45%, ${theme.colors.border});
  `}

  @media (max-width: 768px) {
    max-width: 94%;
    padding: ${({ $isUser }) => ($isUser ? '10px 12px' : '11px 14px')};
  }

  @media (max-width: 520px) {
    max-width: ${({ $isUser }) => ($isUser ? '92%' : '100%')};
  }
`;

export const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 650;
  color: ${({ theme }) => theme.colors.accent};
  font-size: 0.88rem;
`;

export const HeaderActions = styled.div`
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
`;

export const CopyAction = styled.button`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 6px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 82%, transparent);
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  transition: color 0.18s ease, background 0.18s ease, border-color 0.18s ease;

  &:hover {
    color: ${({ theme }) => theme.colors.accent};
    border-color: ${({ theme }) => theme.colors.accent};
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent);
  }
`;

export const CodeBlock = styled.div`
  position: relative;

  & > ${CopyAction} {
    position: absolute;
    top: 8px;
    right: 8px;
    z-index: 1;
    color: ${({ theme }) => theme.colors.white};
    border-color: color-mix(in srgb, ${({ theme }) => theme.colors.white} 25%, transparent);
    background: color-mix(in srgb, #0f172a 86%, transparent);
  }
`;

export const MediaPreview = styled.figure`
  position: relative;
  margin: 0;

  & > ${CopyAction} {
    position: absolute;
    top: 8px;
    right: 8px;
  }
`;

export const MediaDisclosure = styled.figure`
  width: min(100%, 720px);
  margin: 10px 0;
  overflow: hidden;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 88%, ${({ theme }) => theme.colors.bg});
`;

export const MediaDisclosureHeader = styled.div`
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px 5px 10px;

  & > ${CopyAction} {
    flex: 0 0 32px;
    width: 32px;
    height: 32px;
  }
`;

export const MediaDisclosureToggle = styled.button<{ $open: boolean }>`
  min-width: 0;
  min-height: 36px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 6px;
  border: 0;
  background: transparent;
  color: ${({ theme }) => theme.colors.text};
  cursor: pointer;
  text-align: left;

  & > svg:first-child {
    flex: 0 0 auto;
    color: ${({ theme }) => theme.colors.accent};
  }

  & > span {
    min-width: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
  }

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    font-size: 0.84rem;
  }

  small {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.72rem;
  }

  svg.caret {
    flex: 0 0 auto;
    color: ${({ theme }) => theme.colors.textMuted};
    transition: transform 0.2s ease;
    transform: rotate(${({ $open }) => ($open ? '180deg' : '0deg')});
  }

  &:hover {
    color: ${({ theme }) => theme.colors.accent};
  }
`;

export const MediaDisclosureBody = styled.div<{ $open: boolean }>`
  display: ${({ $open }) => ($open ? 'block' : 'none')};
  border-top: 1px solid ${({ theme }) => theme.colors.border};
  background: #0b0f0e;

  img {
    display: block;
    width: 100%;
    height: auto;
    max-height: min(72vh, 720px);
    object-fit: contain;
  }
`;

export const Content = styled.div`
  color: inherit;
  font-size: 0.95rem;

  & p {
    margin-bottom: 0.5rem;
  }

  & p:last-child {
    margin-bottom: 0;
  }

  & ul,
  & ol {
    margin: 0.45rem 0 0.65rem 1.15rem;
    padding-left: 0.45rem;
  }

  & li {
    margin: 0.32rem 0;
    padding-left: 0.1rem;
  }

  & li > p {
    margin-bottom: 0.25rem;
  }

  & pre {
    background: #0f172a;
    color: ${({ theme }) => theme.colors.white};
    padding: 0.9rem;
    border-radius: 8px;
    overflow-x: auto;
    border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 18%, transparent);
    max-width: 100%;
    margin: 0;
  }

  & code {
    font-family: monospace;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.text} 7%, transparent);
    padding: 0.12rem 0.32rem;
    border-radius: 6px;
  }
`;

export const ImageAttachmentGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 8px;
  margin-bottom: 10px;
`;

export const DocumentAttachmentList = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
`;

export const DocumentAttachmentBadge = styled.div`
  min-width: 0;
  max-width: min(100%, 320px);
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 9px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 68%, ${({ theme }) => theme.colors.surface});
  color: inherit;
  font-size: 0.78rem;
  font-weight: 650;

  svg {
    flex: 0 0 auto;
    color: ${({ theme }) => theme.colors.accent};
  }

  span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const ImageAttachmentPreview = styled.figure`
  margin: 0;
  min-width: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.white} 30%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.text} 8%, transparent);

  img {
    width: 100%;
    height: 110px;
    display: block;
    object-fit: cover;
  }

  span {
    display: block;
    padding: 6px 8px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.78rem;
    font-weight: 650;
  }
`;

export const FooterInfo = styled.div`
  margin-top: 8px;
  padding-top: 0;
  border-top: 0;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.72rem;
  display: flex;
  justify-content: flex-end;
`;

export const FileEditCard = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background:
    linear-gradient(135deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent), transparent),
    ${({ theme }) => theme.colors.bg};
  padding: 12px 14px;
  border-radius: 8px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  margin: 12px 0;

  @media (max-width: 560px) {
    align-items: stretch;
    flex-direction: column;
  }
`;

export const FileEditInfo = styled.div`
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.88rem;
  font-weight: 700;

  svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.colors.accent};
  }

  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const DiffButton = styled.button`
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 28%, ${({ theme }) => theme.colors.primary});
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease;

  &:hover {
    background: ${({ theme }) => theme.colors.accent};
    transform: translateY(-1px);
  }

  @media (max-width: 560px) {
    justify-content: center;
  }
`;

export const SkillInvocationBadge = styled.div`
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;

  strong {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 3px 10px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.18);
    border: 1px solid rgba(255, 255, 255, 0.34);
    font-size: 0.82rem;
    letter-spacing: 0.01em;
    white-space: nowrap;

    svg {
      flex-shrink: 0;
    }
  }

  span {
    opacity: 0.94;
  }
`;

export const TableScroll = styled.div`
  overflow-x: auto;
  max-width: 100%;
  margin: 8px 0;
`;

export const StyledTable = styled.table`
  border-collapse: collapse;
  width: 100%;
  font-size: 0.9em;
`;

export const StyledTh = styled.th`
  border: 1px solid ${({ theme }) => theme.colors.border};
  padding: 6px 10px;
  text-align: left;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 88%, ${({ theme }) => theme.colors.bg});
  font-weight: 600;
`;

export const StyledTd = styled.td`
  border: 1px solid ${({ theme }) => theme.colors.border};
  padding: 6px 10px;
`;
