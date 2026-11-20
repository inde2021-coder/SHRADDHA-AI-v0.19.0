export const GEMINI_LIVE_MODEL = 'models/gemini-3.1-flash-live-preview';

export function buildEphemeralTokenRequest(now = new Date()) {
  return {
    uses: 1,
    expireTime: new Date(now.getTime() + 30 * 60 * 1000).toISOString(),
    newSessionExpireTime: new Date(now.getTime() + 60 * 1000).toISOString(),
    liveConnectConstraints: {
      model: GEMINI_LIVE_MODEL,
      config: { responseModalities: ['AUDIO'] }
    }
  };
}
