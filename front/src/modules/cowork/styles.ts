import styled, { keyframes } from 'styled-components';

const fadeIn = keyframes`
  from { opacity: 0; }
  to { opacity: 1; }
`;

const slideUp = keyframes`
  from { opacity: 0; transform: translateY(14px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
`;

export const Container = styled.div`
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.text};
  padding: 24px 32px;
  overflow-y: auto;
  gap: 24px;
  transition: background 0.2s ease, color 0.2s ease;
`;

export const Header = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};
  padding-bottom: 20px;
  flex-wrap: wrap;
  gap: 16px;

  .title-group {
    display: flex;
    flex-direction: column;
    gap: 4px;

    h1 {
      font-size: 1.5rem;
      font-weight: 700;
      color: ${({ theme }) => theme.colors.text};
      display: flex;
      align-items: center;
      gap: 10px;

      svg {
        color: ${({ theme }) => theme.colors.primary};
      }
    }

    p {
      font-size: 0.875rem;
      color: ${({ theme }) => theme.colors.textMuted};
    }
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }
`;

export const KpiBar = styled.div`
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  width: 100%;
  margin-top: 16px;
  margin-bottom: 8px;

  @media (max-width: 768px) {
    grid-template-columns: repeat(2, 1fr);
  }
`;

export const KpiCard = styled.div<{ $variant?: 'primary' | 'success' | 'warning' | 'accent'; $active?: boolean }>`
  background: ${({ $active, theme }) =>
    $active
      ? `color-mix(in srgb, ${theme.colors.surface} 80%, ${theme.colors.primary})`
      : `color-mix(in srgb, ${theme.colors.surface} 85%, ${theme.colors.bg})`};
  border: 1px solid ${({ $active, theme }) => ($active ? theme.colors.primary : theme.colors.border)};
  box-shadow: ${({ $active, theme }) => ($active ? `0 0 0 2px color-mix(in srgb, ${theme.colors.primary} 35%, transparent)` : 'none')};
  border-radius: 10px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.primary};
    transform: translateY(-1px);
  }

  .kpi-icon {
    width: 36px;
    height: 36px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: ${({ $variant, theme }) => 
      $variant === 'success' ? 'rgba(16, 185, 129, 0.15)' :
      $variant === 'warning' ? 'rgba(245, 158, 11, 0.15)' :
      $variant === 'accent' ? `color-mix(in srgb, ${theme.colors.accent} 15%, transparent)` :
      `color-mix(in srgb, ${theme.colors.primary} 15%, transparent)`};
    color: ${({ $variant, theme }) => 
      $variant === 'success' ? '#10B981' :
      $variant === 'warning' ? theme.colors.warning :
      $variant === 'accent' ? theme.colors.accent :
      theme.colors.primary};

    svg {
      color: currentColor;
    }
  }

  .kpi-info {
    display: flex;
    flex-direction: column;
    gap: 2px;

    span {
      font-size: 0.72rem;
      font-weight: 600;
      color: ${({ theme }) => theme.colors.textMuted};
    }

    strong {
      font-size: 1.1rem;
      font-weight: 700;
      color: ${({ theme }) => theme.colors.text};
    }
  }
`;

export const EmptyStateWrapper = styled.div`
  text-align: center;
  padding: 60px 20px;
  background: ${({ theme }) => theme.colors.surface};
  border-radius: 12px;
  border: 1px dashed ${({ theme }) => theme.colors.border};
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;

  > svg {
    color: ${({ theme }) => theme.colors.primary};
    margin-bottom: 16px;
    opacity: 0.9;
  }

  h3 {
    font-size: 1.1rem;
    margin-bottom: 8px;
    color: ${({ theme }) => theme.colors.text};
  }

  p {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.9rem;
    max-width: 460px;
    margin: 0 auto 20px;
  }
`;

export const TabNavigation = styled.div`
  display: flex;
  gap: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 80%, ${({ theme }) => theme.colors.bg});
  padding: 4px;
  border-radius: 10px;
  border: 1px solid ${({ theme }) => theme.colors.border};

  button {
    background: transparent;
    border: none;
    padding: 8px 16px;
    border-radius: 8px;
    font-size: 0.85rem;
    font-weight: 600;
    color: ${({ theme }) => theme.colors.textMuted};
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    transition: all 0.2s ease;

    &.active {
      background: ${({ theme }) => theme.colors.surface};
      color: ${({ theme }) => theme.colors.accent};
      box-shadow: ${({ theme }) => theme.shadows.sm};
      border: 1px solid ${({ theme }) => theme.colors.border};
    }

    &:hover:not(.active) {
      color: ${({ theme }) => theme.colors.text};
    }
  }
