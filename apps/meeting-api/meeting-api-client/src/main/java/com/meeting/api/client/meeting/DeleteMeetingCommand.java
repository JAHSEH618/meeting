package com.meeting.api.client.meeting;

/**
 * Command for {@code DELETE /api/meetings/{meetingId}}.
 *
 * <p>{@code expectedTranscriptVersion} is optional: when present the
 * application service validates the persisted transcript version matches
 * before performing the soft delete, returning {@code VERSION_CONFLICT}
 * otherwise. {@code reason} is propagated to the audit log; pass a stable
 * short label (e.g. {@code "user_request"}, {@code "policy_violation"}).
 */
public record DeleteMeetingCommand(
    String tenantId,
    String meetingId,
    String actorUserId,
    String requestId,
    String reason,
    Integer expectedTranscriptVersion,
    boolean legalHoldAcknowledged
) {
    public DeleteMeetingCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
    }
}
