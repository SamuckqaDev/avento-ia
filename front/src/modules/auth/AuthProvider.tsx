import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  api,
  apiErrorMessage,
  beginApiLogout,
  markApiSessionAnonymous,
  markApiSessionAuthenticated,
} from '../../services/apiClient';

interface AuthUser {
  id: string;
  email: string;
  displayName: string;
  role: 'ROOT' | 'ADMIN' | 'USER';
}

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  bootstrap: (email: string, password: string, displayName: string) => Promise<void>;
  revalidateSession: () => Promise<boolean>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const loadMe = useCallback(async () => {
    setIsLoading(true);
    try {
      const response = await api.get<AuthUser>('/api/auth/me');
      setUser(response.data);
      markApiSessionAuthenticated();
    } catch {
      setUser(null);
      markApiSessionAnonymous();
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadMe();
  }, [loadMe]);

  const login = useCallback(async (email: string, password: string) => {
    try {
      const response = await api.post<{ user: AuthUser }>('/api/auth/login', { email, password });
      setUser(response.data.user);
      markApiSessionAuthenticated();
    } catch (error) {
      throw new Error(apiErrorMessage(error));
    }
  }, []);

  const bootstrap = useCallback(async (email: string, password: string, displayName: string) => {
    try {
      const response = await api.post<{ user: AuthUser }>('/api/auth/bootstrap', { email, password, displayName });
      setUser(response.data.user);
      markApiSessionAuthenticated();
    } catch (error) {
      throw new Error(apiErrorMessage(error));
    }
  }, []);

  const revalidateSession = useCallback(async () => {
    try {
      await api.post('/api/auth/refresh');
      const meResponse = await api.get<AuthUser>('/api/auth/me');
      setUser(meResponse.data);
      markApiSessionAuthenticated();
      return true;
    } catch {
      setUser(null);
      markApiSessionAnonymous();
      return false;
    }
  }, []);

  const logout = useCallback(async () => {
    setIsLoading(true);
    setUser(null);
    await beginApiLogout();
    try {
      await api.post('/api/auth/logout');
    } catch {
      // A interface deve concluir uma saida intencional mesmo se o backend estiver indisponivel.
    } finally {
      markApiSessionAnonymous();
      setIsLoading(false);
    }
  }, []);

  const value = useMemo(() => ({
    user,
    isLoading,
    login,
    bootstrap,
    revalidateSession,
    logout,
  }), [bootstrap, isLoading, login, logout, revalidateSession, user]);

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
