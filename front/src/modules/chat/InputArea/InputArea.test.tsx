import { render, screen } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { expect, it } from 'vitest';
import { lightTheme } from '../../../styles/theme';
import { InputArea } from './index';

function renderInputArea(isAudioPlaying: boolean) {
  return render(
    <ThemeProvider theme={lightTheme}>
      <InputArea
        inputValue=""
        setInputValue={() => {}}
        handleSend={() => {}}
        onStop={() => {}}
        isGenerating={false}
        isRecording={false}
        isRealtimeVoiceActive={false}
        isRealtimeListening={false}
        realtimeTranscript=""
        audioLevel={0}
        isAudioPlaying={isAudioPlaying}
        speechRecognitionSupported
        imageAttachments={[]}
        documentAttachments={[]}
        isAttachingDocuments={false}
        toggleRecording={() => {}}
        toggleRealtimeVoice={() => {}}
        onAttachImages={() => {}}
        onAttachDocuments={() => {}}
        onRemoveImageAttachment={() => {}}
        onRemoveDocumentAttachment={() => {}}
        messageQueue={[]}
        onRemoveFromQueue={() => {}}
        skills={[]}
        agentMode={false}
        onToggleAgentMode={() => {}}
      />
    </ThemeProvider>,
  );
}

it('exibe o mascote animado somente durante a reprodução de voz', () => {
  const { rerender } = renderInputArea(false);

  expect(screen.queryByRole('status')).toBeNull();

  rerender(
    <ThemeProvider theme={lightTheme}>
      <InputArea
        inputValue=""
        setInputValue={() => {}}
        handleSend={() => {}}
        onStop={() => {}}
        isGenerating={false}
        isRecording={false}
        isRealtimeVoiceActive={false}
        isRealtimeListening={false}
        realtimeTranscript=""
        audioLevel={0}
        isAudioPlaying
        speechRecognitionSupported
        imageAttachments={[]}
        documentAttachments={[]}
        isAttachingDocuments={false}
        toggleRecording={() => {}}
        toggleRealtimeVoice={() => {}}
        onAttachImages={() => {}}
        onAttachDocuments={() => {}}
        onRemoveImageAttachment={() => {}}
        onRemoveDocumentAttachment={() => {}}
        messageQueue={[]}
        onRemoveFromQueue={() => {}}
        skills={[]}
        agentMode={false}
        onToggleAgentMode={() => {}}
      />
    </ThemeProvider>,
  );

  expect(screen.getByRole('status').textContent).toContain('O Avento está falando');
  expect(screen.getByText('Reproduzindo a resposta em voz')).toBeTruthy();
});