`;

export const CreateButton = styled.button`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: ${({ theme }) => theme.colors.primary};
  color: #ffffff;
  border: none;
  height: 42px;
  padding: 0 18px;
  border-radius: 8px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  box-sizing: border-box;
  transition: all 0.2s ease;
  box-shadow: 0 4px 12px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 30%, transparent);

  &:hover {
    transform: translateY(-1px);
    opacity: 0.95;
  }
`;

// Calendar Components
export const CalendarWrapper = styled.div`
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 12px;
  padding: 20px;
`;

export const CalendarHeader = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;

  h2 {
    font-size: 1.15rem;
    font-weight: 700;
    color: ${({ theme }) => theme.colors.text};
  }

  .nav-controls {
    display: flex;
    align-items: center;
    gap: 10px;

    button {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 80%, ${({ theme }) => theme.colors.surface});
      border: 1px solid ${({ theme }) => theme.colors.border};
      color: ${({ theme }) => theme.colors.text};
      padding: 6px 12px;
      border-radius: 6px;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 0.85rem;

      &:hover {
        border-color: ${({ theme }) => theme.colors.accent};
      }
    }
  }
`;

export const CalendarGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
  margin-top: 10px;

  .weekday {
    text-align: center;
    font-size: 0.75rem;
    font-weight: 700;
    color: ${({ theme }) => theme.colors.textMuted};
    padding-bottom: 8px;
    text-transform: uppercase;
  }
`;

export const DayCell = styled.div<{ $isToday?: boolean; $isCurrentMonth?: boolean }>`
  min-height: 90px;
  background: ${({ $isToday, theme }) => 
    $isToday ? `color-mix(in srgb, ${theme.colors.accent} 10%, ${theme.colors.bg})` : theme.colors.bg};
  border: 1px solid ${({ $isToday, theme }) => 
    $isToday ? theme.colors.accent : theme.colors.border};
  border-radius: 8px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  opacity: ${({ $isCurrentMonth }) => ($isCurrentMonth ? 1 : 0.4)};
  transition: all 0.15s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
  }

  .day-number {
    font-size: 0.8rem;
    font-weight: 700;
    color: ${({ $isToday, theme }) => ($isToday ? theme.colors.accent : theme.colors.text)};
  }

  .events-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    overflow-y: auto;
    max-height: 60px;
  }
`;

export const EventBadge = styled.div<{ $status?: string }>`
  font-size: 0.68rem;
  padding: 3px 6px;
  border-radius: 4px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 15%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.accent};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, transparent);

  svg {
    color: currentColor;
    flex-shrink: 0;
  }

  &.automation {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.primary} 15%, ${({ theme }) => theme.colors.surface});
    color: ${({ theme }) => theme.colors.primary};
    border-color: color-mix(in srgb, ${({ theme }) => theme.colors.primary} 30%, transparent);
  }

  &.warning {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.warning} 15%, ${({ theme }) => theme.colors.surface});
    color: ${({ theme }) => theme.colors.warning};
    border-color: color-mix(in srgb, ${({ theme }) => theme.colors.warning} 30%, transparent);
  }
`;

export const Grid = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
`;

