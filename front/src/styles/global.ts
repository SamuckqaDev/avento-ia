import { createGlobalStyle } from 'styled-components';

export const GlobalStyle = createGlobalStyle`
  :root {
    /* Avenir Next já vem no macOS: dá ao Avento uma leitura mais humana sem trazer fonte externa
       ou depender de rede. Os fallbacks mantêm a mesma hierarquia visual fora do Mac. */
    --avento-font-sans: 'Avenir Next', Avenir, 'Segoe UI Variable', 'Segoe UI', ui-sans-serif, system-ui, sans-serif;
  }

  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  body {
    margin: 0;
    font-family: var(--avento-font-sans);
    font-weight: 400;
    letter-spacing: 0.005em;
    font-kerning: normal;
    font-variant-numeric: tabular-nums;
    background: ${({ theme }) => theme.colors.bg};
    color: ${({ theme }) => theme.colors.text};
    display: flex;
    height: 100vh;
    height: 100dvh;
    overflow: hidden;
    -webkit-font-smoothing: antialiased;
    text-rendering: optimizeLegibility;
  }

  #root {
    width: 100vw;
    height: 100vh;
    height: 100dvh;
  }

  button,
  input,
  textarea,
  select {
    font: inherit;
  }

  button {
    -webkit-tap-highlight-color: transparent;
  }

  button:focus-visible,
  input:focus-visible,
  textarea:focus-visible,
  select:focus-visible {
    outline: 2px solid color-mix(in srgb, ${({ theme }) => theme.colors.accent} 72%, transparent);
    outline-offset: 2px;
  }

  ::selection {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.accent} 22%, transparent);
  }

  /* Scrollbar styling */
  ::-webkit-scrollbar {
    width: 6px;
  }
  ::-webkit-scrollbar-track {
    background: transparent;
  }
  ::-webkit-scrollbar-thumb {
    background: ${({ theme }) => theme.colors.border};
    border-radius: 10px;
  }
  ::-webkit-scrollbar-thumb:hover {
    background: ${({ theme }) => theme.colors.textMuted};
  }
`;
