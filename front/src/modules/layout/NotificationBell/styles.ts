import styled from 'styled-components';

export const Wrapper = styled.div<{ $isMinimized?: boolean }>`
  position: relative;
  width: var(--sidebar-control-width, 48px);
  height: var(--sidebar-control-height, 40px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
`;

export const BellButton = styled.button`
  position: relative;
  width: var(--sidebar-control-width, 48px);
  height: var(--sidebar-control-height, 40px);
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 86%, transparent);
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.textMuted};
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease;

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 8%, ${({ theme }) => theme.colors.surface});
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ theme }) => theme.colors.accent};
  }

  svg {
    display: block;
    flex-shrink: 0;
  }
`;

export const Badge = styled.span`
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 3px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 0.62rem;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
`;

export const Dropdown = styled.div`
  position: absolute;
  top: calc(var(--sidebar-control-height, 40px) + 8px);
  right: 0;
  width: min(calc(var(--sidebar-expanded-width, 304px) - 24px), calc(100vw - 28px));
  max-height: 380px;
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(180deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 5%, transparent), transparent 32%),
    ${({ theme }) => theme.colors.surface};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.border} 88%, transparent);
  border-radius: 8px;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.18);
  overflow: hidden;
  z-index: 20;

  ${Wrapper}[data-minimized='true'] & {
    top: 0;
    right: auto;
    left: calc(var(--sidebar-control-width, 48px) + 8px);
    width: min(320px, calc(100vw - var(--sidebar-rail-width, 68px) - 16px));
  }

  @media (max-width: 520px) {
    position: fixed;
    top: 12px;
    right: 12px;
    left: 12px;
    width: auto;
    max-height: min(380px, calc(100dvh - 24px));
  }
`;

export const DropdownHeader = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
  font-size: 0.82rem;
  font-weight: 800;
  color: ${({ theme }) => theme.colors.text};
  min-width: 0;
`;

export const MarkAllButton = styled.button`
  background: transparent;
  border: 1px solid transparent;
  color: ${({ theme }) => theme.colors.accent};
  font-size: 0.72rem;
  font-weight: 700;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  white-space: nowrap;

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 8%, transparent);
  }
`;

export const NotificationList = styled.div`
  overflow-y: auto;
  min-width: 0;
`;

export const NotificationItem = styled.div<{ $read?: boolean }>`
  padding: 11px 12px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
  cursor: ${({ $read }) => ($read ? 'default' : 'pointer')};
  background: ${({ $read, theme }) => ($read ? 'transparent' : `color-mix(in srgb, ${theme.colors.accent} 7%, transparent)`)};
  transition: background 0.16s ease;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: ${({ $read, theme }) => (!$read ? `color-mix(in srgb, ${theme.colors.accent} 10%, transparent)` : 'transparent')};
  }

  .title {
    font-size: 0.82rem;
    font-weight: 750;
    color: ${({ theme }) => theme.colors.text};
    overflow-wrap: anywhere;
  }

  .message {
    margin-top: 2px;
    font-size: 0.78rem;
    color: ${({ theme }) => theme.colors.textMuted};
    line-height: 1.35;
    overflow-wrap: anywhere;
  }

  .time {
    margin-top: 4px;
    font-size: 0.68rem;
    color: ${({ theme }) => theme.colors.textMuted};
    opacity: 0.8;
  }
`;

export const EmptyState = styled.div`
  padding: 24px 12px;
  text-align: center;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.82rem;
`;
