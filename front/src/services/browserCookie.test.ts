import { afterEach, describe, expect, it } from 'vitest';
import { getBrowserCookie, removeBrowserCookie, setBrowserCookie } from './browserCookie';

const COOKIE_NAME = 'avento-test-preference';

afterEach(() => removeBrowserCookie(COOKIE_NAME));

describe('browserCookie', () => {
  it('round-trips a browser preference', () => {
    setBrowserCookie(COOKIE_NAME, 'modelo com espaço');

    expect(getBrowserCookie(COOKIE_NAME)).toBe('modelo com espaço');
  });

  it('returns null when a preference is absent', () => {
    expect(getBrowserCookie(COOKIE_NAME)).toBeNull();
  });

  it('removes a browser preference', () => {
    setBrowserCookie(COOKIE_NAME, 'enabled');
    removeBrowserCookie(COOKIE_NAME);

    expect(getBrowserCookie(COOKIE_NAME)).toBeNull();
  });
});
