/// <reference types="vite/client" />

/**
 * Ponte exposta pelo preload do Electron. Ausente no navegador — por isso tudo é opcional e cada
 * chamada precisa do `?.`: a mesma tela roda nos dois lugares.
 */
interface AventoDesktopBridge {
  isDesktop: true;
  platform: string;
  /** Faz a janela flutuar sobre os outros aplicativos. */
  setOverlayMode: (enabled: boolean) => Promise<boolean>;
}

interface Window {
  avento?: AventoDesktopBridge;
}
