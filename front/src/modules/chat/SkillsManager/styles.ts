import styled from 'styled-components';

export const Backdrop = styled.div`
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.42);
  backdrop-filter: blur(3px);
`;

export const Modal = styled.div`
  position: relative;
  width: min(680px, 100%);
  max-height: min(82vh, 760px);
  display: flex;
  flex-direction: column;
  padding: 24px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.24);

  h2 {
    margin: 0 32px 4px 0;
    font-size: 1.08rem;
  }

  > p {
    margin: 0 0 14px;
    font-size: 0.82rem;
    color: ${({ theme }) => theme.colors.textMuted};
  }
`;

export const CloseButton = styled.button`
  position: absolute;
  top: 14px;
  right: 14px;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;

  &:hover {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.text} 8%, transparent);
    color: ${({ theme }) => theme.colors.text};
  }
`;

export const ScrollArea = styled.div`
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
`;

export const SkillDirectory = styled.section`
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

export const SkillDirectoryHeader = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 0;
    font-size: 0.88rem;
  }

  span {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.72rem;
    font-variant-numeric: tabular-nums;
  }
`;

export const SkillSearch = styled.div`
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.textMuted};

  &:focus-within {
    border-color: ${({ theme }) => theme.colors.accent};
    box-shadow: 0 0 0 3px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 14%, transparent);
  }

  input {
    min-width: 0;
    flex: 1;
    border: 0;
    outline: 0;
    background: transparent;
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.84rem;

    &::-webkit-search-cancel-button {
      display: none;
    }
  }

  button {
    width: 28px;
    height: 28px;
    flex: 0 0 28px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: ${({ theme }) => theme.colors.textMuted};
    cursor: pointer;

    &:hover {
      background: color-mix(in srgb, ${({ theme }) => theme.colors.text} 8%, transparent);
      color: ${({ theme }) => theme.colors.text};
    }
  }
`;

export const SkillListViewport = styled.div`
  max-height: 210px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;

  @media (max-height: 700px) {
    max-height: 150px;
  }
`;

export const SkillList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

export const SkillRow = styled.div`
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;

  strong {
    font-size: 0.86rem;
    white-space: nowrap;
  }

  p {
    flex: 1;
    margin: 0;
    font-size: 0.8rem;
    color: ${({ theme }) => theme.colors.textMuted};
  }

  @media (max-width: 560px) {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    align-items: center;

    p {
      grid-column: 1 / -1;
      grid-row: 2;
    }
  }
`;

export const SkillBadge = styled.span`
  font-size: 0.66rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 2px 7px;
  border-radius: 999px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  color: ${({ theme }) => theme.colors.textMuted};
  white-space: nowrap;
`;

export const DeleteSkillButton = styled.button`
  display: grid;
  place-items: center;
  align-self: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;

  &:hover {
    background: color-mix(in srgb, #dc2626 12%, transparent);
    color: #dc2626;
  }
`;

export const FormSection = styled.form`
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px solid ${({ theme }) => theme.colors.border};

  h3 {
    margin: 0;
    font-size: 0.92rem;
  }

  label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 0.78rem;
    font-weight: 650;
    color: ${({ theme }) => theme.colors.textMuted};
  }

  input,
  textarea {
    padding: 9px 11px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    font-size: 0.85rem;

    &:focus {
      outline: none;
      border-color: ${({ theme }) => theme.colors.accent};
    }
  }

  textarea {
    min-height: 110px;
    resize: vertical;
  }
`;

export const FormError = styled.p`
  margin: 0;
  font-size: 0.8rem;
  color: #dc2626;
`;

export const FormActions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 8px;
`;

export const SaveButton = styled.button`
  min-height: 38px;
  padding: 8px 16px;
  border: 1px solid ${({ theme }) => theme.colors.accent};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.accent};
  color: #fff;
  font-weight: 700;
  cursor: pointer;

  &:disabled {
    opacity: 0.6;
    cursor: wait;
  }
`;
