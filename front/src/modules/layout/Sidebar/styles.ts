import styled from 'styled-components';

export const Container = styled.aside<{ $isOpen: boolean; $isMinimized?: boolean }>`
  --sidebar-expanded-width: 304px;
  --sidebar-rail-width: 68px;
  --sidebar-control-width: 48px;
  --sidebar-control-height: 40px;
  --sidebar-rail-padding: calc((var(--sidebar-rail-width) - var(--sidebar-control-width)) / 2);

  position: relative;
  width: ${({ $isMinimized }) => ($isMinimized ? 'var(--sidebar-rail-width)' : 'var(--sidebar-expanded-width)')};
  background:
    linear-gradient(180deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 5%, transparent), transparent 28%),
    ${({ theme }) => theme.colors.surface};
  border-right: 1px solid ${({ theme }) => theme.colors.border};
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease, transform 0.25s ease;
  box-shadow: 10px 0 34px rgba(15, 23, 42, 0.04);
  overflow: visible;
  z-index: 3;

  @media (max-width: 768px) {
    position: fixed;
    top: 0;
    left: 0;
    height: 100vh;
    z-index: 999;
    transform: ${({ $isOpen }) => ($isOpen ? 'translateX(0)' : 'translateX(-100%)')};
  }

  ${({ $isMinimized }) => $isMinimized && `
    .hide-on-minimized {
      display: none !important;
    }

    button {
      justify-content: center !important;
    }

    li {
      justify-content: center !important;
      span { display: none; }
    }
  `}
`;

export const MinimizeButton = styled.button`
  width: var(--sidebar-control-width);
  height: var(--sidebar-control-height);
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 75%, transparent);
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.textMuted};
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease;

  &:hover {
    background: ${({ theme }) => theme.colors.bg};
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  svg {
    display: block;
    flex-shrink: 0;
  }
`;

export const Brand = styled.div`
  min-height: 72px;
  padding: 14px 12px;
  border-bottom: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 82%, transparent);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  ${Container}[data-minimized='true'] & {
    justify-content: center;
    min-height: 112px;
    padding: 12px var(--sidebar-rail-padding);
  }
`;

export const LogoContainer = styled.div`
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  min-width: 0;

  h1 {
    margin: 0;
    font-size: 0.96rem;
    color: ${({ theme }) => theme.colors.text};
    letter-spacing: 0.03em;
    white-space: nowrap;
    line-height: 1.1;
  }

  span {
    display: block;
    margin-top: 3px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.7rem;
    letter-spacing: 0;
  }
`;

export const HeaderActions = styled.div<{ $isMinimized?: boolean }>`
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;

  ${({ $isMinimized }) => $isMinimized && `
    width: var(--sidebar-control-width);
    margin: 0 auto;
    flex-direction: column;
    align-items: center;
    gap: 10px;

    button {
      width: var(--sidebar-control-width);
      height: var(--sidebar-control-height);
    }
  `}
`;

export const LogoMesh = styled.div`
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  display: grid;
  place-items: center;
  box-shadow: 0 12px 24px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 18%, transparent);

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
`;

export const ScrollArea = styled.div`
  padding: 12px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
  overflow-x: hidden;

  ${Container}[data-minimized='true'] & {
    padding: 12px var(--sidebar-rail-padding);
    align-items: center;
  }
`;

export const Section = styled.section`
  display: flex;
  flex-direction: column;
  gap: 8px;

  ${Container}[data-minimized='true'] & {
    width: var(--sidebar-control-width);
    align-items: center;
  }

  h3 {
    margin: 0;
    padding: 0 6px;
    font-size: 0.68rem;
    text-transform: uppercase;
    color: ${({ theme }) => theme.colors.textMuted};
    letter-spacing: 0.08em;
    font-weight: 800;
    display: flex;
    align-items: center;
    gap: 6px;

    &.clickable {
      cursor: pointer;
      user-select: none;
      min-height: 30px;
      border-radius: 8px;
      transition: background 0.2s ease, color 0.2s ease;

      &:hover {
        background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, transparent);
        color: ${({ theme }) => theme.colors.accent};
      }
    }
  }
`;

export const SectionToggle = styled.button<{ $open: boolean }>`
  width: 100%;
  min-height: 32px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;

  .section-label {
    min-width: 0;
    flex: 1;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.68rem;
    font-weight: 800;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .caret {
    flex: 0 0 auto;
    transition: transform 0.2s ease;
    transform: rotate(${({ $open }) => ($open ? '180deg' : '0deg')});
  }

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, transparent);
    color: ${({ theme }) => theme.colors.accent};
  }
`;

export const SectionCount = styled.span`
  min-width: 22px;
  height: 20px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 999px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 75%, transparent);
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.68rem;
  font-weight: 750;
`;

export const ActionBtn = styled.button`
  width: 100%;
  min-height: 40px;
  height: var(--sidebar-control-height);
  padding: 9px 11px;
  background: ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 26%, ${({ theme }) => theme.colors.primary});
  border-radius: 8px;
  cursor: pointer;
  font-weight: 750;
  transition: background 0.2s ease, transform 0.2s ease, border-color 0.2s ease;
  display: flex;
  align-items: center;
  gap: 8px;

  &:hover {
    background: ${({ theme }) => theme.colors.accent};
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.white};
    transform: translateY(-1px);
  }

  ${Container}[data-minimized='true'] & {
    width: var(--sidebar-control-width);
    align-self: center;
    padding: 0;
  }
`;

export const ChatList = styled.ul`
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 210px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;

`;

