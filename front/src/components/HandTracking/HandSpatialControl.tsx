import { useEffect, useRef, useState, useCallback } from 'react';
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
  const smoothedPosRef = useRef({ x: window.innerWidth / 2, y: window.innerHeight / 2 });

  const triggerSpatialClick = useCallback((x: number, y: number) => {
    const now = Date.now();
    if (now - lastClickTimeRef.current < 380) return; // Cooldown de 380ms para evitar duplo clique involuntário
    lastClickTimeRef.current = now;

    // Criar efeito visual de onda de clique
    const rippleId = now;
    setRipples(prev => [...prev.slice(-3), { id: rippleId, x, y }]);
    setTimeout(() => {
      setRipples(prev => prev.filter(r => r.id !== rippleId));
    }, 550);

    // Encontrar elemento sob o cursor e disparar o clique real
    const element = document.elementFromPoint(x, y) as HTMLElement | null;
    if (element) {
      element.click();
      element.focus?.();
    }
  }, []);

  useEffect(() => {
    if (!isActive) return;

    let isMounted = true;
    let cameraInstance: any = null;
    let handsInstance: any = null;

    const loadScripts = async () => {
      // 1. Carregar Camera Utils CDN
      if (!(window as any).Camera) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js';
          script.crossOrigin = 'anonymous';
          script.onload = resolve;
          document.head.appendChild(script);
        });
      }

      // 2. Carregar Drawing Utils CDN
      if (!(window as any).drawConnectors) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils/drawing_utils.js';
          script.crossOrigin = 'anonymous';
          script.onload = resolve;
          document.head.appendChild(script);
        });
      }

      // 3. Carregar MediaPipe Hands CDN
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

      // Inicializar detector de mãos
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

          // Desenhar o esqueleto vetorial da mão no canvas da webcam
          if (windowAny.drawConnectors && windowAny.drawLandmarks && windowAny.HAND_CONNECTIONS) {
            windowAny.drawConnectors(canvasCtx, landmarks, windowAny.HAND_CONNECTIONS, { color: '#00f2fe', lineWidth: 3 });
            windowAny.drawLandmarks(canvasCtx, landmarks, { color: '#10b981', lineWidth: 1, radius: 3 });
          }

          // Posição da ponta do dedo indicador (Landmark 8)
          const indexTip = landmarks[8];
          // Posição da ponta do polegar (Landmark 4)
          const thumbTip = landmarks[4];

          // Espelhar X porque a webcam está invertida
          const targetX = (1 - indexTip.x) * window.innerWidth;
          const targetY = indexTip.y * window.innerHeight;

          // Suavizar movimento do cursor (Filtro Passa-Baixa / EMA)
          const smoothedX = smoothedPosRef.current.x + (targetX - smoothedPosRef.current.x) * 0.35;
          const smoothedY = smoothedPosRef.current.y + (targetY - smoothedPosRef.current.y) * 0.35;
          smoothedPosRef.current = { x: smoothedX, y: smoothedY };
          setCursorPos({ x: smoothedX, y: smoothedY });

          // Calcular distância Euclidiana entre indicador e polegar em 3D
          const dx = indexTip.x - thumbTip.x;
          const dy = indexTip.y - thumbTip.y;
          const dz = indexTip.z - thumbTip.z;
          const pinchDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

          // Limiar de Pinça (Gesto de Clique)
          const pinching = pinchDistance < 0.052;
          setIsPinching(pinching);

          if (pinching) {
            setStatusMessage('👌 Gesto de Pinça!');
            triggerSpatialClick(smoothedX, smoothedY);
          } else {
            setStatusMessage('✋ Mão Rastreada');
          }
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
