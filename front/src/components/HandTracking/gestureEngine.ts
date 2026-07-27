/**
 * Motor de gesto: entra landmark de mão, sai evento de ponteiro.
 *
 * Sem React, sem câmera, sem rede — de propósito. A lógica de gesto morava dentro do componente,
 * misturada com canvas e HTTP, e por isso não dava para testá-la sem uma webcam: ajustar limiar era
 * tentativa e erro. Aqui ela é função pura do estado interno, e uma sequência gravada de landmarks
 * reproduz qualquer bug.
 *
 * Os eventos que saem daqui são normalizados (0..1) e independentes de tela. É essa fronteira que
 * atravessa a rede quando quem aplica o cursor é outra máquina.
 */

export type Landmark = { x: number; y: number; z: number };
export type Landmarks = Landmark[];

export type PointerButton = 'left' | 'right';

export type GestureEvent =
  | { type: 'move'; x: number; y: number }
  | { type: 'down'; button: PointerButton; x: number; y: number }
  | { type: 'up'; button: PointerButton; x: number; y: number }
  | { type: 'click'; button: PointerButton; x: number; y: number };

export type GestureConfig = {
  /** Fração do tamanho da mão abaixo da qual a pinça fecha. */
  pinchEnter: number;
  /** Fração acima da qual a pinça abre. Histerese: sempre > pinchEnter. */
  pinchExit: number;
  /** Até este tempo (ms) sem passar do slop, soltar vira clique em vez de fim de arrasto. */
  tapMaxMs: number;
  /** Movimento (em unidades normalizadas) que compromete o gesto como arrasto. */
  tapSlop: number;
  /** Suavização parado (0..1). Menor = mais estável, mais lento. */
  alphaSlow: number;
  /** Suavização em movimento rápido. Maior = menos atraso. */
  alphaFast: number;
  /** Acima deste delta normalizado, usa alphaFast. */
  fastDelta: number;
};

export const DEFAULT_GESTURE_CONFIG: GestureConfig = {
  // Frações do tamanho da mão, não do frame — ver handSpan abaixo.
  pinchEnter: 0.35,
  pinchExit: 0.45,
  tapMaxMs: 250,
  tapSlop: 0.035,
  alphaSlow: 0.35,
  alphaFast: 0.6,
  fastDelta: 0.02,
};

const WRIST = 0;
const THUMB_TIP = 4;
const INDEX_TIP = 8;
const MIDDLE_MCP = 9;
const MIDDLE_TIP = 12;

/**
 * Distância 2D. O eixo z do MediaPipe é profundidade relativa de baixa confiança — é o canal mais
 * ruidoso dos landmarks, e incluí-lo fazia a distância de pinça tremer com os dedos parados.
 */
function distance2D(a: Landmark, b: Landmark): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/**
 * Comprimento de referência da própria mão (pulso → base do dedo médio).
 *
 * Os landmarks são normalizados pelo FRAME, não pela mão: longe da câmera todos os pontos ficam
 * mais juntos, então um limiar fixo fechava a pinça sozinho; perto, não fechava nunca. Dividir por
 * este comprimento torna o limiar independente da distância em que a pessoa senta.
 */
function handSpan(landmarks: Landmarks): number {
  return distance2D(landmarks[WRIST], landmarks[MIDDLE_MCP]);
}

type PinchState = { closed: boolean };

type ActiveGesture = {
  button: PointerButton;
  startedAt: number;
  startX: number;
  startY: number;
  committedToDrag: boolean;
};

export class GestureEngine {
  private readonly config: GestureConfig;
  private readonly leftPinch: PinchState = { closed: false };
  private readonly rightPinch: PinchState = { closed: false };
  private active: ActiveGesture | null = null;
  private smoothed: { x: number; y: number } | null = null;

  constructor(config: Partial<GestureConfig> = {}) {
    this.config = { ...DEFAULT_GESTURE_CONFIG, ...config };
  }

  /** Posição suavizada atual, em 0..1. Null enquanto nenhuma mão foi vista. */
  get position(): { x: number; y: number } | null {
    return this.smoothed;
  }

  get isDragging(): boolean {
    return this.active?.committedToDrag ?? false;
  }

  get activeButton(): PointerButton | null {
    return this.active?.button ?? null;
  }

