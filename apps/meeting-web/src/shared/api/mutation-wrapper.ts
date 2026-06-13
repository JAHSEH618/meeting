import { generateStableIdempotencyKey, clearIdempotencyCache } from '@shared/utils/idempotency';

/**
 * Wrap a mutation function with automatic idempotency key generation and error handling.
 *
 * @param action - Action name (e.g., 'create-meeting', 'update-speaker')
 * @param mutateFn - Original mutation function (body, idempotencyKey) => Promise<T>
 * @returns Wrapped function (body, userId, context?) => Promise<T>
 */
export function wrapMutation<TBody, TResult>(
  action: string,
  mutateFn: (body: TBody, idempotencyKey: string) => Promise<TResult>
): (body: TBody, userId: string, context?: string) => Promise<TResult> {
  return async (body: TBody, userId: string, context?: string): Promise<TResult> => {
    const idempotencyKey = generateStableIdempotencyKey(action, userId, context);

    try {
      const result = await mutateFn(body, idempotencyKey);
      // Clear cache on success (allow new action)
      clearIdempotencyCache();
      return result;
    } catch (error) {
      // Re-throw with normalized structure
      if (error && typeof error === 'object' && 'code' in error) {
        throw error; // Already normalized (ApiClientError)
      }
      throw new Error(String(error));
    }
  };
}

/**
 * Clear mutation cache.
 * Alias for clearIdempotencyCache for semantic clarity.
 */
export function clearMutationCache(): void {
  clearIdempotencyCache();
}
