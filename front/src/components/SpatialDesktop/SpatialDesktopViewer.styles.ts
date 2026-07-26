import styled, { keyframes } from 'styled-components';

const pulseCursor = keyframes`
  0% { transform: translate(-50%, -50%) scale(1); box-shadow: 0 0 12px #00f2fe, 0 0 24px #00f2fe; }
  50% { transform: translate(-50%, -50%) scale(1.2); box-shadow: 0 0 24px #00f2fe, 0 0 40px #00c6ff; }
  100% { transform: translate(-50%, -50%) scale(1); box-shadow: 0 0 12px #00f2fe, 0 0 24px #00f2fe; }
`;

const rippleEffect = keyframes`
  0% { transform: translate(-50%, -50%) scale(0.2); opacity: 1; border-color: #10b981; }
  100% { transform: translate(-50%, -50%) scale(3); opacity: 0; border-color: #10b981; }
`;

export const SpatialRipple = styled.div<{ $x: number; $y: number }>`
  position: absolute;
  left: ${({ $x }) => $x}px;
  top: ${({ $y }) => $y}px;
  width: 60px;
  height: 60px;
  border: 3px solid #10b981;
  border-radius: 50%;
  pointer-events: none;
  z-index: 99997;
  animation: ${rippleEffect} 0.55s ease-out forwards;
`;

export const SpatialOverlay = styled.div<{ $passThroughOpacity: number }>`
  position: fixed;
  inset: 0;
  z-index: 99990;
  background: ${({ $passThroughOpacity }) =>
    `rgba(10, 14, 26, ${1 - $passThroughOpacity})`};
  backdrop-filter: blur(${({ $passThroughOpacity }) => Math.max(0, 16 * (1 - $passThroughOpacity))}px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 20px;
  box-sizing: border-box;
  overflow: hidden;
  user-select: none;
  transition: background 0.3s ease;

  .hud-topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    max-width: 1280px;
    background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 90%, #000);
    border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.primary} 35%, transparent);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4), 0 0 20px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 15%, transparent);
    border-radius: 14px;
    padding: 12px 20px;
    backdrop-filter: blur(14px);
    z-index: 99995;

    .hud-title {
      display: flex;
      align-items: center;
      gap: 12px;

      h2 {
        font-size: 1.1rem;
        font-weight: 700;
        color: #ffffff;
        display: flex;
        align-items: center;
        gap: 8px;

        svg {
          color: #00f2fe;
        }
      }

      .status-pill {
        font-size: 0.72rem;
        font-weight: 700;
        padding: 3px 10px;
        border-radius: 20px;
        display: inline-flex;
        align-items: center;
        gap: 6px;

        &.active {
          background: rgba(16, 185, 129, 0.15);
          color: #10b981;
          border: 1px solid rgba(16, 185, 129, 0.3);
        }

        &.pinching {
          background: rgba(0, 242, 254, 0.2);
          color: #00f2fe;
          border: 1px solid rgba(0, 242, 254, 0.5);
        }

        &.waiting {
          background: rgba(245, 158, 11, 0.15);
          color: #f59e0b;
          border: 1px solid rgba(245, 158, 11, 0.3);
        }
      }
    }

    .hud-controls {
      display: flex;
      align-items: center;
      gap: 16px;

      .control-slider {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 0.76rem;
        font-weight: 600;
        color: #e2e8f0;

        input[type='range'] {
          accent-color: #00f2fe;
          cursor: pointer;
          width: 90px;
        }
      }

      button {
        background: color-mix(in srgb, ${({ theme }) => theme.colors.primary} 20%, transparent);
        color: #ffffff;
        border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.primary} 40%, transparent);
        padding: 8px 14px;
        border-radius: 8px;
        font-size: 0.82rem;
        font-weight: 600;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        transition: all 0.2s ease;

        &:hover {
          background: ${({ theme }) => theme.colors.primary};
          transform: translateY(-1px);
        }

        &.close-btn {
          background: rgba(239, 68, 68, 0.15);
          border-color: rgba(239, 68, 68, 0.3);
          color: #ef4444;

          &:hover {
            background: #ef4444;
            color: #ffffff;
          }
        }
      }
    }
  }
`;

export const ViewportCanvasBox = styled.div`
  position: relative;
  width: 100%;
  max-width: 1280px;
  height: calc(100vh - 140px);
  margin: 12px 0;
  background: #050811;
  border: 1px solid color-mix(in srgb, #00f2fe 30%, transparent);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.6), 0 0 30px color-mix(in srgb, #00f2fe 15%, transparent);
  border-radius: 16px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: perspective(1200px) rotateX(0.8deg);

  video {
    display: none;
  }

  /* Canvas do Screen Share do Mac ao vivo */
  canvas.screen-canvas {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }

  /* Canvas de Overlay 3D das Mãos e Esqueleto */
  canvas.hand-overlay-canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
    transform: scaleX(-1); /* Espelhar esqueleto da câmera */
  }

  .no-stream-placeholder {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16px;
    color: #94a3b8;
    text-align: center;
    padding: 40px;

    h3 {
      font-size: 1.25rem;
      color: #ffffff;
      font-weight: 700;
    }

    p {
      font-size: 0.88rem;
      max-width: 480px;
      line-height: 1.5;
    }

    button {
      background: #00f2fe;
      color: #050811;
      border: none;
      padding: 12px 24px;
      border-radius: 10px;
      font-size: 0.95rem;
      font-weight: 700;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      box-shadow: 0 4px 20px rgba(0, 242, 254, 0.4);
      transition: all 0.2s ease;

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 6px 28px rgba(0, 242, 254, 0.6);
      }
    }
  }
`;

export const SpatialPointer = styled.div<{ $x: number; $y: number; $isPinching?: boolean }>`
  position: absolute;
  left: ${({ $x }) => $x}px;
  top: ${({ $y }) => $y}px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: ${({ $isPinching }) => ($isPinching ? '#10B981' : '#00f2fe')};
  pointer-events: none;
  z-index: 99998;
  animation: ${pulseCursor} 1.8s infinite ease-in-out;
  transition: background 0.15s ease;

  &:after {
    content: '';
    position: absolute;
    top: 50%;
    left: 50%;
    width: 44px;
    height: 44px;
    border: 2px solid ${({ $isPinching }) => ($isPinching ? '#10B981' : 'rgba(0, 242, 254, 0.5)')};
    border-radius: 50%;
    transform: translate(-50%, -50%);
  }
`;

const slideBanner = keyframes`
  0% { transform: translate(-50%, -20px); opacity: 0; }
  20% { transform: translate(-50%, 0); opacity: 1; }
  80% { transform: translate(-50%, 0); opacity: 1; }
  100% { transform: translate(-50%, -20px); opacity: 0; }
`;

export const GestureFeedbackBanner = styled.div`
  position: absolute;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: color-mix(in srgb, #00f2fe 20%, rgba(10, 14, 26, 0.95));
  border: 1px solid #00f2fe;
  box-shadow: 0 0 25px rgba(0, 242, 254, 0.4);
  color: #ffffff;
  padding: 8px 18px;
  border-radius: 30px;
  font-size: 0.88rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 99999;
  pointer-events: none;
  animation: ${slideBanner} 1.4s cubic-bezier(0.16, 1, 0.3, 1) forwards;
`;
