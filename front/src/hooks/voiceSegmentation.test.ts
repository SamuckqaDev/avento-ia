import { describe, expect, it } from 'vitest';
import {
  REALTIME_UTTERANCE_SILENCE_MS,
  shouldFinalizeRealtimeUtterance,
} from './voiceSegmentation';

describe('shouldFinalizeRealtimeUtterance', () => {
  it('keeps recording during a natural pause', () => {
    expect(shouldFinalizeRealtimeUtterance(true, 1_000, 2_200)).toBe(false);
  });

  it('finishes only after the complete silence window', () => {
    expect(shouldFinalizeRealtimeUtterance(true, 1_000, 1_000 + REALTIME_UTTERANCE_SILENCE_MS)).toBe(true);
  });

  it('does not finish before speech is detected', () => {
    expect(shouldFinalizeRealtimeUtterance(false, 1_000, 5_000)).toBe(false);
  });
});
