import styled from 'styled-components';

export const AuthLayout = styled.main`
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 32px;
  background:
    linear-gradient(135deg, color-mix(in srgb, ${({ theme }) => theme.colors.primary} 16%, transparent), transparent 34%),
    linear-gradient(315deg, color-mix(in srgb, ${({ theme }) => theme.colors.accent} 14%, transparent), transparent 36%),
    linear-gradient(90deg, color-mix(in srgb, ${({ theme }) => theme.colors.border} 36%, transparent) 1px, transparent 1px),
    linear-gradient(0deg, color-mix(in srgb, ${({ theme }) => theme.colors.border} 28%, transparent) 1px, transparent 1px),
    ${({ theme }) => theme.colors.bg};
  background-size: auto, auto, 52px 52px, 52px 52px, auto;
  overflow: auto;

  @media (max-width: 720px) {
    padding: 14px;
    place-items: stretch;
  }
`;

export const AuthShell = styled.div`
  width: min(100%, 1120px);
  min-height: min(720px, calc(100vh - 64px));
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr);
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 82%, transparent);
  box-shadow: ${({ theme }) => theme.shadows.lg};
  overflow: hidden;

  @media (max-width: 880px) {
    min-height: auto;
    grid-template-columns: 1fr;
  }

  @media (max-width: 720px) {
    width: 100%;
  }
`;

export const AuthAside = styled.section`
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 28px;
  min-height: 620px;
  padding: 38px;
  overflow: hidden;
  background:
    linear-gradient(145deg, color-mix(in srgb, ${({ theme }) => theme.colors.primary} 92%, #000000), color-mix(in srgb, ${({ theme }) => theme.colors.primary} 68%, ${({ theme }) => theme.colors.accent})),
    ${({ theme }) => theme.colors.primary};
  color: ${({ theme }) => theme.colors.white};

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background:
      linear-gradient(120deg, rgba(255, 255, 255, 0.14) 0 1px, transparent 1px 28px),
      linear-gradient(30deg, rgba(255, 255, 255, 0.1) 0 1px, transparent 1px 34px);
    opacity: 0.38;
    pointer-events: none;
  }

  > * {
    position: relative;
    z-index: 1;
  }

  @media (max-width: 880px) {
    min-height: auto;
    padding: 28px;
  }

  @media (max-width: 720px) {
    gap: 20px;
    padding: 22px;
  }
`;

export const AuthBrand = styled.div`
  display: flex;
  align-items: center;
  gap: 14px;

  div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    font-size: 1.15rem;
    line-height: 1;
  }

  span {
    color: rgba(255, 255, 255, 0.72);
    font-size: 0.82rem;
    font-weight: 700;
  }
`;

export const BrandMark = styled.img`
  width: 52px;
  height: 52px;
  border-radius: 8px;
  box-shadow: 0 16px 32px rgb(0 0 0 / 0.24);
`;

export const AuthHeader = styled.header`
  max-width: 620px;
  display: flex;
  flex-direction: column;
  gap: 16px;

  > span {
    width: fit-content;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    border: 1px solid rgba(255, 255, 255, 0.22);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.1);
    color: ${({ theme }) => theme.colors.accentLight};
    font-size: 0.82rem;
    font-weight: 800;
  }

  h1 {
    margin: 0;
    max-width: 10ch;
    font-size: 4.35rem;
    line-height: 0.94;
    font-weight: 900;

    @media (max-width: 880px) {
      font-size: 3rem;
      line-height: 1;
    }

    @media (max-width: 520px) {
      max-width: 12ch;
      font-size: 2.35rem;
    }
  }

  p {
    margin: 0;
    max-width: 58ch;
    color: rgba(255, 255, 255, 0.76);
    font-size: 1.02rem;
    line-height: 1.7;

    @media (max-width: 520px) {
      font-size: 0.94rem;
      line-height: 1.55;
    }
  }
`;

export const AuthFeatureGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;

  @media (max-width: 720px) {
    grid-template-columns: 1fr;
  }

  @media (max-width: 520px) {
    display: none;
  }
`;

export const AuthFeatureItem = styled.div`
  min-height: 138px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(14px);

  svg {
    color: ${({ theme }) => theme.colors.accentLight};
  }

  strong {
    font-size: 0.94rem;
  }

  span {
    color: rgba(255, 255, 255, 0.68);
    font-size: 0.84rem;
    line-height: 1.45;
  }
