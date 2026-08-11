import { describe, expect, it } from 'vitest';
import { splitSpeechChunks } from './useAudioServices';

describe('splitSpeechChunks', () => {
  it('keeps short sentences together for a natural first audio chunk', () => {
    expect(splitSpeechChunks('Tudo pronto. Pode continuar.', 80)).toEqual([
      'Tudo pronto. Pode continuar.',
    ]);
  });

  it('prefers a clause pause before splitting a long sentence mechanically', () => {
    const text =
      'O índice terminou de atualizar, mas ainda estou verificando os arquivos modificados para não falar antes da hora.';

    expect(splitSpeechChunks(text, 70)).toEqual([
      'O índice terminou de atualizar,',
      'mas ainda estou verificando os arquivos modificados para não falar',
      'antes da hora.',
    ]);
  });
});
