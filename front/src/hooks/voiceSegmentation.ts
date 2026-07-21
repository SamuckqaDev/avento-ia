export const REALTIME_UTTERANCE_SILENCE_MS = 1800;
export const REALTIME_RECORDER_RESTART_DELAY_MS = 80;

export function shouldFinalizeRealtimeUtterance(
  speechDetected: boolean,
  silenceStartedAt: number | null,
  now: number
): boolean {
  return speechDetected
    && silenceStartedAt !== null
    && now - silenceStartedAt >= REALTIME_UTTERANCE_SILENCE_MS;
}
