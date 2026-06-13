let counter = 0;

// Cache for stable keys (action + context -> key)
const keyCache = new Map<string, string>();

export function generateIdempotencyKey(prefix: string): string {
  counter += 1;
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${timestamp}_${random}_${counter}`;
}

/**
 * Generate stable idempotency key that stays the same for a given action + context.
 * Use this for mutations that should reuse the same key on retry.
 *
 * @param action - Action name (e.g., 'create-meeting', 'update-speaker')
 * @param userId - Current user ID (for multi-user isolation)
 * @param context - Optional context (e.g., resource ID, form state hash)
 * @returns Stable idempotency key
 */
export function generateStableIdempotencyKey(
  action: string,
  userId: string,
  context?: string
): string {
  const cacheKey = `${action}:${userId}:${context || 'default'}`;

  if (keyCache.has(cacheKey)) {
    return keyCache.get(cacheKey)!;
  }

  const key = generateIdempotencyKey(action);
  keyCache.set(cacheKey, key);
  return key;
}

/**
 * Clear idempotency key cache.
 * Call this on successful mutation completion or when user navigates away.
 */
export function clearIdempotencyCache(): void {
  keyCache.clear();
}
