import { useEffect, useRef, useState, useCallback } from 'react';
import { api } from '../../services/apiClient';
import {
  SpatialOverlay,
  ViewportCanvasBox,
  SpatialPointer,
  SpatialRipple,
  GestureFeedbackBanner
} from './SpatialDesktopViewer.styles';
import { X, Hand, Camera, Monitor, Play, Eye, CheckCircle, BookOpen } from '@phosphor-icons/react';

interface SpatialDesktopViewerProps {
  isOpen: boolean;
  onClose: () => void;
}

interface SpatialRippleItem {
  id: number;
  x: number;
  y: number;
}

export function SpatialDesktopViewer({ isOpen, onClose }: SpatialDesktopViewerProps) {
  const webcamVideoRef = useRef<HTMLVideoElement | null>(null);
  const screenVideoRef = useRef<HTMLVideoElement | null>(null);
  const screenCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const handOverlayCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const viewportBoxRef = useRef<HTMLDivElement | null>(null);

  const [isStreamingScreen, setIsStreamingScreen] = useState(false);
  const [hasHand, setHasHand] = useState(false);
  const [isPinching, setIsPinching] = useState(false);
  const [passThroughOpacity, setPassThroughOpacity] = useState(0.2); // Transparência de fundo
  const [pointerPos, setPointerPos] = useState({ x: 400, y: 300 });
  const [ripples, setRipples] = useState<SpatialRippleItem[]>([]);
  const [gestureBanner, setGestureBanner] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState('Clique em "Transmitir Tela" para espelhar seu Mac');

  const lastClickTimeRef = useRef(0);
  const lastSwipeTimeRef = useRef(0);
  const prevPalmXRef = useRef<number | null>(null);
  const smoothedPosRef = useRef({ x: 400, y: 300 });
  const animFrameIdRef = useRef<number | null>(null);

  // Iniciar compartilhamento de tela do macOS ao vivo em 60 FPS
  const handleStartScreenShare = async () => {
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          displaySurface: 'monitor',
          frameRate: { ideal: 60, max: 60 }
        },
        audio: false
      });

      if (screenVideoRef.current) {
        screenVideoRef.current.srcObject = stream;
        await screenVideoRef.current.play();
        setIsStreamingScreen(true);
        setStatusMsg('🟢 Área de Trabalho Transmitida em 60 FPS');
      }

      // Parar transmissão se o usuário fechar a partilha nativa do Mac
      stream.getVideoTracks()[0].onended = () => {
        setIsStreamingScreen(false);
        setStatusMsg('Transmissão encerrada');
      };
    } catch (err) {
      console.error('Erro ao capturar tela do Mac:', err);
      setStatusMsg('Erro de permissão para transmitir a tela');
    }
  };

  // Renderizar o feed de vídeo da tela do Mac no Canvas principal
  useEffect(() => {
    if (!isStreamingScreen) return;

    const renderScreenFrame = () => {
      if (screenVideoRef.current && screenCanvasRef.current) {
        const video = screenVideoRef.current;
        const canvas = screenCanvasRef.current;
        const ctx = canvas.getContext('2d');

        if (ctx && video.readyState >= 2) {
          if (canvas.width !== video.videoWidth || canvas.height !== video.videoHeight) {
            canvas.width = video.videoWidth || 1920;
            canvas.height = video.videoHeight || 1080;
          }
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        }
      }
      animFrameIdRef.current = requestAnimationFrame(renderScreenFrame);
    };

    animFrameIdRef.current = requestAnimationFrame(renderScreenFrame);

    return () => {
      if (animFrameIdRef.current) cancelAnimationFrame(animFrameIdRef.current);
    };
  }, [isStreamingScreen]);

  // Disparar clique espacial
  const triggerSpatialPinchClick = useCallback((localX: number, localY: number) => {
    const now = Date.now();
    if (now - lastClickTimeRef.current < 400) return; // Cooldown de 400ms
    lastClickTimeRef.current = now;

    // Criar efeito visual de onda de clique espacial
    const rippleId = now;
    setRipples(prev => [...prev.slice(-3), { id: rippleId, x: localX, y: localY }]);
    setTimeout(() => {
      setRipples(prev => prev.filter(r => r.id !== rippleId));
    }, 550);

    // Se estiver transmitindo a tela, disparar o clique real
    if (viewportBoxRef.current) {
      const boxRect = viewportBoxRef.current.getBoundingClientRect();
      const xRatio = Math.max(0, Math.min(1, localX / boxRect.width));
      const yRatio = Math.max(0, Math.min(1, localY / boxRect.height));

      // 1. Enviar clique de hardware nativo para o macOS via API Backend
      api.post('/api/v1/spatial/click', { xRatio, yRatio, isDouble: false }).catch(() => {});

      // 2. Disparar clique local na aba do navegador
      const globalX = boxRect.left + localX;
      const globalY = boxRect.top + localY;
      const element = document.elementFromPoint(globalX, globalY) as HTMLElement | null;
      if (element) {
        element.click();
        element.focus?.();
      }
    }
  }, []);

  // Rastreamento 3D das mãos via MediaPipe Hands
  useEffect(() => {
    if (!isOpen) return;

    let isMounted = true;
    let cameraInstance: any = null;
    let handsInstance: any = null;

    const loadMediaPipe = async () => {
      const windowAny = window as any;

      if (!windowAny.Camera) {
        await new Promise(r => {
          const s = document.createElement('script');
          s.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js';
          s.crossOrigin = 'anonymous';
          s.onload = r;
          document.head.appendChild(s);
        });
      }

      if (!windowAny.drawConnectors) {
        await new Promise(r => {
          const s = document.createElement('script');
          s.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/drawing_utils/drawing_utils.js';
          s.crossOrigin = 'anonymous';
          s.onload = r;
          document.head.appendChild(s);
        });
      }

      if (!windowAny.Hands) {
        await new Promise(r => {
          const s = document.createElement('script');
          s.src = 'https://cdn.jsdelivr.net/npm/@mediapipe/hands/hands.js';
          s.crossOrigin = 'anonymous';
          s.onload = r;
          document.head.appendChild(s);
        });
      }

      if (!isMounted) return;

      if (!windowAny.Hands || !webcamVideoRef.current || !handOverlayCanvasRef.current) return;

      handsInstance = new windowAny.Hands({
        locateFile: (file: string) => `https://cdn.jsdelivr.net/npm/@mediapipe/hands/${file}`
      });

      handsInstance.setOptions({
        maxNumHands: 1,
        modelComplexity: 1,
        minDetectionConfidence: 0.65,
        minTrackingConfidence: 0.65
      });

      const overlayCtx = handOverlayCanvasRef.current.getContext('2d');

      handsInstance.onResults((results: any) => {
        if (!isMounted || !handOverlayCanvasRef.current || !overlayCtx || !viewportBoxRef.current) return;

        const boxRect = viewportBoxRef.current.getBoundingClientRect();
        handOverlayCanvasRef.current.width = boxRect.width;
        handOverlayCanvasRef.current.height = boxRect.height;

        overlayCtx.save();
        overlayCtx.clearRect(0, 0, boxRect.width, boxRect.height);

        if (results.multiHandLandmarks && results.multiHandLandmarks.length > 0) {
          setHasHand(true);
          const landmarks = results.multiHandLandmarks[0];

          // Desenhar o esqueleto vetorial 3D da mão sobre a área de trabalho
          if (windowAny.drawConnectors && windowAny.drawLandmarks && windowAny.HAND_CONNECTIONS) {
            windowAny.drawConnectors(overlayCtx, landmarks, windowAny.HAND_CONNECTIONS, { color: '#00f2fe', lineWidth: 3 });
            windowAny.drawLandmarks(overlayCtx, landmarks, { color: '#10b981', lineWidth: 1, radius: 4 });
          }

          const indexTip = landmarks[8];
          const thumbTip = landmarks[4];

          // Mapeamento 1:1 para a área do viewport da tela do Mac
          const targetLocalX = (1 - indexTip.x) * boxRect.width;
          const targetLocalY = indexTip.y * boxRect.height;

          // Suavizar movimento (Filtro Passa-Baixa EMA)
          const smoothedX = smoothedPosRef.current.x + (targetLocalX - smoothedPosRef.current.x) * 0.35;
          const smoothedY = smoothedPosRef.current.y + (targetLocalY - smoothedPosRef.current.y) * 0.35;

          smoothedPosRef.current = { x: smoothedX, y: smoothedY };
          setPointerPos({ x: smoothedX, y: smoothedY });

          // Detectar velocidade da mão para Virar Página / Swipe Lateral (Gesto "Virar Folha")
          const currentPalmX = 1 - landmarks[9].x; // X do centro da palma espelhado
          const now = Date.now();

          if (prevPalmXRef.current !== null && now - lastSwipeTimeRef.current > 750) {
            const vx = currentPalmX - prevPalmXRef.current;

            if (vx > 0.12) {
              // Swipe para a Direita (Virar Página para Frente)
              lastSwipeTimeRef.current = now;
              setGestureBanner('📖 Virou a Página para a Direita! (Próxima Tela / Aba)');
              setTimeout(() => setGestureBanner(null), 1400);

              // Disparar evento de navegação por teclado (Seta Direita / PageDown)
              window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', code: 'ArrowRight', bubbles: true }));
            } else if (vx < -0.12) {
              // Swipe para a Esquerda (Voltar Página)
              lastSwipeTimeRef.current = now;
              setGestureBanner('📖 Virou a Página para a Esquerda! (Tela Anterior / Aba)');
              setTimeout(() => setGestureBanner(null), 1400);

              // Disparar evento de navegação por teclado (Seta Esquerda / PageUp)
              window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', code: 'ArrowLeft', bubbles: true }));
            }
          }
          prevPalmXRef.current = currentPalmX;

          // Calcular distância Euclidiana entre indicador e polegar em 3D
          const dx = indexTip.x - thumbTip.x;
          const dy = indexTip.y - thumbTip.y;
          const dz = indexTip.z - thumbTip.z;
          const pinchDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

          const pinching = pinchDist < 0.052;
          setIsPinching(pinching);

          if (pinching) {
            setStatusMsg('👌 Segurando / Arrastando no Ar...');
            triggerSpatialPinchClick(smoothedX, smoothedY);
          } else if (isStreamingScreen) {
            setStatusMsg('🟢 Área de Trabalho + ✋ Mão Rastreada (Mova de lado para virar página!)');
          }
        } else {
          setHasHand(false);
          setIsPinching(false);
          if (isStreamingScreen) setStatusMsg('🟢 Área de Trabalho Ativa | 📷 Aguardando Mão');
        }
        overlayCtx.restore();
      });

      if (windowAny.Camera && webcamVideoRef.current) {
        cameraInstance = new windowAny.Camera(webcamVideoRef.current, {
          onFrame: async () => {
            if (webcamVideoRef.current && handsInstance) {
              await handsInstance.send({ image: webcamVideoRef.current });
            }
          },
          width: 640,
          height: 480
        });
        await cameraInstance.start();
      }
    };

    void loadMediaPipe();

    return () => {
      isMounted = false;
      if (cameraInstance) cameraInstance.stop();
      if (handsInstance) handsInstance.close();
    };
  }, [isOpen, isStreamingScreen, triggerSpatialPinchClick]);

  if (!isOpen) return null;

  return (
    <SpatialOverlay $passThroughOpacity={passThroughOpacity}>
      <video ref={webcamVideoRef} playsInline muted style={{ display: 'none' }} />
      <video ref={screenVideoRef} playsInline muted style={{ display: 'none' }} />

      <div className="hud-topbar">
        <div className="hud-title">
          <h2>
            <Monitor size={24} /> Área de Trabalho Espacial do Mac 3D
          </h2>

          <span className={`status-pill ${isPinching ? 'pinching' : hasHand ? 'active' : 'waiting'}`}>
            {isPinching ? (
              <>
                <CheckCircle size={12} /> Pinça Espacial
              </>
            ) : hasHand ? (
              <>
                <Hand size={12} /> Mão Rastreada
              </>
            ) : (
              <>
                <Camera size={12} /> Webcam
              </>
            )}
          </span>
        </div>

        <div className="hud-controls">
          <div className="control-slider">
            <Eye size={16} /> Fundo Pass-Through:
            <input
              type="range"
              min="0"
              max="0.85"
              step="0.05"
              value={passThroughOpacity}
              onChange={e => setPassThroughOpacity(Number(e.target.value))}
              title="Ajustar transparência de fundo"
            />
          </div>

          {!isStreamingScreen ? (
            <button type="button" onClick={handleStartScreenShare}>
              <Play size={16} /> Transmitir Tela do Mac
            </button>
          ) : (
            <button type="button" onClick={() => setIsStreamingScreen(false)}>
              <Monitor size={16} /> Interromper Transmissão
            </button>
          )}

          <button type="button" className="close-btn" onClick={onClose} title="Sair da exibição espacial">
            <X size={16} /> Sair
          </button>
        </div>
      </div>

      <ViewportCanvasBox ref={viewportBoxRef}>
        {gestureBanner && (
          <GestureFeedbackBanner>
            <BookOpen size={18} /> {gestureBanner}
          </GestureFeedbackBanner>
        )}
        {isStreamingScreen ? (
          <>
            <canvas ref={screenCanvasRef} className="screen-canvas" />
            <canvas ref={handOverlayCanvasRef} className="hand-overlay-canvas" />

            <SpatialPointer $x={pointerPos.x} $y={pointerPos.y} $isPinching={isPinching} />

            {ripples.map(r => (
              <SpatialRipple key={r.id} $x={r.x} $y={r.y} />
            ))}
          </>
        ) : (
          <div className="no-stream-placeholder">
            <Monitor size={64} style={{ color: '#00f2fe' }} />
            <h3>Transmissão Espacial da Área de Trabalho do Mac</h3>
            <p>
              Clique no botão abaixo para espelhar a tela inteira do seu Mac em 60 FPS e controlá-la no ar usando os movimentos da sua mão!
            </p>
            <button type="button" onClick={handleStartScreenShare}>
              <Play size={18} /> Transmitir Área de Trabalho do Mac
            </button>
          </div>
        )}
      </ViewportCanvasBox>

      <div style={{ fontSize: '0.78rem', color: '#94a3b8', textAlign: 'center' }}>
        {statusMsg}
      </div>
    </SpatialOverlay>
  );
}