`;

export const AuthAsideCard = styled.div`
  display: flex;
  align-items: flex-start;
  gap: 12px;
  max-width: 470px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.12);

  svg {
    flex: 0 0 auto;
    color: ${({ theme }) => theme.colors.accentLight};
  }

  div {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  strong {
    font-size: 0.94rem;
  }

  span {
    color: rgba(255, 255, 255, 0.7);
    font-size: 0.86rem;
    line-height: 1.45;
  }
`;

export const AuthPanel = styled.section`
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 20px;
  padding: 46px;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 96%, transparent);

  @media (max-width: 880px) {
    padding: 30px;
  }

  @media (max-width: 720px) {
    padding: 24px 20px;
  }

  > span {
    color: ${({ theme }) => theme.colors.accent};
    font-size: 0.78rem;
    font-weight: 800;
    letter-spacing: 0;
    text-transform: uppercase;
  }

  h2 {
    margin: 0;
    color: ${({ theme }) => theme.colors.text};
    font-size: 2rem;
    line-height: 1.1;

    @media (max-width: 520px) {
      font-size: 1.55rem;
    }
  }

  p {
    margin: 0;
    color: ${({ theme }) => theme.colors.textMuted};
    line-height: 1.55;
  }
`;

export const AuthForm = styled.form`
  display: flex;
  flex-direction: column;
  gap: 15px;
`;

export const FieldGroup = styled.div`
  display: flex;
  flex-direction: column;
  gap: 7px;

  label {
    color: ${({ theme }) => theme.colors.text};
    font-size: 0.88rem;
    font-weight: 700;
  }

  input {
    height: 48px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.bg} 74%, ${({ theme }) => theme.colors.surface});
    color: ${({ theme }) => theme.colors.text};
    padding: 0 14px;
    font: inherit;
    outline: none;
    transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;

    &:focus {
      border-color: ${({ theme }) => theme.colors.accent};
      box-shadow: 0 0 0 3px color-mix(in srgb, ${({ theme }) => theme.colors.accent} 20%, transparent);
    }
  }
`;

export const PasswordInputWrap = styled.div`
  position: relative;
  display: flex;
  align-items: center;

  input {
    width: 100%;
    padding-right: 46px;
  }
`;

export const PasswordToggleButton = styled.button`
  position: absolute;
  right: 8px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: ${({ theme }) => theme.colors.textMuted};
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;

  &:hover,
  &:focus-visible {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 10%, transparent);
    color: ${({ theme }) => theme.colors.text};
    outline: none;
  }
`;

export const AuthActions = styled.div`
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 6px;

  @media (max-width: 520px) {
    flex-direction: column;
  }
`;

export const AuthSubmitButton = styled.button`
  flex: 1;
  min-width: 140px;
  height: 48px;
  border: 0;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: linear-gradient(135deg, ${({ theme }) => theme.colors.primary}, color-mix(in srgb, ${({ theme }) => theme.colors.primary} 72%, ${({ theme }) => theme.colors.accent}));
  color: ${({ theme }) => theme.colors.white};
  cursor: pointer;
  font-weight: 800;
  box-shadow: 0 14px 28px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 24%, transparent);
  transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 18px 36px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 30%, transparent);
  }

  &:disabled {
    opacity: 0.65;
    cursor: wait;
  }
`;

export const AuthSecondaryButton = styled.button`
  height: 48px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.surface};
  color: ${({ theme }) => theme.colors.text};
  cursor: pointer;
  font-weight: 700;
  padding: 0 14px;
  transition: border-color 0.18s ease, background 0.18s ease;

  @media (max-width: 520px) {
    width: 100%;
  }

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 7%, ${({ theme }) => theme.colors.surface});
  }
`;

export const AuthTextButton = styled.button`
  align-self: flex-start;
  border: 0;
  background: transparent;
  color: ${({ theme }) => theme.colors.accent};
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  padding: 0;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
`;

export const AuthTrustBar = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  span {
    padding: 7px 9px;
    border: 1px solid ${({ theme }) => theme.colors.border};
    border-radius: 8px;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accentPale} 70%, transparent);
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.76rem;
    font-weight: 800;
  }
`;

export const AuthError = styled.div`
  border: 1px solid color-mix(in srgb, #ef4444 42%, ${({ theme }) => theme.colors.border});
  border-radius: 8px;
  background: color-mix(in srgb, #ef4444 10%, ${({ theme }) => theme.colors.surface});
  color: ${({ theme }) => theme.colors.text};
  padding: 10px 12px;
  font-size: 0.9rem;
`;

export const LoadingState = styled.div`
  color: ${({ theme }) => theme.colors.text};
  font-weight: 700;
`;
