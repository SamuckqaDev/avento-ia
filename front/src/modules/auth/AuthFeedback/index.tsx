import { useEffect, useState } from 'react';
import { API_AUTH_ERROR_EVENT, type ApiAuthErrorDetail } from '../../../services/apiClient';
import { useAuth } from '../AuthProvider';
import {
  AuthFeedbackActions,
  AuthFeedbackButton,
  AuthFeedbackCloseButton,
  AuthFeedbackDetails,
  AuthFeedbackPanel,
  AuthFeedbackShell,
  AuthFeedbackTitle,
  Snackbar,
  SnackbarCloseButton,
  SnackbarMeta,
  SnackbarTitle,
} from './styles';

interface SnackbarState {
  id: number;
  detail: ApiAuthErrorDetail;
}

function errorLabel(detail: ApiAuthErrorDetail) {
  return `${detail.status} ${detail.error}`;
}

export function AuthFeedback() {
  const { user, revalidateSession, logout } = useAuth();
  const [sessionError, setSessionError] = useState<ApiAuthErrorDetail | null>(null);
  const [snackbar, setSnackbar] = useState<SnackbarState | null>(null);
  const [isRevalidating, setRevalidating] = useState(false);

  useEffect(() => {
    const handleAuthError = (event: Event) => {
      const detail = (event as CustomEvent<ApiAuthErrorDetail>).detail;
      if (!detail || !user) return;

      if (detail.kind === 'session-expired') {
        setSessionError(detail);
        return;
      }

      setSnackbar({ id: Date.now(), detail });
    };

    window.addEventListener(API_AUTH_ERROR_EVENT, handleAuthError);
    return () => window.removeEventListener(API_AUTH_ERROR_EVENT, handleAuthError);
  }, [user]);

  useEffect(() => {
    if (user) return;
    setSessionError(null);
    setSnackbar(null);
    setRevalidating(false);
  }, [user]);

  useEffect(() => {
    if (!snackbar) return;
    const timeout = window.setTimeout(() => setSnackbar(null), 9000);
    return () => window.clearTimeout(timeout);
  }, [snackbar]);

  const revalidate = async () => {
    setRevalidating(true);
    try {
      const ok = await revalidateSession();
      if (ok) {
        setSessionError(null);
      }
    } finally {
      setRevalidating(false);
    }
  };

  return (
    <>
      {sessionError && (
        <AuthFeedbackShell role="alertdialog" aria-live="assertive" aria-label="Sessao vencida">
          <AuthFeedbackPanel>
            <AuthFeedbackCloseButton type="button" onClick={() => setSessionError(null)} aria-label="Fechar aviso">
              x
            </AuthFeedbackCloseButton>
            <AuthFeedbackTitle>Sessao vencida</AuthFeedbackTitle>
            <p>O Avento recebeu um {errorLabel(sessionError)}. Voce pode tentar revalidar a sessao antes de voltar ao login.</p>
            <AuthFeedbackDetails>
              <strong>Servidor:</strong> {sessionError.message}
              <br />
              <strong>Rota:</strong> {sessionError.method} {sessionError.path}
            </AuthFeedbackDetails>
            <AuthFeedbackActions>
              <AuthFeedbackButton type="button" onClick={revalidate} disabled={isRevalidating}>
                {isRevalidating ? 'Revalidando...' : 'Revalidar sessao'}
              </AuthFeedbackButton>
              <AuthFeedbackButton type="button" data-variant="ghost" onClick={logout}>
                Ir para login
              </AuthFeedbackButton>
            </AuthFeedbackActions>
          </AuthFeedbackPanel>
        </AuthFeedbackShell>
      )}

      {snackbar && (
        <Snackbar role="status" aria-live="polite">
          <SnackbarCloseButton type="button" onClick={() => setSnackbar(null)} aria-label="Fechar erro">
            x
          </SnackbarCloseButton>
          <SnackbarTitle>Acesso nao autorizado</SnackbarTitle>
          <p>{snackbar.detail.message}</p>
          <SnackbarMeta>
            {errorLabel(snackbar.detail)} - {snackbar.detail.method} {snackbar.detail.path}
          </SnackbarMeta>
        </Snackbar>
      )}
    </>
  );
}
