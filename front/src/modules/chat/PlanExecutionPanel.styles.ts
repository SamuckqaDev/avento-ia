import styled from 'styled-components';

export const Panel = styled.section`
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  color: ${({ theme }) => theme.colors.text};
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 94%, ${({ theme }) => theme.colors.bg});
`;

export const Header = styled.header`
  min-height: 60px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  div:first-child {
    min-width: 0;
    flex: 1;
  }

  h2 {
    margin: 0;
    font-size: 0.95rem;
    letter-spacing: 0;
  }

  p {
    margin: 3px 0 0;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.78rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const IconButton = styled.button<{ $danger?: boolean }>`
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme, $danger }) => ($danger ? '#dc2626' : theme.colors.text)};
  cursor: pointer;

  &:hover:not(:disabled) {
    border-color: ${({ theme, $danger }) => ($danger ? '#dc2626' : theme.colors.accent)};
    color: ${({ theme, $danger }) => ($danger ? '#dc2626' : theme.colors.accent)};
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;

export const Toolbar = styled.div`
  padding: 10px 14px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  select {
    min-width: 0;
    flex: 1 1 100%;
    height: 36px;
    padding: 0 10px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    outline: none;
    background: ${({ theme }) => theme.colors.surface};
    color: ${({ theme }) => theme.colors.text};

    &:focus {
      border-color: ${({ theme }) => theme.colors.accent};
    }
  }
`;

export const CreateForm = styled.form`
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  textarea {
    width: 100%;
    min-height: 76px;
    resize: vertical;
    padding: 10px 11px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    outline: none;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;
    line-height: 1.45;

    &:focus {
      border-color: ${({ theme }) => theme.colors.accent};
      box-shadow: 0 0 0 3px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 16%, transparent);
    }
  }
`;

export const PrimaryButton = styled.button`
  min-height: 36px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid ${({ theme }) => theme.colors.accent};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.accent};
  color: white;
  font-weight: 700;
  cursor: pointer;

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
`;

export const ErrorBanner = styled.div`
  margin: 10px 14px 0;
  padding: 9px 10px;
  border: 1px solid color-mix(in srgb, #dc2626 45%, transparent);
  border-radius: 8px;
  color: #dc2626;
  background: color-mix(in srgb, #dc2626 8%, transparent);
  font-size: 0.8rem;
`;

export const ProposalNotice = styled.div`
  padding: 9px 10px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 28%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.76rem;
  line-height: 1.45;
`;

export const TaskList = styled.ol`
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  margin: 0;
  padding: 12px 14px 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  list-style: none;
`;

export const EmptyState = styled.div`
  min-height: 150px;
  display: grid;
  place-items: center;
  padding: 20px;
  color: ${({ theme }) => theme.colors.textMuted};
  text-align: center;
  font-size: 0.86rem;
`;

export const TaskCard = styled.li<{ $active: boolean; $failed: boolean }>`
  padding: 11px;
  border: 1px solid ${({ theme, $active, $failed }) =>
    $failed ? '#dc2626' : $active ? theme.colors.accent : theme.colors.border};
  border-radius: 8px;
  background: ${({ theme, $active }) =>
    $active
      ? `color-mix(in srgb, ${theme.colors.accent} 7%, ${theme.colors.surface})`
      : theme.colors.surface};
`;

export const TaskHeader = styled.div`
  display: flex;
  align-items: flex-start;
  gap: 9px;

  > svg {
    flex: 0 0 auto;
    margin-top: 2px;
  }

  > div {
    min-width: 0;
    flex: 1;
  }

  strong {
    display: block;
    font-size: 0.84rem;
    line-height: 1.35;
  }

  p {
    margin: 4px 0 0;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.77rem;
    line-height: 1.45;
    overflow-wrap: anywhere;
  }
`;

export const TaskActions = styled.div`
  margin-top: 9px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  button {
    min-height: 30px;
    padding: 0 9px;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.74rem;
    font-weight: 650;
    cursor: pointer;

    &:hover {
      border-color: ${({ theme }) => theme.colors.accent};
      color: ${({ theme }) => theme.colors.accent};
    }
  }
`;

export const TaskResult = styled.div`
  margin-top: 8px;
  padding: 8px;
  border-radius: 7px;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.74rem;
  line-height: 1.4;
`;

export const TaskAgent = styled.div`
  margin-top: 8px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.72rem;

  svg {
    color: ${({ theme }) => theme.colors.accent};
  }

  span {
    width: 100%;
    padding-left: 19px;
    font-size: 0.68rem;
  }
`;

export const FileList = styled.div`
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 5px;

  code {
    max-width: 100%;
    padding: 3px 6px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 6px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.68rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;

export const EditForm = styled.div`
  margin-top: 9px;
  display: flex;
  flex-direction: column;
  gap: 7px;

  label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.74rem;
    font-weight: 650;
  }

  label input {
    width: 78px;
  }

  input,
  textarea {
    width: 100%;
    padding: 8px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 7px;
    outline: none;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    font: inherit;

    &:focus {
      border-color: ${({ theme }) => theme.colors.accent};
    }
  }

  textarea {
    min-height: 76px;
    resize: vertical;
  }
`;
