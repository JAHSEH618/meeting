package com.meeting.api.domain.export;

import java.util.List;
import java.util.Optional;

/**
 * Read-only cross-aggregate port that loads the version-locked meeting
 * snapshot used by {@link ExportGateway}. Implementations live in
 * infrastructure (JDBC) and <em>must</em>:
 *
 * <ul>
 *   <li>Filter by the requested {@code transcriptVersion} (and
 *       {@code minutesVersion} when not null) — if the version doesn't
 *       exist or its {@code stale_status} is not {@code ACTIVE}, return
 *       {@link Optional#empty()} so the application layer can throw
 *       {@code EXPORT_CONTENT_STALE}.</li>
 *   <li>Apply RLS via the standard tenant context — no bypass.</li>
 *   <li>Skip evidence whose {@code stale_status} is not {@code ACTIVE}.</li>
 * </ul>
 */
public interface MeetingSnapshotPort {

    Optional<MeetingSnapshot> loadSnapshot(
        String tenantId,
        String meetingId,
        int transcriptVersion,
        Integer minutesVersion
    );

    /** Loaded snapshot — all collections are immutable views. */
    record MeetingSnapshot(
        String meetingId,
        String title,
        
        String language,
        Long durationSeconds,
        int transcriptVersion,
        Integer minutesVersion,
        List<TranscriptSegmentRow> segments,
        MinutesRow minutes,
        List<ActionItemRow> actionItems,
        List<DecisionRow> decisions,
        List<RiskRow> risks,
        List<MeetingSpeakerRow> speakers
    ) {

        public MeetingSnapshot {
            segments = segments == null ? List.of() : List.copyOf(segments);
            actionItems = actionItems == null ? List.of() : List.copyOf(actionItems);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            risks = risks == null ? List.of() : List.copyOf(risks);
            speakers = speakers == null ? List.of() : List.copyOf(speakers);
        }
    }

    record TranscriptSegmentRow(
        String segmentId,
        int segmentIndex,
        long startMs,
        long endMs,
        String speakerLabel,
        String speakerName,
        String text
    ) {}

    record MinutesRow(
        int minutesVersion,
        String title,
        String markdown
    ) {}

    record ActionItemRow(
        String id,
        String title,
        String description,
        String ownerName,
        String deadline,
        String priority,
        String status
    ) {}

    record DecisionRow(
        String id,
        String title,
        String description,
        String status
    ) {}

    record RiskRow(
        String id,
        String title,
        String description,
        String severity,
        String status
    ) {}

    record MeetingSpeakerRow(
        String speakerLabel,
        String displayName,
        String verificationStatus
    ) {}
}
