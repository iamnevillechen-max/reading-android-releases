/** Free quota exhaustion is service availability, never evidence of a bad source. */
export function dailyReadAvailability(error: unknown, now = Date.now()): {
  code: string; message: string; resetAt: number; retryAfterSeconds: number;
} | undefined {
  let current = error;
  for (let depth = 0; depth < 3 && current && typeof current === 'object'; depth++) {
    const value = current as {message?:unknown;cause?:unknown};
    if (typeof value.message === 'string' && /^D1_ERROR: Your account has exceeded D1's free tier daily row read limit\./.test(value.message)) {
      const resetAt = (Math.floor(now / 86_400_000) + 1) * 86_400_000;
      return {code:'CLOUD_DAILY_READ_LIMIT',message:'雲端今日免費額度已用完，將於香港時間上午 8 時重置；已保存的正文仍可離線閱讀。',
        resetAt,retryAfterSeconds:Math.max(1,Math.ceil((resetAt-now)/1000))};
    }
    current = value.cause;
  }
  return undefined;
}