export const Card = styled.div<{ $status?: string }>`
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: ${({ theme }) => theme.shadows.sm};
  transition: all 0.2s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    box-shadow: ${({ theme }) => theme.shadows.md};
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;

    h3 {
      font-size: 1.05rem;
      font-weight: 600;
      color: ${({ theme }) => theme.colors.text};
    }

    .badge {
      font-size: 0.72rem;
      padding: 4px 10px;
      border-radius: 20px;
      font-weight: 600;
      text-transform: uppercase;

      &.active {
        background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 15%, transparent);
        color: ${({ theme }) => theme.colors.accent};
        border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, transparent);
      }

      &.paused {
        background: color-mix(in srgb, ${({ theme }) => theme.colors.warning} 15%, transparent);
        color: ${({ theme }) => theme.colors.warning};
        border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.warning} 30%, transparent);
      }
    }
  }

  .card-body {
    display: flex;
    flex-direction: column;
    gap: 8px;
    font-size: 0.85rem;
    color: ${({ theme }) => theme.colors.textMuted};

    .cron-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 80%, ${({ theme }) => theme.colors.surface});
      border: 1px solid ${({ theme }) => theme.colors.border};
      padding: 4px 8px;
      border-radius: 6px;
      font-family: monospace;
      font-size: 0.8rem;
      width: fit-content;
      color: ${({ theme }) => theme.colors.text};

      svg {
        color: ${({ theme }) => theme.colors.primary};
        flex-shrink: 0;
      }
    }

    .prompt-snippet {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 60%, ${({ theme }) => theme.colors.surface});
      padding: 10px;
      border-radius: 6px;
      font-size: 0.8rem;
      color: ${({ theme }) => theme.colors.text};
      max-height: 80px;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }

  .diagnosis-box {
    border-radius: 8px;
    padding: 10px 12px;
    font-size: 0.8rem;
    display: flex;
    flex-direction: column;
    gap: 4px;

    &.success {
      background: color-mix(in srgb, #10b981 12%, ${({ theme }) => theme.colors.surface});
      border: 1px solid color-mix(in srgb, #10b981 35%, transparent);
      color: #10b981;
    }

    &.warning {
      background: color-mix(in srgb, #f59e0b 12%, ${({ theme }) => theme.colors.surface});
      border: 1px solid color-mix(in srgb, #f59e0b 35%, transparent);
      color: #f59e0b;
    }

    &.failed {
      background: color-mix(in srgb, #ef4444 12%, ${({ theme }) => theme.colors.surface});
      border: 1px solid color-mix(in srgb, #ef4444 35%, transparent);
      color: #ef4444;
    }

    strong {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
    }

    .error-details {
      margin-top: 4px;
      font-size: 0.75rem;
      opacity: 0.9;
    }
  }

  .card-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid ${({ theme }) => theme.colors.border};
    padding-top: 12px;
    margin-top: auto;

    .next-run {
      font-size: 0.75rem;
      color: ${({ theme }) => theme.colors.textMuted};
    }

    .actions {
      display: flex;
      gap: 8px;

      button {
        background: transparent;
        border: 1px solid ${({ theme }) => theme.colors.border};
        color: ${({ theme }) => theme.colors.text};
        padding: 6px 12px;
        border-radius: 6px;
        font-size: 0.75rem;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 4px;
        transition: all 0.2s;

        &:hover {
          background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 80%, ${({ theme }) => theme.colors.surface});
          border-color: ${({ theme }) => theme.colors.accent};
        }

        &.run-now {
          background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 15%, transparent);
          color: ${({ theme }) => theme.colors.accent};
          border-color: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, transparent);

          &:hover {
            background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 25%, transparent);
          }
        }

        &.delete {
          color: #ef4444;
          &:hover {
            background: rgba(239, 68, 68, 0.15);
          }
        }
      }
    }
  }
`;

export const FrequencyGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
  margin-bottom: 2px;
`;

export const FrequencyOptionButton = styled.button<{ $active?: boolean }>`
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid ${({ $active, theme }) => ($active ? theme.colors.accent : theme.colors.border)};
  background: ${({ $active, theme }) =>
    $active ? `color-mix(in srgb, ${theme.colors.accent} 12%, ${theme.colors.surface})` : theme.colors.bg};
  color: ${({ $active, theme }) => ($active ? theme.colors.accent : theme.colors.text)};
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
  }

  svg {
    flex-shrink: 0;
    color: ${({ $active, theme }) => ($active ? theme.colors.accent : theme.colors.textMuted)};
  }
`;

export const SubInputPanel = styled.div`
  margin-top: 2px;
  padding: 8px 10px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 70%, ${({ theme }) => theme.colors.surface});
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;

  .input-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;

    .field-box {
      display: flex;
      flex-direction: column;
      gap: 3px;
      flex: 1;
      min-width: 120px;

      label {
        font-size: 0.75rem;
        font-weight: 600;
        color: ${({ theme }) => theme.colors.text};
      }

      input, select {
        width: 100%;
        height: 36px;
        box-sizing: border-box;
      }
    }
  }

  .cron-hint {
    font-size: 0.72rem;
    color: ${({ theme }) => theme.colors.textMuted};

    code {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 80%, ${({ theme }) => theme.colors.bg});
      padding: 2px 6px;
      border-radius: 4px;
      font-family: monospace;
      color: ${({ theme }) => theme.colors.accent};
    }
  }
