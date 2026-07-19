import { useState, useEffect } from 'react';
import { ThemeProvider } from 'styled-components';
import { lightTheme, darkTheme } from './styles/theme';
import { GlobalStyle } from './styles/global';
import { Home } from './pages/Home';
import { AuthProvider, useAuth } from './modules/auth/AuthProvider';
import { AuthFeedback } from './modules/auth/AuthFeedback';
import { LoginScreen } from './modules/auth/LoginScreen';

function AuthenticatedApp({ isDarkMode, toggleTheme }: { isDarkMode: boolean; toggleTheme: () => void }) {
  const { user, isLoading } = useAuth();

  if (isLoading || !user) {
    return <LoginScreen />;
  }

  return <Home isDarkMode={isDarkMode} toggleTheme={toggleTheme} />;
}

function App() {
  const [isDarkMode, setIsDarkMode] = useState<boolean>(() => {
    const saved = localStorage.getItem('avento-theme');
    return saved === 'dark';
  });

  useEffect(() => {
    localStorage.setItem('avento-theme', isDarkMode ? 'dark' : 'light');
  }, [isDarkMode]);

  const toggleTheme = () => setIsDarkMode(prev => !prev);

  return (
    <ThemeProvider theme={isDarkMode ? darkTheme : lightTheme}>
      <GlobalStyle />
      <AuthProvider>
        <AuthenticatedApp isDarkMode={isDarkMode} toggleTheme={toggleTheme} />
        <AuthFeedback />
      </AuthProvider>
    </ThemeProvider>
  );
}

export default App;
