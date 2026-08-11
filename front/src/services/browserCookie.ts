const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365;
const MAX_COOKIE_VALUE_CHARS = 3_000;

/**
 * Browser preferences are readable by the UI, so they deliberately cannot be HttpOnly. They do
 * not contain credentials or other secrets; session cookies remain owned by the backend.
 */
export function getBrowserCookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`;
  const cookie = document.cookie.split('; ').find(entry => entry.startsWith(prefix));
  if (!cookie) return null;

  try {
    return decodeURIComponent(cookie.slice(prefix.length));
  } catch {
    return null;
  }
}

export function setBrowserCookie(name: string, value: string, maxAge = ONE_YEAR_SECONDS): void {
  if (value.length > MAX_COOKIE_VALUE_CHARS) {
    throw new RangeError('Browser preference is too large to store in a cookie.');
  }

  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `${encodeURIComponent(name)}=${encodeURIComponent(value)}; Path=/; SameSite=Lax; Max-Age=${maxAge}${secure}`;
}

export function removeBrowserCookie(name: string): void {
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `${encodeURIComponent(name)}=; Path=/; SameSite=Lax; Max-Age=0${secure}`;
}