`;

export const ModalBackdrop = styled.div`
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
  animation: ${fadeIn} 0.18s ease-out;
`;

export const Modal = styled.div`
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 14px;
  width: 90%;
  max-width: 500px;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: ${({ theme }) => theme.shadows.lg};
  color: ${({ theme }) => theme.colors.text};
  animation: ${slideUp} 0.22s cubic-bezier(0.16, 1, 0.3, 1);

  .modal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    h2 {
      font-size: 1.1rem;
      font-weight: 700;
    }

    button {
      background: transparent;
      border: none;
      color: ${({ theme }) => theme.colors.textMuted};
      cursor: pointer;
      &:hover { color: ${({ theme }) => theme.colors.text}; }
    }
  }

  .form-group {
    display: flex;
    flex-direction: column;
    gap: 4px;

    label {
      font-size: 0.78rem;
      font-weight: 600;
      color: ${({ theme }) => theme.colors.text};
    }
  }

    input, textarea, select {
      background: ${({ theme }) => theme.colors.bg};
      border: 1px solid ${({ theme }) => theme.colors.border};
      border-radius: 8px;
      padding: 10px 14px;
      color: ${({ theme }) => theme.colors.text};
      font-size: 0.875rem;

      &:focus {
        outline: none;
        border-color: ${({ theme }) => theme.colors.accent};
      }
    }
  }

  .modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding-top: 10px;
  }
`;

export const DeleteModalBackdrop = styled.div`
  position: fixed;
  inset: 0;
  z-index: 10000;
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
  border-radius: 12px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.24);

  h2 {
    margin: 0 32px 8px 0;
    font-size: 1.1rem;
    font-weight: 700;
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
    transition: all 0.15s ease;

    &:hover {
      color: ${({ theme }) => theme.colors.text};
      background: color-mix(in srgb, ${({ theme }) => theme.colors.textMuted} 15%, transparent);
    }
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
  padding: 8px 14px;
  border: 1px solid ${({ $danger, theme }) => $danger ? '#dc2626' : theme.colors.border};
  border-radius: 8px;
  background: ${({ $danger, theme }) => $danger ? '#dc2626' : theme.colors.surface};
  color: ${({ $danger, theme }) => $danger ? '#ffffff' : theme.colors.text};
  font-weight: 700;
  font-size: 0.85rem;
  cursor: pointer;
  transition: opacity 0.15s ease;

  &:hover:not(:disabled) {
    opacity: 0.9;
  }

  &:disabled {
    opacity: 0.6;
    cursor: wait;
  }
`;

export const ToolbarWrapper = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
`;

export const FilterChipGroup = styled.div`
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
`;

export const FilterChipButton = styled.button<{ $active?: boolean }>`
  background: ${({ $active, theme }) =>
    $active ? theme.colors.primary : theme.colors.surface};
  color: ${({ $active, theme }) => ($active ? '#ffffff' : theme.colors.text)};
  border: 1px solid ${({ $active, theme }) => ($active ? theme.colors.primary : theme.colors.border)};
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.15s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.primary};
  }
`;

export const ActionButtonGroup = styled.div`
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
`;

export const SecondaryActionButton = styled.button`
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  height: 42px;
  padding: 0 16px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
  transition: all 0.15s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.primary};
    color: ${({ theme }) => theme.colors.primary};
    background: color-mix(in srgb, ${({ theme }) => theme.colors.primary} 8%, ${({ theme }) => theme.colors.surface});
  }
`;

export const SecondaryBadgeButton = styled.button`
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.text};
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 0.75rem;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  width: fit-content;
  transition: all 0.15s ease;

  &:hover {
    border-color: ${({ theme }) => theme.colors.primary};
    color: ${({ theme }) => theme.colors.primary};
  }
`;

export const PrimaryBadgeButton = styled.button`
  background: ${({ theme }) => theme.colors.primary};
  border: none;
  color: #ffffff;
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 0.75rem;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  width: fit-content;
  transition: opacity 0.15s ease;

  &:hover {
    opacity: 0.9;
  }
`;