  update(landmarks: Landmarks | null, now: number): GestureEvent[] {
    if (!landmarks || landmarks.length <= MIDDLE_TIP) {
      return this.releaseEverything();
    }

    const events: GestureEvent[] = [];
    const { x, y } = this.smooth(landmarks[INDEX_TIP]);
    events.push({ type: 'move', x, y });

    const span = handSpan(landmarks);
    // Mão degenerada (span ~0) só aparece em detecção ruim; tratar como ausência evita divisão por
    // zero virar pinça permanente.
    if (span < 1e-6) {
      return [...events, ...this.releaseActive(x, y, now)];
    }

    const leftClosed = this.updatePinch(
      this.leftPinch,
      distance2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP]) / span
    );
    const rightClosed = this.updatePinch(
      this.rightPinch,
      distance2D(landmarks[THUMB_TIP], landmarks[MIDDLE_TIP]) / span
    );

    events.push(...this.arbitrate(leftClosed, rightClosed, landmarks, span, x, y, now));
    return events;
  }

  private updatePinch(state: PinchState, ratio: number): boolean {
    if (state.closed) {
      if (ratio > this.config.pinchExit) state.closed = false;
    } else if (ratio < this.config.pinchEnter) {
      state.closed = true;
    }
    return state.closed;
  }

  /**
   * Um gesto ativo por vez. Fechar o indicador contra o polegar costuma arrastar o médio junto, e
   * com dois `if` independentes isso disparava clique direito no meio de um arrasto. Aqui o menor
   * dos dois vence, e o vencedor fica travado até soltar.
   */
  private arbitrate(
    leftClosed: boolean,
    rightClosed: boolean,
    landmarks: Landmarks,
    span: number,
    x: number,
    y: number,
    now: number
  ): GestureEvent[] {
    if (this.active) {
      const stillClosed = this.active.button === 'left' ? leftClosed : rightClosed;
      if (stillClosed) {
        if (!this.active.committedToDrag && this.movedPastSlop(x, y)) {
          this.active.committedToDrag = true;
        }
        return [];
      }
      return this.releaseActive(x, y, now);
    }

    if (!leftClosed && !rightClosed) {
      return [];
    }

    const button = this.winner(leftClosed, rightClosed, landmarks, span);
    this.active = { button, startedAt: now, startX: x, startY: y, committedToDrag: false };
    return [{ type: 'down', button, x, y }];
  }

  private winner(
    leftClosed: boolean,
    rightClosed: boolean,
    landmarks: Landmarks,
    span: number
  ): PointerButton {
    if (leftClosed !== rightClosed) {
      return leftClosed ? 'left' : 'right';
    }
    const leftRatio = distance2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP]) / span;
    const rightRatio = distance2D(landmarks[THUMB_TIP], landmarks[MIDDLE_TIP]) / span;
    return leftRatio <= rightRatio ? 'left' : 'right';
  }

  private movedPastSlop(x: number, y: number): boolean {
    if (!this.active) return false;
    return Math.hypot(x - this.active.startX, y - this.active.startY) > this.config.tapSlop;
  }

  /**
   * Toque e arrasto são exclusivos por construção. Antes, soltar a pinça mandava o release do
   * arrasto E um clique separado: dois press/release no mesmo ponto em milissegundos, que o sistema
   * operacional lê como duplo-clique.
   */
  private releaseActive(x: number, y: number, now: number): GestureEvent[] {
    if (!this.active) return [];
    const gesture = this.active;
    this.active = null;

    const wasTap = !gesture.committedToDrag && now - gesture.startedAt < this.config.tapMaxMs;
    if (wasTap) {
      return [{ type: 'click', button: gesture.button, x, y }];
    }
    return [{ type: 'up', button: gesture.button, x, y }];
  }

  /** Mão saiu do frame: soltar o botão, senão o cursor fica preso arrastando. */
  private releaseEverything(): GestureEvent[] {
    this.leftPinch.closed = false;
    this.rightPinch.closed = false;
    if (!this.active) return [];
    const gesture = this.active;
    this.active = null;
    const at = this.smoothed ?? { x: 0.5, y: 0.5 };
    return [{ type: 'up', button: gesture.button, x: at.x, y: at.y }];
  }

  /**
   * Espelha o eixo x (câmera frontal inverte) e suaviza. Alpha maior no movimento rápido reduz o
   * atraso; menor parado segura o tremor do landmark.
   */
  private smooth(indexTip: Landmark): { x: number; y: number } {
    const targetX = 1 - indexTip.x;
    const targetY = indexTip.y;

    if (!this.smoothed) {
      this.smoothed = { x: targetX, y: targetY };
      return this.smoothed;
    }

    const delta = Math.hypot(targetX - this.smoothed.x, targetY - this.smoothed.y);
    const alpha = delta > this.config.fastDelta ? this.config.alphaFast : this.config.alphaSlow;
    this.smoothed = {
      x: clamp01(this.smoothed.x + (targetX - this.smoothed.x) * alpha),
      y: clamp01(this.smoothed.y + (targetY - this.smoothed.y) * alpha),
    };
    return this.smoothed;
  }
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}
