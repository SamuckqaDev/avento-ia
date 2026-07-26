import { useEffect, useRef, useState, useCallback } from 'react';
import { api } from '../../services/apiClient';
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

  const lastClickTimeRef = useRef(0);
  const lastMoveTimeRef = useRef(0);
  const isLeftHoldingPinchRef = useRef(false);
  const isRightHoldingPinchRef = useRef(false);
  const wasLeftPinchingRef = useRef(false);
  const wasRightPinchingRef = useRef(false);
  const pinchStartTimeRef = useRef(0);
  const smoothedPosRef = useRef({ x: window.innerWidth / 2, y: window.innerHeight / 2 });

  const triggerSpatialClick = useCallback((x: number, y: number, isRight = false) => {
    const now = Date.now();
    if (now - lastClickTimeRef.current < 350) return;
    lastClickTimeRef.current = now;

    // Criar efeito visual de onda de clique
    const rippleId = now;
    setRipples(prev => [...prev.slice(-3), { id: rippleId, x, y }]);
    setTimeout(() => {
      setRipples(prev => prev.filter(r => r.id !== rippleId));
    }, 550);

    const xRatio = Math.max(0, Math.min(1, x / window.innerWidth));
    const yRatio = Math.max(0, Math.min(1, y / window.innerHeight));

    // 1. Enviar para o backend hardware do Mac
    api.post('/api/v1/spatial/click', { xRatio, yRatio, isDouble: false, isRight }).catch(() => {});

    // 2. Disparar clique local no elemento DOM sob o cursor
    const element = document.elementFromPoint(x, y) as HTMLElement | null;
    if (element) {
      if (isRight) {
        element.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
      } else {
        element.click();
        element.focus?.();
      }
    }
  }, []);

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

          const indexTip = landmarks[8];
          const middleTip = landmarks[12];
          const thumbTip = landmarks[4];

          // Cursor alinhado 100% EXATAMENTE na ponta do indicador (Landmark 8)
          const targetX = (1 - indexTip.x) * window.innerWidth;
          const targetY = indexTip.y * window.innerHeight;

          const deltaDist = Math.hypot(targetX - smoothedPosRef.current.x, targetY - smoothedPosRef.current.y);
          const alpha = deltaDist > 25 ? 0.60 : 0.35;

          const smoothedX = smoothedPosRef.current.x + (targetX - smoothedPosRef.current.x) * alpha;
          const smoothedY = smoothedPosRef.current.y + (targetY - smoothedPosRef.current.y) * alpha;
          smoothedPosRef.current = { x: smoothedX, y: smoothedY };
          setCursorPos({ x: smoothedX, y: smoothedY });

          // Transmitir posição contínua do mouse para o backend a cada 40ms
          const nowMove = Date.now();
          if (nowMove - lastMoveTimeRef.current > 40) {
            lastMoveTimeRef.current = nowMove;
            const xRatio = Math.max(0, Math.min(1, smoothedX / window.innerWidth));
            const yRatio = Math.max(0, Math.min(1, smoothedY / window.innerHeight));
            api.post('/api/v1/spatial/move', { xRatio, yRatio }).catch(() => {});
          }

          // 1. PINÇA ESQUERDA (INDICADOR + POLEGAR)
          const dxIndex = indexTip.x - thumbTip.x;
          const dyIndex = indexTip.y - thumbTip.y;
          const dzIndex = indexTip.z - thumbTip.z;
          const indexPinchDist = Math.hypot(dxIndex, dyIndex, dzIndex);

          // Histerese Anti-Soltura: entra com < 0.042, só sai se abrir > 0.066
          if (isLeftHoldingPinchRef.current) {
            if (indexPinchDist > 0.066) {
              isLeftHoldingPinchRef.current = false;
            }
          } else {
            if (indexPinchDist < 0.042) {
              isLeftHoldingPinchRef.current = true;
            }
          }
          const isLeftPinching = isLeftHoldingPinchRef.current;

          // 2. PINÇA DIREITA (DEDO MÉDIO + POLEGAR)
          const dxMiddle = middleTip.x - thumbTip.x;
          const dyMiddle = middleTip.y - thumbTip.y;
          const dzMiddle = middleTip.z - thumbTip.z;
          const middlePinchDist = Math.hypot(dxMiddle, dyMiddle, dzMiddle);

          if (isRightHoldingPinchRef.current) {
            if (middlePinchDist > 0.066) {
              isRightHoldingPinchRef.current = false;
            }
          } else {
            if (middlePinchDist < 0.042) {
              isRightHoldingPinchRef.current = true;
            }
          }
          const isRightPinching = isRightHoldingPinchRef.current;

          setIsPinching(isLeftPinching || isRightPinching);

          const xRatio = Math.max(0, Math.min(1, smoothedX / window.innerWidth));
          const yRatio = Math.max(0, Math.min(1, smoothedY / window.innerHeight));

          // 1. MANUSEIO CLIQUE DIREITO (MÉDIO)
          if (isRightPinching && !wasRightPinchingRef.current) {
            setStatusMessage('🤏 Clique Direito');
            triggerSpatialClick(smoothedX, smoothedY, true);
          }
          wasRightPinchingRef.current = isRightPinching;

          // 2. MANUSEIO CLIQUE ESQUERDO / ARRASTAR (INDICADOR)
          if (isLeftPinching && !wasLeftPinchingRef.current) {
            pinchStartTimeRef.current = Date.now();
            setStatusMessage('👌 Segurando / Arrastando...');
            api.post('/api/v1/spatial/drag', { xRatio, yRatio, isDown: true }).catch(() => {});
          } else if (isLeftPinching && wasLeftPinchingRef.current) {
            setStatusMessage('👌 Segurando / Arrastando...');
          } else if (!isLeftPinching && wasLeftPinchingRef.current) {
            const duration = Date.now() - pinchStartTimeRef.current;
            api.post('/api/v1/spatial/drag', { xRatio, yRatio, isDown: false }).catch(() => {});

            if (duration < 280) {
              triggerSpatialClick(smoothedX, smoothedY, false);
            }
            setStatusMessage('✋ Mão Rastreada');
          }
          wasLeftPinchingRef.current = isLeftPinching;
        } else {
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
  }, [isActive, triggerSpatialClick]);

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
