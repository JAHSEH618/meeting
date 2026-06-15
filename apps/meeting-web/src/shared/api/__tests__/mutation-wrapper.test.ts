import { describe, it, expect, vi, beforeEach } from 'vitest';
import { wrapMutation, clearMutationCache } from '../mutation-wrapper';

describe('wrapMutation', () => {
  beforeEach(() => {
    clearMutationCache();
    vi.clearAllMocks();
  });

  it('generates and passes idempotency key to mutation', async () => {
    const mockFn = vi.fn().mockResolvedValue({ success: true });
    const wrapped = wrapMutation('test-action', mockFn);

    await wrapped({ param: 'value' }, 'user1');

    expect(mockFn).toHaveBeenCalledWith(
      { param: 'value' },
      expect.stringMatching(/^test-action_/)
    );
  });

  it('reuses same key on retry', async () => {
    let callCount = 0;
    const mockFn = vi.fn().mockImplementation(async () => {
      callCount++;
      if (callCount === 1) throw new Error('Network error');
      return { success: true };
    });
    const wrapped = wrapMutation('test-action', mockFn);

    // First call fails
    await expect(wrapped({ param: 'value' }, 'user1')).rejects.toThrow();
    const firstKey = mockFn.mock.calls[0][1];

    // Retry succeeds with SAME key
    await wrapped({ param: 'value' }, 'user1');
    const secondKey = mockFn.mock.calls[1][1];

    expect(firstKey).toBe(secondKey);
  });

  it('uses context for key stability', async () => {
    const mockFn = vi.fn().mockResolvedValue({ success: true });
    const wrapped = wrapMutation('update-meeting', mockFn);

    await wrapped({ meetingId: 'm1', title: 'New' }, 'user1', 'm1');
    await wrapped({ meetingId: 'm2', title: 'New' }, 'user1', 'm2');

    const key1 = mockFn.mock.calls[0][1];
    const key2 = mockFn.mock.calls[1][1];

    expect(key1).not.toBe(key2); // Different contexts
  });

  it('throws user-friendly error on failure', async () => {
    const mockFn = vi.fn().mockRejectedValue({
      code: 'MEETING_NOT_FOUND',
      message: 'Meeting not found',
    });
    const wrapped = wrapMutation('delete-meeting', mockFn);

    await expect(wrapped({ meetingId: 'm1' }, 'user1')).rejects.toMatchObject({
      message: 'Meeting not found',
      code: 'MEETING_NOT_FOUND',
    });
  });
});
