// Ponte entre o processo principal e o React.
//
// Minima de proposito: cada coisa exposta aqui vira superficie de ataque para qualquer conteudo que
// a janela venha a carregar. Sao dois dados de leitura e uma unica acao, que nao le nada e nao
// aceita caminho nem comando — so um booleano.
'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('avento', {
  isDesktop: true,
  platform: process.platform,

  /**
   * Faz a janela flutuar sobre os outros aplicativos.
   *
   * <p>Necessario para controlar outro programa por gesto: sem isso a janela fica atras da
   * apresentacao e o cursor e o widget da camera somem justamente quando sao necessarios.
   */
  setOverlayMode: enabled => ipcRenderer.invoke('avento:overlay-mode', Boolean(enabled)),
});
