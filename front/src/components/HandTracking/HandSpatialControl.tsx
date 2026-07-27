import { useEffect, useRef, useState, useCallback } from 'react';
import { api } from '../../services/apiClient';
import { GestureEngine, type GestureEvent } from './gestureEngine';
import { FloatingWidget, HolographicCursor, ClickRipple } from './HandSpatialControl.styles';
import { X, Hand, Camera, CheckCircle } from '@phosphor-icons/react';

interface HandSpatialControlProps {
  isActive: boolean;
  onClose: () => void;
}

interface RippleEffect {
  id: number;
  x: number;
  y: number;
}

export function HandSpatialControl({ isActive, onClose }: HandSpatialControlProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [hasHand, setHasHand] = useState(false);
  const [isPinching, setIsPinching] = useState(false);
  const [cursorPos, setCursorPos] = useState<{ x: number; y: number }>({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
  const [ripples, setRipples] = useState<RippleEffect[]>([]);
  const [statusMessage, setStatusMessage] = useState('Iniciando Câmera...');

  const lastMoveTimeRef = useRef(0);
  // Toda a lógica de gesto vive no motor: histerese, arbitragem entre pinças, toque vs arrasto.
  // O componente só traduz os eventos dele em chamadas de API e em pixels para o cursor visual.
  const engineRef = useRef(new GestureEngine());

  /** Onda visual no ponto do clique. Puramente cosmético. */
  const showRipple = useCallback((x: number, y: number) => {
    const rippleId = Date.now();
    setRipples(prev => [...prev.slice(-3), { id: rippleId, x, y }]);
    setTimeout(() => {
      setRipples(prev => prev.filter(r => r.id !== rippleId));
    }, 550);
  }, []);

  /**
   * Aplica um evento do motor. Só o caminho do sistema operacional é usado: o clique do Robot já
   * cai sobre a janela do Avento e aciona o elemento, então o `element.click()` sintético que
   * existia aqui fazia cada botão receber o evento duas vezes.
   */
  const applyGestureEvent = useCallback((event: GestureEvent) => {
    const xRatio = event.x;
    const yRatio = event.y;

    switch (event.type) {
      case 'move': {
        const now = Date.now();
        if (now - lastMoveTimeRef.current <= 40) return;
        lastMoveTimeRef.current = now;
        api.post('/api/v1/spatial/move', { xRatio, yRatio }).catch(() => {});
        return;
      }
      case 'down':
        setStatusMessage(event.button === 'left' ? '👌 Segurando / Arrastando...' : '🤏 Clique Direito');
        if (event.button === 'left') {
          api.post('/api/v1/spatial/drag', { xRatio, yRatio, isDown: true }).catch(() => {});
        }
        return;
      case 'up':
        setStatusMessage('✋ Mão Rastreada');
        if (event.button === 'left') {
          api.post('/api/v1/spatial/drag', { xRatio, yRatio, isDown: false }).catch(() => {});
        }
        return;
      case 'click':
        showRipple(xRatio * window.innerWidth, yRatio * window.innerHeight);
        setStatusMessage(event.button === 'left' ? '👆 Clique' : '🤏 Clique Direito');
        api
          .post('/api/v1/spatial/click', {
            xRatio,
            yRatio,
            isDouble: false,
            isRight: event.button === 'right',
          })
          .catch(() => {});
        return;
    }
  }, [showRipple]);

  useEffect(() => {
    if (!isActive) return;

    let isMounted = true;
    let cameraInstance: any = null;
    let handsInstance: any = null;

    const loadScripts = async () => {
      if (!(window as any).Camera) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js';
          script.crossOrigin = 'anonymous';
          script.onload = resolve;
          document.head.appendChild(script);
        });
      }

      if (!(window as any).drawConnectors) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils/drawing_utils.js';
          script.crossOrigin = 'anonymous';
          script.onload = resolve;
          document.head.appendChild(script);
        });
      }

      if (!(window as any).Hands) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/hands/hands.js';
          script.crossOrigin = 'anonymous';
          script.onload = resolve;
          document.head.appendChild(script);
        });
      }

      if (!isMounted) return;

      const windowAny = window as any;
      if (!windowAny.Hands || !videoRef.current || !canvasRef.current) return;

      setStatusMessage('Carregando Modelo de Mãos...');

      handsInstance = new windowAny.Hands({
        locateFile: (file: string) => `https://cdn.jsdelivr.net/npm/@mediapipe/hands/${file}`
      });

      handsInstance.setOptions({
        maxNumHands: 1,
        modelComplexity: 1,
        minDetectionConfidence: 0.65,
        minTrackingConfidence: 0.65
      });

      const canvasCtx = canvasRef.current.getContext('2d');

      handsInstance.onResults((results: any) => {
        if (!isMounted || !canvasRef.current || !canvasCtx) return;

        canvasCtx.save();
        canvasCtx.clearRect(0, 0, canvasRef.current.width, canvasRef.current.height);
        canvasCtx.drawImage(results.image, 0, 0, canvasRef.current.width, canvasRef.current.height);

        if (results.multiHandLandmarks && results.multiHandLandmarks.length > 0) {
          setHasHand(true);
          const landmarks = results.multiHandLandmarks[0];

          if (windowAny.drawConnectors && windowAny.drawLandmarks && windowAny.HAND_CONNECTIONS) {
            windowAny.drawConnectors(canvasCtx, landmarks, windowAny.HAND_CONNECTIONS, { color: '#00f2fe', lineWidth: 3 });
            windowAny.drawLandmarks(canvasCtx, landmarks, { color: '#10b981', lineWidth: 1, radius: 3 });
          }

          // O motor devolve eventos já resolvidos (move/down/up/click) em coordenadas 0..1.
          const events = engineRef.current.update(landmarks, Date.now());
          events.forEach(applyGestureEvent);

          const position = engineRef.current.position;
          if (position) {
            setCursorPos({ x: position.x * window.innerWidth, y: position.y * window.innerHeight });
          }
          setIsPinching(engineRef.current.activeButton !== null);
        } else {
          // Sem isso o botão fica pressionado quando a mão sai do frame no meio de um arrasto.
          engineRef.current.update(null, Date.now()).forEach(applyGestureEvent);
          setHasHand(false);
          setIsPinching(false);
          setStatusMessage('📷 Aguardando Mão na Câmera');
        }
        canvasCtx.restore();
      });

      // Inicializar captura da câmera do Mac
      if (windowAny.Camera && videoRef.current) {
        cameraInstance = new windowAny.Camera(videoRef.current, {
          onFrame: async () => {
            if (videoRef.current && handsInstance) {
              await handsInstance.send({ image: videoRef.current });
            }
          },
          width: 320,
          height: 240
        });
        await cameraInstance.start();
      }
    };

    void loadScripts();

    return () => {
      isMounted = false;
      if (cameraInstance) cameraInstance.stop();
      if (handsInstance) handsInstance.close();
    };
  }, [isActive, applyGestureEvent]);

  if (!isActive) return null;

  return (
    <>
      <HolographicCursor $x={cursorPos.x} $y={cursorPos.y} $isPinching={isPinching} />

      {ripples.map(r => (
        <ClickRipple key={r.id} $x={r.x} $y={r.y} />
      ))}

      <FloatingWidget>
        <div className="widget-header">
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Hand size={16} color="#00f2fe" /> Gestos 3D VR
          </span>

          <span className={`status-tag ${isPinching ? 'pinching' : hasHand ? 'active' : 'waiting'}`}>
            {isPinching ? (
              <>
                <CheckCircle size={10} /> Pinça
              </>
            ) : hasHand ? (
              <>
                <Hand size={10} /> Ativo
              </>
            ) : (
              <>
                <Camera size={10} /> Câmera
              </>
            )}
          </span>

          <button type="button" onClick={onClose} title="Fechar controle por gestos">
            <X size={14} />
          </button>
        </div>

        <div className="canvas-wrapper">
          <video ref={videoRef} playsInline muted />
          <canvas ref={canvasRef} width={320} height={240} />
        </div>

        <div className="gesture-hint">
          {statusMessage}<br />
          Junte o <strong>Indicador + Polegar</strong> para Clicar!
        </div>
      </FloatingWidget>
    </>
  );
}
