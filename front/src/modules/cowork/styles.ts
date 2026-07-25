import styled from 'styled-components';

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
  gap: 8px;
  background: ${({ theme }) => theme.colors.primary};
  color: #ffffff;
  border: none;
  padding: 10px 18px;
  border-radius: 8px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
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
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 15%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.accent};
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 30%, transparent);

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
    background: color-mix(in srgb, #ef4444 12%, ${({ theme }) => theme.colors.surface});
    border: 1px solid color-mix(in srgb, #ef4444 35%, transparent);
    border-radius: 8px;
    padding: 10px;
    font-size: 0.8rem;
    color: #ef4444;
    display: flex;
    flex-direction: column;
    gap: 4px;

    strong {
      display: flex;
      align-items: center;
      gap: 4px;
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
  gap: 8px;
  margin-bottom: 8px;
`;

export const FrequencyOptionButton = styled.button<{ $active?: boolean }>`
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid ${({ $active, theme }) => ($active ? theme.colors.accent : theme.colors.border)};
  background: ${({ $active, theme }) =>
    $active ? `color-mix(in srgb, ${theme.colors.accent} 12%, ${theme.colors.surface})` : theme.colors.bg};
  color: ${({ $active, theme }) => ($active ? theme.colors.accent : theme.colors.text)};
  font-size: 0.82rem;
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
  margin-top: 6px;
  padding: 14px 16px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 70%, ${({ theme }) => theme.colors.surface});
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .input-row {
    display: flex;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;

    .field-box {
      display: flex;
      flex-direction: column;
      gap: 6px;
      flex: 1;
      min-width: 140px;

      label {
        font-size: 0.8rem;
        font-weight: 600;
        color: ${({ theme }) => theme.colors.text};
      }

      input, select {
        width: 100%;
        height: 42px;
        box-sizing: border-box;
      }
    }
  }

  .cron-hint {
    font-size: 0.78rem;
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
`;

export const Modal = styled.div`
  background: ${({ theme }) => theme.colors.surface};
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 16px;
  width: 90%;
  max-width: 540px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  box-shadow: ${({ theme }) => theme.shadows.lg};
  color: ${({ theme }) => theme.colors.text};

  .modal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    h2 {
      font-size: 1.25rem;
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
    gap: 6px;

    label {
      font-size: 0.85rem;
      font-weight: 600;
      color: ${({ theme }) => theme.colors.text};
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
