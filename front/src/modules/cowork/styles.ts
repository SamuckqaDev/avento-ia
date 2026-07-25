import styled from 'styled-components';

export const Container = styled.div`
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: var(--bg-primary, #0f1412);
  color: var(--text-primary, #f0f4f2);
  padding: 24px 32px;
  overflow-y: auto;
  gap: 24px;
`;

export const Header = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  padding-bottom: 20px;

  .title-group {
    display: flex;
    flex-direction: column;
    gap: 4px;

    h1 {
      font-size: 1.5rem;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
      display: flex;
      align-items: center;
      gap: 10px;
    }

    p {
      font-size: 0.875rem;
      color: var(--text-secondary, #8e9b95);
    }
  }
`;

export const CreateButton = styled.button`
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: #ffffff;
  border: none;
  padding: 10px 18px;
  border-radius: 8px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.25);

  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 6px 16px rgba(16, 185, 129, 0.35);
  }
`;

export const Grid = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
`;

export const Card = styled.div<{ $status?: string }>`
  background: var(--bg-card, rgba(255, 255, 255, 0.03));
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  backdrop-filter: blur(10px);
  transition: all 0.2s ease;

  &:hover {
    border-color: rgba(16, 185, 129, 0.3);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;

    h3 {
      font-size: 1.1rem;
      font-weight: 600;
      color: var(--text-primary, #ffffff);
    }

    .badge {
      font-size: 0.75rem;
      padding: 4px 10px;
      border-radius: 20px;
      font-weight: 600;
      text-transform: uppercase;

      &.active {
        background: rgba(16, 185, 129, 0.15);
        color: #34d399;
        border: 1px solid rgba(52, 211, 153, 0.3);
      }

      &.paused {
        background: rgba(245, 158, 11, 0.15);
        color: #fbbf24;
        border: 1px solid rgba(251, 191, 36, 0.3);
      }
    }
  }

  .card-body {
    display: flex;
    flex-direction: column;
    gap: 8px;
    font-size: 0.85rem;
    color: var(--text-secondary, #9ca3af);

    .cron-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(255, 255, 255, 0.05);
      padding: 4px 8px;
      border-radius: 6px;
      font-family: monospace;
      font-size: 0.8rem;
      width: fit-content;
      color: #a7f3d0;
    }

    .prompt-snippet {
      background: rgba(0, 0, 0, 0.2);
      padding: 10px;
      border-radius: 6px;
      font-size: 0.8rem;
      color: #d1d5db;
      max-height: 80px;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }

  .diagnosis-box {
    background: rgba(239, 68, 68, 0.1);
    border: 1px solid rgba(239, 68, 68, 0.3);
    border-radius: 8px;
    padding: 10px;
    font-size: 0.8rem;
    color: #fca5a5;
    display: flex;
    flex-direction: column;
    gap: 4px;

    strong {
      color: #ef4444;
      display: flex;
      align-items: center;
      gap: 4px;
    }
  }

  .card-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
    padding-top: 12px;
    margin-top: auto;

    .next-run {
      font-size: 0.75rem;
      color: var(--text-secondary, #6b7280);
    }

    .actions {
      display: flex;
      gap: 8px;

      button {
        background: transparent;
        border: 1px solid rgba(255, 255, 255, 0.1);
        color: #d1d5db;
        padding: 6px 12px;
        border-radius: 6px;
        font-size: 0.75rem;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 4px;
        transition: all 0.2s;

        &:hover {
          background: rgba(255, 255, 255, 0.08);
          border-color: rgba(255, 255, 255, 0.2);
        }

        &.run-now {
          background: rgba(59, 130, 246, 0.15);
          color: #60a5fa;
          border-color: rgba(96, 165, 250, 0.3);

          &:hover {
            background: rgba(59, 130, 246, 0.25);
          }
        }

        &.delete {
          color: #f87171;
          &:hover {
            background: rgba(239, 68, 68, 0.15);
          }
        }
      }
    }
  }
`;

export const ModalBackdrop = styled.div`
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
`;

export const Modal = styled.div`
  background: var(--bg-card, #161c19);
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.1));
  border-radius: 16px;
  width: 90%;
  max-width: 540px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5);

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
      color: #9ca3af;
      cursor: pointer;
      &:hover { color: #ffffff; }
    }
  }

  .form-group {
    display: flex;
    flex-direction: column;
    gap: 6px;

    label {
      font-size: 0.85rem;
      font-weight: 600;
      color: #d1d5db;
    }

    input, textarea, select {
      background: rgba(0, 0, 0, 0.3);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 8px;
      padding: 10px 14px;
      color: #ffffff;
      font-size: 0.875rem;

      &:focus {
        outline: none;
        border-color: #10b981;
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
