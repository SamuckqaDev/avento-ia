import styled, { keyframes } from 'styled-components';

const pulseGlow = keyframes`
  0% { transform: scale(1); box-shadow: 0 0 10px #00f2fe, 0 0 20px #00f2fe; }
  50% { transform: scale(1.25); box-shadow: 0 0 20px #00f2fe, 0 0 35px #00c6ff; }
  100% { transform: scale(1); box-shadow: 0 0 10px #00f2fe, 0 0 20px #00f2fe; }
`;

const ripple = keyframes`
  0% { transform: translate(-50%, -50%) scale(0.2); opacity: 1; }
  100% { transform: translate(-50%, -50%) scale(2.5); opacity: 0; }
`;

export const FloatingWidget = styled.div`
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 99999;
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 92%, #000);
  border: 1px solid color-mix(in srgb, ${({ theme }) => theme.colors.primary} 40%, transparent);
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.4), 0 0 15px color-mix(in srgb, ${({ theme }) => theme.colors.primary} 20%, transparent);
  border-radius: 14px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 220px;
  backdrop-filter: blur(12px);
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);

  .widget-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 0.78rem;
    font-weight: 700;
    color: ${({ theme }) => theme.colors.text};

    .status-tag {
      font-size: 0.68rem;
      padding: 2px 7px;
      border-radius: 10px;
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      gap: 4px;

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

    button {
      background: transparent;
      border: none;
      color: ${({ theme }) => theme.colors.textMuted};
      cursor: pointer;
      padding: 2px;
      border-radius: 4px;

      &:hover {
        color: ${({ theme }) => theme.colors.text};
      }
    }
  }

  .canvas-wrapper {
    position: relative;
    width: 100%;
    height: 140px;
    background: #000;
    border-radius: 10px;
    overflow: hidden;
    border: 1px solid ${({ theme }) => theme.colors.border};

    video {
      display: none;
    }

    canvas {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transform: scaleX(-1); /* Espelhar imagem da webcam */
    }
  }

  .gesture-hint {
    font-size: 0.72rem;
    color: ${({ theme }) => theme.colors.textMuted};
    line-height: 1.3;
    text-align: center;

    strong {
      color: #00f2fe;
    }
  }
`;

export const HolographicCursor = styled.div<{ $x: number; $y: number; $isPinching?: boolean }>`
  position: fixed;
  left: ${({ $x }) => $x}px;
  top: ${({ $y }) => $y}px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: ${({ $isPinching }) => ($isPinching ? '#10B981' : '#00f2fe')};
  box-shadow: 0 0 15px ${({ $isPinching }) => ($isPinching ? '#10B981' : '#00f2fe')}, 0 0 30px ${({ $isPinching }) => ($isPinching ? '#10B981' : '#00c6ff')};
  pointer-events: none;
  z-index: 999999;
  transform: translate(-50%, -50%);
  transition: background 0.15s ease, box-shadow 0.15s ease;
  animation: ${pulseGlow} 2s infinite ease-in-out;

  &:before {
    content: '';
    position: absolute;
    top: 50%;
    left: 50%;
    width: 38px;
    height: 38px;
    border: 2px solid ${({ $isPinching }) => ($isPinching ? '#10B981' : 'rgba(0, 242, 254, 0.6)')};
    border-radius: 50%;
    transform: translate(-50%, -50%);
  }
`;

export const ClickRipple = styled.div<{ $x: number; $y: number }>`
  position: fixed;
  left: ${({ $x }) => $x}px;
  top: ${({ $y }) => $y}px;
  width: 50px;
  height: 50px;
  border: 3px solid #10b981;
  border-radius: 50%;
  pointer-events: none;
  z-index: 999998;
  animation: ${ripple} 0.5s ease-out forwards;
`;