export const ChatRow = styled.li`
    min-height: 34px;
    padding: 8px 10px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 0.84rem;
    color: ${({ theme }) => theme.colors.text};
    transition: background 0.2s ease, color 0.2s ease;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    border: 1px solid transparent;

    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;

    & > span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &:hover,
    &.active {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 9%, ${({ theme }) => theme.colors.surface});
      border-color: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 18%, ${({ theme }) => theme.colors.border});
      color: ${({ theme }) => theme.colors.accent};
    }

    &.active {
      font-weight: 750;
      box-shadow: inset 3px 0 0 ${({ theme }) => theme.colors.accent};
    }
`;

export const ChatDeleteButton = styled.button`
  width: 26px;
  height: 26px;
  padding: 0;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  opacity: 0;

  ${ChatRow}:hover &,
  ${ChatRow}.active &,
  &:focus-visible {
    opacity: 1;
  }

  &:hover {
    background: color-mix(in srgb, #ef4444 12%, transparent);
    color: #ef4444;
  }
`;

export const DeleteModalBackdrop = styled.div`
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.42);
  backdrop-filter: blur(3px);
`;

export const DeleteModal = styled.div`
  position: relative;
  width: min(420px, 100%);
  padding: 24px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.24);

  h2 {
    margin: 0 32px 8px 0;
    font-size: 1.08rem;
  }

  p {
    margin: 0;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.88rem;
    line-height: 1.5;
  }

  .modal-close {
    position: absolute;
    top: 14px;
    right: 14px;
    width: 32px;
    height: 32px;
    display: grid;
    place-items: center;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: transparent;
    color: ${({ theme }) => theme.colors.textMuted};
    cursor: pointer;
  }
`;

export const DeleteModalActions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 22px;

  @media (max-width: 460px) {
    flex-direction: column-reverse;
  }
`;

export const DeleteModalError = styled.p`
  margin-top: 14px !important;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, #dc2626 42%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, #dc2626 9%, ${({ theme }) => theme.colors.surface});
  color: #dc2626 !important;
`;

export const DeleteModalButton = styled.button<{ $danger?: boolean }>`
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid ${({ $danger, theme }) => $danger ? '#dc2626' : theme.colors.border};
  border-radius: 8px;
  background: ${({ $danger, theme }) => $danger ? '#dc2626' : theme.colors.surface};
  color: ${({ $danger, theme }) => $danger ? theme.colors.white : theme.colors.text};
  font-weight: 700;
  cursor: pointer;

  &:disabled {
    opacity: 0.6;
    cursor: wait;
  }
`;

export const MediaList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
`;

export const MediaItemButton = styled.button`
  min-width: 0;
  min-height: 32px;
  padding: 6px 9px;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 70%, ${({ theme }) => theme.colors.surface});
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  text-align: left;

  & > svg {
    flex: 0 0 auto;
  }

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  span {
    min-width: 0;
    padding: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.68rem;
  }
`;

export const ProjectSelectorWrapper = styled.div`
  display: flex;
  flex-direction: column;
  gap: 8px;

  button {
    width: 100%;
    min-height: 38px;
    padding: 9px 10px;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 72%, ${({ theme }) => theme.colors.surface});
    color: ${({ theme }) => theme.colors.text};
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    cursor: pointer;
    font-size: 0.84rem;
    font-weight: 650;
    transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;

    &:hover {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, ${({ theme }) => theme.colors.surface});
      border-color: ${({ theme }) => theme.colors.accent};
      color: ${({ theme }) => theme.colors.accent};
    }
  }
`;

export const ProjectPathList = styled.ul`
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
`;

export const ProjectPathItem = styled.li`
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 68%, ${({ theme }) => theme.colors.surface});
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 86%, transparent);
  padding: 7px 8px;
  border-radius: 8px;
  font-size: 0.78rem;

  .path-display {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: ${({ theme }) => theme.colors.textMuted};
  }
`;

export const RemovePathButton = styled.button`
  width: 26px !important;
  height: 26px;
  padding: 0 !important;
  background: transparent !important;
  border: 1px solid transparent !important;
  color: #ef4444 !important;
  flex: 0 0 auto;

  &:hover {
    background: color-mix(in srgb, #ef4444 9%, transparent) !important;
    border-color: color-mix(in srgb, #ef4444 28%, transparent) !important;
  }
`;

export const FileTreeWrapper = styled.div`
  max-height: 300px;
  overflow-y: auto;
  font-size: 0.82rem;
  color: ${({ theme }) => theme.colors.text};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  padding: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 66%, ${({ theme }) => theme.colors.surface});

  ul {
    list-style: none;
    padding-left: 14px;
    margin: 4px 0;
  }

  & > ul {
    padding-left: 0;
  }

  li {
    margin: 3px 0;
  }

  .tree-folder {
    width: 100%;
    background: transparent;
    color: ${({ theme }) => theme.colors.text};
    border: 1px solid transparent;
    border-radius: 6px;
    cursor: pointer;
    font-size: 0.82rem;
    font-weight: 700;
    padding: 4px 3px;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 6px;
    text-align: left;

    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &:hover {
      color: ${({ theme }) => theme.colors.accent};
      background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, transparent);
    }
  }

  label {
    display: flex;
    align-items: center;
    gap: 7px;
    cursor: pointer;
    padding: 3px;
    min-width: 0;
    border-radius: 6px;

    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &:hover {
      color: ${({ theme }) => theme.colors.accent};
      background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, transparent);
    }

    input[type="checkbox"] {
      accent-color: ${({ theme }) => theme.colors.accent};
    }
  }
`;

export const Footer = styled.div`
  padding: 12px 14px;
  border-top: 1px solid ${({ theme }) => theme.colors.border};
  text-align: center;
  font-size: 0.76rem;
  color: ${({ theme }) => theme.colors.textMuted};

  p {
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;
