import { afterEach, describe, expect, it } from 'vitest';
import { removeBrowserCookie, setBrowserCookie } from '../../services/browserCookie';
import { loadImagePreferences, loadSelectedModel, loadVoiceEnabled } from './index';

const MODEL_COOKIE = 'avento-selected-model';
const VOICE_COOKIE = 'avento-voice-enabled';
const IMAGE_PREFERENCES_COOKIE = 'avento-image-generation-options';

afterEach(() => {
  removeBrowserCookie(MODEL_COOKIE);
  removeBrowserCookie(VOICE_COOKIE);
  removeBrowserCookie(IMAGE_PREFERENCES_COOKIE);
});

describe('Home browser preferences', () => {
  it('restores the selected model from its cookie', () => {
    setBrowserCookie(MODEL_COOKIE, 'qwen3:8b');

    expect(loadSelectedModel()).toBe('qwen3:8b');
  });

  it('restores the voice preference from its cookie', () => {
    setBrowserCookie(VOICE_COOKIE, 'false');

    expect(loadVoiceEnabled()).toBe(false);
  });

  it('restores image preferences from their cookie', () => {
    setBrowserCookie(IMAGE_PREFERENCES_COOKIE, JSON.stringify({
      qualityPreset: 'quality',
      aspectRatio: 'portrait',
      lockSeed: true,
      seed: 123,
    }));

    expect(loadImagePreferences()).toMatchObject({
      qualityPreset: 'quality',
      aspectRatio: 'portrait',
      lockSeed: true,
      seed: 123,
    });
  });
});
