import { describe, expect, it } from 'vitest';
import { GestureEngine, type GestureEvent, type Landmarks } from './gestureEngine';

/**
 * Sequências sintéticas de landmarks no lugar de webcam. Era isso que faltava: a lógica de gesto
 * vivia dentro do componente React, então nenhum limiar podia ser verificado sem uma mão na frente
 * da câmera, e cada ajuste era tentativa e erro.
 */

/** Mão com 21 landmarks. `pinch` é a distância polegar↔indicador em fração do tamanho da mão. */
function hand(options: {
  x?: number;
  y?: number;
  pinch?: number;
  middlePinch?: number;
  scale?: number;
}): Landmarks {
  const { x = 0.5, y = 0.5, pinch = 1, middlePinch = 1, scale = 0.2 } = options;
  const points: Landmarks = Array.from({ length: 21 }, () => ({ x, y, z: 0 }));
  points[0] = { x, y, z: 0 }; // pulso
  points[9] = { x, y: y - scale, z: 0 }; // base do médio → handSpan = scale
  points[4] = { x, y, z: 0 }; // polegar
  points[8] = { x: x + pinch * scale, y, z: 0 }; // indicador
  points[12] = { x: x + middlePinch * scale, y, z: 0 }; // médio
  return points;
}

function typesOf(events: GestureEvent[]): string[] {
  return events.filter(e => e.type !== 'move').map(e => e.type);
}

/** Roda até a suavização assentar, para asserções de posição não pegarem o transiente. */
function settle(engine: GestureEngine, landmarks: Landmarks, frames = 40, startAt = 0): void {
  for (let i = 0; i < frames; i++) engine.update(landmarks, startAt + i * 16);
}

describe('GestureEngine', () => {
  it('emite move a cada frame com a mão presente', () => {
    const engine = new GestureEngine();
    const events = engine.update(hand({}), 0);

    expect(events.some(e => e.type === 'move')).toBe(true);
  });

  // O bug original: soltar a pinça mandava release do arrasto E um clique separado — dois
  // press/release no mesmo ponto, que o macOS lê como duplo-clique.
  it('um toque rápido produz um único clique, nunca down+up+click', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1 }), 0);

    const closed = typesOf(engine.update(hand({ pinch: 0.1 }), 100));
    const opened = typesOf(engine.update(hand({ pinch: 1 }), 200));

    expect(closed).toEqual(['down']);
    expect(opened).toEqual(['click']);
    expect(opened).not.toContain('up');
  });

  it('segurar além do tempo de toque vira arrasto e termina em up, sem clique', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1 }), 0);
    engine.update(hand({ pinch: 0.1 }), 0);

    const opened = typesOf(engine.update(hand({ pinch: 1 }), 900));

    expect(opened).toEqual(['up']);
    expect(opened).not.toContain('click');
  });

  it('mover além do slop compromete como arrasto mesmo dentro do tempo de toque', () => {
    const engine = new GestureEngine();
    engine.update(hand({ x: 0.5, pinch: 1 }), 0);
    engine.update(hand({ x: 0.5, pinch: 0.1 }), 0);
    for (let i = 1; i <= 10; i++) engine.update(hand({ x: 0.5 + i * 0.02, pinch: 0.1 }), i * 5);

    const opened = typesOf(engine.update(hand({ x: 0.7, pinch: 1 }), 100));

    expect(opened).toEqual(['up']);
  });

  // Fechar o indicador arrasta o médio junto; com dois `if` independentes isso disparava clique
  // direito no meio de um arrasto esquerdo.
  it('com as duas pinças fechadas, apenas a mais fechada vence', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1, middlePinch: 1 }), 0);

    const events = engine.update(hand({ pinch: 0.1, middlePinch: 0.3 }), 50);
    const down = events.find(e => e.type === 'down');

    expect(down).toMatchObject({ type: 'down', button: 'left' });
    expect(typesOf(events)).toEqual(['down']);
  });

  it('o gesto ativo fica travado até soltar, ignorando a outra pinça', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1, middlePinch: 1 }), 0);
    engine.update(hand({ pinch: 0.1, middlePinch: 1 }), 0);

    const whileHeld = typesOf(engine.update(hand({ pinch: 0.1, middlePinch: 0.05 }), 400));

    expect(whileHeld).toEqual([]);
    expect(engine.activeButton).toBe('left');
  });

  it('pinça do médio sozinha dispara clique direito', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1, middlePinch: 1 }), 0);
    engine.update(hand({ pinch: 1, middlePinch: 0.1 }), 50);

    const events = engine.update(hand({ pinch: 1, middlePinch: 1 }), 150);

    expect(events.find(e => e.type === 'click')).toMatchObject({ button: 'right' });
  });

  // O limiar era fixo sobre coordenadas normalizadas pelo FRAME: longe da câmera a mão inteira
  // encolhe e a pinça fechava sozinha.
  it('o limiar de pinça não depende da distância até a câmera', () => {
    const perto = new GestureEngine();
    const longe = new GestureEngine();

    // Mesma proporção de abertura, tamanhos de mão muito diferentes.
    const abertaPerto = typesOf(perto.update(hand({ pinch: 0.8, scale: 0.4 }), 0));
    const abertaLonge = typesOf(longe.update(hand({ pinch: 0.8, scale: 0.05 }), 0));

    expect(abertaPerto).toEqual([]);
    expect(abertaLonge).toEqual([]);
  });

  it('histerese evita tremer no limiar', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1 }), 0);
    engine.update(hand({ pinch: 0.3 }), 10); // fecha (< 0.35)

    // Entre pinchEnter e pinchExit: continua fechada.
    const between = typesOf(engine.update(hand({ pinch: 0.4 }), 20));

    expect(between).toEqual([]);
    expect(engine.activeButton).toBe('left');
  });

  it('mão fora do frame solta o botão em vez de deixar o cursor preso arrastando', () => {
    const engine = new GestureEngine();
    engine.update(hand({ pinch: 1 }), 0);
    engine.update(hand({ pinch: 0.1 }), 0);

    const gone = typesOf(engine.update(null, 900));

    expect(gone).toEqual(['up']);
    expect(engine.activeButton).toBeNull();
  });

  it('espelha o eixo x da câmera frontal', () => {
    const engine = new GestureEngine();
    settle(engine, hand({ x: 0.2, pinch: 1 }));

    // Indicador em x=0.2+pinch*scale=0.4 no frame → 0.6 na tela.
    expect(engine.position!.x).toBeCloseTo(0.6, 1);
  });

  it('mantém a posição dentro de 0..1', () => {
    const engine = new GestureEngine();
    settle(engine, hand({ x: 0.99, y: 1.4, pinch: 1 }));

    expect(engine.position!.x).toBeGreaterThanOrEqual(0);
    expect(engine.position!.x).toBeLessThanOrEqual(1);
    expect(engine.position!.y).toBeLessThanOrEqual(1);
  });
});
