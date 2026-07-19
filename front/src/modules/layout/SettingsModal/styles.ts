import styled from 'styled-components';

export const ModalBackdrop = styled.div`
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
`;

export const ModalContainer = styled.div`
  background: #102A26; /* Superfície escura */
  border: 1px solid #21433D;
  border-radius: 12px;
  width: 90%;
  max-width: 500px;
  padding: 24px;
  color: #F2FFFB;
  box-shadow: 0 10px 25px rgba(0,0,0,0.5);
  display: flex;
  flex-direction: column;
  gap: 20px;
`;

export const Header = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;

  h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
  }

  button {
    background: transparent;
    border: none;
    color: #9FB8B1;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    padding: 4px;
    
    &:hover {
      background: rgba(255, 255, 255, 0.05);
      color: #F2FFFB;
    }
  }
`;

export const Body = styled.div`
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  background: ${props => props.$active ? '#66E6C8' : '#21433D'};
  border: none;
  cursor: pointer;
  transition: background 0.2s ease;

  &::after {
    content: '';
    position: absolute;
    top: 2px;
    left: ${props => props.$active ? '22px' : '2px'};
    width: 20px;
    height: 20px;
    background: ${props => props.$active ? '#102A26' : '#9FB8B1'};
    border-radius: 50%;
    transition: left 0.2s ease, background 0.2s ease;
  }
`;

export const Footer = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #21433D;
`;

export const DestructiveButton = styled.button`
  background: transparent;
  color: #F0628C; /* Rosa Ação no tema escuro */
  border: 1px solid #F0628C;
  border-radius: 6px;
  padding: 8px 16px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(240, 98, 140, 0.1);
  }
`;

export const SaveButton = styled.button`
  background: #104E45;
  color: #F2FFFB;
  border: 1px solid #66E6C8;
  border-radius: 6px;
  padding: 8px 24px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: #66E6C8;
    color: #102A26;
  }
`;
