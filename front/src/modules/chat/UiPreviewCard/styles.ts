import styled from 'styled-components';

export const Card = styled.section`
  width: 100%;
  margin: 12px 0;
  overflow: hidden;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
`;

export const CardHeader = styled.header`
  min-height: 54px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px 8px 12px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  @media (max-width: 640px) {
    align-items: flex-start;
    flex-direction: column;
  }
`;

export const Title = styled.div`
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 9px;
  color: ${({ theme }) => theme.colors.text};

  & > svg {
    flex: 0 0 auto;
    color: ${({ theme }) => theme.colors.accent};
  }

  & > span {
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    font-size: 0.86rem;
  }

  small {
    color: ${({ theme }) => theme.colors.textMuted};
    font-size: 0.72rem;
  }
`;

export const Toolbar = styled.div`
  display: flex;
  align-items: center;
  gap: 5px;
`;

export const DeviceSelector = styled.div`
  height: 34px;
  display: inline-flex;
  align-items: center;
  padding: 2px;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 7px;
  background: ${({ theme }) => theme.colors.surface};
`;

export const DeviceButton = styled.button<{ $active: boolean }>`
  width: 30px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: ${({ $active, theme }) => ($active ? theme.colors.primary : 'transparent')};
  color: ${({ $active, theme }) => ($active ? theme.colors.white : theme.colors.textMuted)};
  cursor: pointer;

  &:hover {
    color: ${({ $active, theme }) => ($active ? theme.colors.white : theme.colors.accent)};
  }
`;

export const ActionButton = styled.button<{ $active?: boolean }>`
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 7px;
  background: ${({ $active, theme }) => ($active ? theme.colors.primary : theme.colors.surface)};
  color: ${({ $active, theme }) => ($active ? theme.colors.white : theme.colors.textMuted)};
  cursor: pointer;

  &:hover {
    border-color: ${({ theme }) => theme.colors.accent};
    color: ${({ $active, theme }) => ($active ? theme.colors.white : theme.colors.accent)};
  }
`;

export const PreviewViewport = styled.div<{ $compact?: boolean }>`
  width: 100%;
  min-height: ${({ $compact }) => ($compact ? '188px' : '260px')};
  height: ${({ $compact }) => ($compact ? '188px' : 'auto')};
  max-height: ${({ $compact }) => ($compact ? '188px' : 'min(68vh, 720px)')};
  display: flex;
  justify-content: center;
  overflow: ${({ $compact }) => ($compact ? 'hidden' : 'auto')};
  padding: ${({ $compact }) => ($compact ? '8px' : '12px')};
  background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 74%, #111 26%);
`;

export const PreviewThumbnail = styled.div`
  position: relative;
  cursor: pointer;

  & iframe {
    pointer-events: none;
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.colors.accent};
    outline-offset: -2px;
  }

  &:hover ${PreviewViewport} {
    background: color-mix(in srgb, ${({ theme }) => theme.colors.surface} 66%, ${({ theme }) => theme.colors.accent} 34%);
  }
`;

export const PreviewStage = styled.div<{ $width: number; $height: number; $scale: number }>`
  position: relative;
  flex: 0 0 auto;
  width: ${({ $width, $scale }) => `${$width * $scale}px`};
  height: ${({ $height, $scale }) => `${$height * $scale}px`};
  overflow: hidden;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.24);
`;

export const PreviewFrame = styled.iframe<{ $width: number; $height: number; $scale: number }>`
  position: absolute;
  inset: 0 auto auto 0;
  width: ${({ $width }) => `${$width}px`};
  height: ${({ $height }) => `${$height}px`};
  display: block;
  border: 0;
  transform: scale(${({ $scale }) => $scale});
  transform-origin: top left;
  background: #fff;
`;

export const ModalBackdrop = styled.div`
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  background: rgba(2, 8, 7, 0.78);
  backdrop-filter: blur(5px);
`;

export const Modal = styled.section`
  width: min(96vw, 1540px);
  height: min(94vh, 1040px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid ${({ theme }) => theme.colors.border};
  border-radius: 8px;
  background: ${({ theme }) => theme.colors.bg};
  box-shadow: 0 30px 90px rgba(0, 0, 0, 0.48);

  & > ${PreviewViewport} {
    flex: 1;
    max-height: none;
  }
`;

export const ModalHeader = styled.header`
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px 9px 14px;
  border-bottom: 1px solid ${({ theme }) => theme.colors.border};

  @media (max-width: 640px) {
    flex-wrap: wrap;
  }
`;
