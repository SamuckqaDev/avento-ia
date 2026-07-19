export const lightTheme = {
  colors: {
    primary: '#0F3F38',
    accent: '#C23B63',
    accentLight: '#4FC5B3',
    accentPale: '#E9F7F4',
    bg: '#F4F7F6',
    surface: '#FFFFFF',
    text: '#17211F',
    textMuted: '#66736F',
    border: '#DDE5E2',
    warning: '#F59E0B',
    white: '#FFFFFF',
  },
  shadows: {
    sm: '0 1px 2px 0 rgb(0 0 0 / 0.05)',
    md: '0 10px 24px -18px rgb(15 23 42 / 0.35)',
    lg: '0 20px 52px -34px rgb(15 23 42 / 0.45)',
  },
  radius: {
    md: '0.5rem',
    lg: '1rem',
    full: '9999px',
  }
};

export const darkTheme = {
  colors: {
    primary: '#67D8C8',
    accent: '#E85F86',
    accentLight: '#94EEDF',
    accentPale: '#123834',
    bg: '#091311',
    surface: '#111A18',
    text: '#F4FBF8',
    textMuted: '#9DA9A5',
    border: '#26332F',
    warning: '#F59E0B',
    white: '#FFFFFF',
  },
  shadows: lightTheme.shadows,
  radius: lightTheme.radius,
};

// Aliasing the default theme type export
export type Theme = typeof lightTheme;
