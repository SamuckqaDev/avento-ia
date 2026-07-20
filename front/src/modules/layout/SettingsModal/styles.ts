import styled, { keyframes } from 'styled-components';

const fadeIn = keyframes`
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: scale(1); }
`;

export const ModalBackdrop = styled.div`
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  animation: ${fadeIn} 0.2s cubic-bezier(0.16, 1, 0.3, 1);
`;

export const ModalContainer = styled.div`
  background: rgba(16, 42, 38, 0.85); /* Superfície translúcida */
  backdrop-filter: blur(24px);
  border: 1px solid rgba(102, 230, 200, 0.15);
  border-radius: 16px;
  width: 90%;
  max-width: 540px;
  min-height: 400px;
  padding: 28px;
  color: #F2FFFB;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.05);
  display: flex;
  flex-direction: column;
  gap: 24px;
`;

export const Header = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;

  h2 {
    margin: 0;
    font-size: 1.35rem;
    font-weight: 600;
    letter-spacing: -0.02em;
    background: linear-gradient(180deg, #FFFFFF 0%, #B8D4CC 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }

  button {
    background: transparent;
    border: 1px solid transparent;
    color: #9FB8B1;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    padding: 6px;
    transition: all 0.2s ease;
    
    &:hover {
      background: rgba(255, 255, 255, 0.08);
      color: #F2FFFB;
      border-color: rgba(255, 255, 255, 0.1);
    }
  }
`;

const slideUp = keyframes`
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
`;

export const Body = styled.div`
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 20px;
  animation: ${slideUp} 0.3s cubic-bezier(0.16, 1, 0.3, 1);

  .profile-header {
    display: flex;
    align-items: center;
    gap: 20px;
    padding: 16px;
    background: rgba(33, 67, 61, 0.3);
    border-radius: 12px;
    border: 1px solid rgba(102, 230, 200, 0.1);
  }

  .profile-avatar {
    width: 64px;
    height: 64px;
    flex-shrink: 0;
    border-radius: 16px;
    background: linear-gradient(135deg, #66E6C8 0%, #104E45 100%);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 1.5rem;
    font-weight: 700;
    color: #102A26;
    box-shadow: 0 4px 12px rgba(102, 230, 200, 0.2);
    border: 2px solid rgba(255, 255, 255, 0.1);
  }

  .profile-info {
    display: flex;
    flex-direction: column;
    gap: 4px;
    
    h3 {
      margin: 0;
      font-size: 1.15rem;
      font-weight: 600;
    }
    
    p {
      margin: 0;
      font-size: 0.9rem;
      color: #9FB8B1;
    }

    .profile-badge {
      display: inline-block;
      margin-top: 4px;
      padding: 2px 8px;
      background: rgba(102, 230, 200, 0.15);
      color: #66E6C8;
      border-radius: 4px;
      font-size: 0.75rem;
      font-weight: 600;
      letter-spacing: 0.05em;
      width: fit-content;
    }
  }
`;

export const SettingRow = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;

  label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    
    strong {
      font-weight: 500;
    }
    
    span {
      font-size: 0.85rem;
      color: #9FB8B1;
    }
  }
`;

export const ToggleSwitch = styled.button<{ $active: boolean }>`
  position: relative;
  width: 44px;
  height: 24px;
  border-radius: 12px;
  background: ${props => props.$active ? '#66E6C8' : 'rgba(33, 67, 61, 0.6)'};
  border: 1px solid ${props => props.$active ? 'transparent' : 'rgba(255, 255, 255, 0.1)'};
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: ${props => props.$active ? '0 0 12px rgba(102, 230, 200, 0.3)' : 'none'};

  &::after {
    content: '';
    position: absolute;
    top: 1px;
    left: ${props => props.$active ? '21px' : '1px'};
    width: 20px;
    height: 20px;
    background: ${props => props.$active ? '#102A26' : '#9FB8B1'};
    border-radius: 50%;
    transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
  }
`;

export const Footer = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  align-items: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
`;

export const DestructiveButton = styled.button`
  background: rgba(240, 98, 140, 0.1);
  color: #F0628C;
  border: 1px solid rgba(240, 98, 140, 0.2);
  border-radius: 8px;
  padding: 10px 20px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(240, 98, 140, 0.2);
    border-color: rgba(240, 98, 140, 0.4);
  }
`;

export const SaveButton = styled.button`
  background: linear-gradient(135deg, #66E6C8 0%, #30A48A 100%);
  color: #102A26;
  border: none;
  border-radius: 8px;
  padding: 10px 24px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 4px 12px rgba(102, 230, 200, 0.2);

  &:hover:not(:disabled) {
    box-shadow: 0 6px 16px rgba(102, 230, 200, 0.3);
    transform: translateY(-1px);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    background: #21433D;
    color: #9FB8B1;
    box-shadow: none;
  }
`;

export const Tabs = styled.div`
  display: flex;
  gap: 8px;
  padding: 4px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  margin-bottom: 24px;
  border: 1px solid rgba(255, 255, 255, 0.05);
`;

export const TabButton = styled.button<{ $active?: boolean }>`
  flex: 1;
  background: ${({ $active }) => $active ? 'rgba(255, 255, 255, 0.1)' : 'transparent'};
  border: none;
  border-radius: 6px;
  color: ${({ $active }) => $active ? '#F2FFFB' : '#9FB8B1'};
  padding: 10px;
  font-size: 0.95rem;
  font-weight: ${({ $active }) => $active ? '600' : '500'};
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: ${({ $active }) => $active ? '0 2px 8px rgba(0,0,0,0.2)' : 'none'};

  &:hover {
    color: #F2FFFB;
    background: ${({ $active }) => $active ? 'rgba(255, 255, 255, 0.15)' : 'rgba(255, 255, 255, 0.05)'};
  }
`;

export const UsageCard = styled.div`
  background: rgba(33, 67, 61, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    border-color: rgba(102, 230, 200, 0.2);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
  }

  h3 {
    margin: 0;
    font-size: 1.1rem;
    color: #F2FFFB;
    display: flex;
    align-items: center;
    gap: 8px;
    
    &::before {
      content: '';
      display: inline-block;
      width: 4px;
      height: 14px;
      background: #66E6C8;
      border-radius: 2px;
    }
  }

  p {
    margin: 0;
    font-size: 0.9rem;
    color: #9FB8B1;
  }
`;

export const BarChart = styled.svg`
  width: 100%;
  height: 120px;
  margin-top: 8px;
  overflow: visible;
  
  rect {
    fill: url(#barGradient);
    rx: 4; /* Rounded corners */
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    
    &:hover {
      fill: #66E6C8;
      filter: drop-shadow(0 0 8px rgba(102, 230, 200, 0.4));
      transform: translateY(-2px);
    }
  }

  text {
    fill: #9FB8B1;
    font-size: 11px;
    font-weight: 500;
  }
`;

export const UsageTable = styled.table`
  width: 100%;
  border-collapse: collapse;
  margin-top: 8px;
  font-size: 0.9rem;

  th {
    text-align: left;
    color: #9FB8B1;
    font-weight: 500;
    padding: 12px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }

  td {
    color: #F2FFFB;
    padding: 12px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.03);
  }
  
  tr:last-child td {
    border-bottom: none;
  }

  tr:hover td {
    background: rgba(255, 255, 255, 0.02);
  }
`;
