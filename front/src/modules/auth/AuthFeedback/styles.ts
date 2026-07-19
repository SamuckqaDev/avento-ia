import styled from 'styled-components';

export const AuthFeedbackShell = styled.div`
  position: fixed;
  inset: auto 18px 18px auto;
  width: min(420px, calc(100vw - 36px));
  z-index: 1200;

  @media (max-width: 640px) {
    inset: auto 12px 12px 12px;
    width: auto;
  }
`;

export const AuthFeedbackPanel = styled.section`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.warning} 48%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: ${({ theme }) => theme.shadows.lg};

  p {
    margin: 0;
    padding-right: 22px;
    color: ${({ theme }) => theme.colors.textMuted};
    line-height: 1.45;
  }
`;

export const AuthFeedbackTitle = styled.h2`
  margin: 0;
  color: ${({ theme }) => theme.colors.text};
  font-size: 1rem;
  line-height: 1.2;
`;

export const AuthFeedbackDetails = styled.div`
  overflow-wrap: anywhere;
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.warning} 8%, ${({ theme }) => theme.colors.bg});
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.warning} 24%, ${({ theme }) => theme.colors.border});
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.84rem;
  line-height: 1.5;
  padding: 10px 12px;

  strong {
    color: ${({ theme }) => theme.colors.text};
  }
`;

export const AuthFeedbackActions = styled.div`
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
`;

export const AuthFeedbackButton = styled.button`
  min-height: 38px;
  border: 1px solid ${({ theme }) => theme.colors.primary};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};
  cursor: pointer;
  font: inherit;
  font-size: 0.88rem;
  font-weight: 800;
  padding: 0 14px;

  &[data-variant='ghost'] {
    background: ${({ theme }) => theme.colors.surface};
    border-color: ${({ theme }) => theme.colors.border};
    color: ${({ theme }) => theme.colors.text};
  }

  &:disabled {
    cursor: wait;
    opacity: 0.62;
  }
`;

export const AuthFeedbackCloseButton = styled.button`
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  font: inherit;
  font-weight: 800;
  line-height: 1;
`;

export const Snackbar = styled.div`
  position: fixed;
  right: 18px;
  top: 18px;
  z-index: 1201;
  width: min(420px, calc(100vw - 36px));
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 14px 42px 14px 14px;
  border: 1px solid color-mix(in srgb, #ef4444 44%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  box-shadow: ${({ theme }) => theme.shadows.lg};

  p {
    margin: 0;
    color: ${({ theme }) => theme.colors.text};
    line-height: 1.42;
  }

  @media (max-width: 640px) {
    inset: 12px 12px auto 12px;
    width: auto;
  }
`;

export const SnackbarTitle = styled.strong`
  color: ${({ theme }) => theme.colors.text};
  font-size: 0.94rem;
`;

export const SnackbarMeta = styled.span`
  overflow-wrap: anywhere;
  color: ${({ theme }) => theme.colors.textMuted};
  font-size: 0.78rem;
  line-height: 1.35;
`;

export const SnackbarCloseButton = styled.button`
  position: absolute;
  top: 9px;
  right: 9px;
  width: 28px;
  height: 28px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  font: inherit;
  font-weight: 800;
  line-height: 1;
`;
