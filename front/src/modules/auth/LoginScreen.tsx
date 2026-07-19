import { useState, type FormEvent } from 'react';
import { ArrowRight, Cookie, Cpu, Eye, EyeSlash, LockKey, Sparkle, TerminalWindow } from '@phosphor-icons/react';
import { useAuth } from './AuthProvider';
import logoUrl from '../../assets/avento-logo.svg';
import {
  AuthActions,
  AuthAside,
  AuthAsideCard,
  AuthBrand,
  AuthError,
  AuthFeatureGrid,
  AuthFeatureItem,
  AuthForm,
  AuthHeader,
  AuthLayout,
  AuthPanel,
  AuthSecondaryButton,
  AuthShell,
  AuthSubmitButton,
  AuthTextButton,
  AuthTrustBar,
  BrandMark,
  FieldGroup,
  LoadingState,
  PasswordInputWrap,
  PasswordToggleButton,
} from './styles';

export function LoginScreen() {
  const { login, bootstrap, isLoading } = useAuth();
  const [isBootstrapMode, setBootstrapMode] = useState(false);
  const [displayName, setDisplayName] = useState('Avento Root');
  const [email, setEmail] = useState('admin@avento.local');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setSubmitting] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (isBootstrapMode && password !== confirmPassword) {
      setError('As senhas nao conferem.');
      return;
    }
    setSubmitting(true);
    try {
      if (isBootstrapMode) {
        await bootstrap(email, password, displayName);
      } else {
        await login(email, password);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Nao foi possivel autenticar.');
    } finally {
      setSubmitting(false);
    }
  };

  if (isLoading) {
    return (
      <AuthLayout>
        <LoadingState>Validando sessão local...</LoadingState>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout>
      <AuthShell>
        <AuthAside aria-label="Resumo do Avento">
          <AuthBrand>
            <BrandMark src={logoUrl} alt="" />
            <div>
              <strong>Avento</strong>
              <span>Local AI Workbench</span>
            </div>
          </AuthBrand>

          <AuthHeader>
            <span><Sparkle size={16} weight="fill" /> Ambiente local pronto</span>
            <h1>Avento no seu Mac.</h1>
            <p>
              Entre para continuar conversas, projetos e ações locais com segurança.
            </p>
          </AuthHeader>

          <AuthFeatureGrid>
            <AuthFeatureItem>
              <TerminalWindow size={22} weight="duotone" />
              <strong>Projetos conectados</strong>
              <span>Contexto, RAG, validações e diffs no mesmo fluxo.</span>
            </AuthFeatureItem>
            <AuthFeatureItem>
              <LockKey size={22} weight="duotone" />
              <strong>Sessão protegida</strong>
              <span>JWT em cookie HttpOnly e refresh só no backend.</span>
            </AuthFeatureItem>
            <AuthFeatureItem>
              <Cpu size={22} weight="duotone" />
              <strong>IA local</strong>
              <span>Ollama, Whisper e Piper trabalhando no seu Mac.</span>
            </AuthFeatureItem>
          </AuthFeatureGrid>

          <AuthAsideCard>
            <Cookie size={20} weight="duotone" />
            <div>
              <strong>Sem token no localStorage</strong>
              <span>A validação acontece pelo cookie emitido pelo backend do Avento.</span>
            </div>
          </AuthAsideCard>
        </AuthAside>

        <AuthPanel>
          <span>{isBootstrapMode ? 'Primeiro acesso' : 'Acesso local'}</span>
          <h2>{isBootstrapMode ? 'Criar primeiro acesso' : 'Entrar no Avento'}</h2>
          <p>
            Acesse sua conta local para continuar de onde parou.
          </p>

          <AuthForm onSubmit={submit}>
            {isBootstrapMode && (
              <FieldGroup>
                <label htmlFor="displayName">Nome de usuario</label>
                <input
                  id="displayName"
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  autoComplete="name"
                />
              </FieldGroup>
            )}

            <FieldGroup>
              <label htmlFor="email">Email</label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                required
              />
            </FieldGroup>

            <FieldGroup>
              <label htmlFor="password">Senha</label>
              <PasswordInputWrap>
                <input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete={isBootstrapMode ? 'new-password' : 'current-password'}
                  minLength={8}
                  required
                />
                <PasswordToggleButton
                  type="button"
                  onClick={() => setShowPassword((current) => !current)}
                  aria-label={showPassword ? 'Ocultar senha' : 'Mostrar senha'}
                  title={showPassword ? 'Ocultar senha' : 'Mostrar senha'}
                >
                  {showPassword ? <EyeSlash size={18} /> : <Eye size={18} />}
                </PasswordToggleButton>
              </PasswordInputWrap>
            </FieldGroup>

            {isBootstrapMode && (
              <FieldGroup>
                <label htmlFor="confirmPassword">Confirmar senha</label>
                <PasswordInputWrap>
                  <input
                    id="confirmPassword"
                    type={showConfirmPassword ? 'text' : 'password'}
                    value={confirmPassword}
                    onChange={(event) => setConfirmPassword(event.target.value)}
                    autoComplete="new-password"
                    minLength={8}
                    required
                  />
                  <PasswordToggleButton
                    type="button"
                    onClick={() => setShowConfirmPassword((current) => !current)}
                    aria-label={showConfirmPassword ? 'Ocultar confirmacao de senha' : 'Mostrar confirmacao de senha'}
                    title={showConfirmPassword ? 'Ocultar senha' : 'Mostrar senha'}
                  >
                    {showConfirmPassword ? <EyeSlash size={18} /> : <Eye size={18} />}
                  </PasswordToggleButton>
                </PasswordInputWrap>
              </FieldGroup>
            )}

            {error && <AuthError>{error}</AuthError>}

            <AuthActions>
              <AuthSubmitButton type="submit" disabled={isSubmitting}>
                <span>{isSubmitting ? 'Validando...' : isBootstrapMode ? 'Criar admin' : 'Entrar'}</span>
                <ArrowRight size={18} weight="bold" />
              </AuthSubmitButton>
              <AuthSecondaryButton
                type="button"
                onClick={() => {
                  setError(null);
                  setBootstrapMode((current) => !current);
                }}
              >
                {isBootstrapMode ? 'Ja tenho acesso' : 'Primeiro acesso'}
              </AuthSecondaryButton>
            </AuthActions>
          </AuthForm>

          <AuthTrustBar>
            <span>Cookie HttpOnly</span>
            <span>Refresh server-side</span>
            <span>Loopback-first</span>
          </AuthTrustBar>

          <AuthTextButton as="a" href="/docs.html" target="_blank" rel="noreferrer">
            Abrir documentação local
          </AuthTextButton>
        </AuthPanel>
      </AuthShell>
    </AuthLayout>
  );
}
