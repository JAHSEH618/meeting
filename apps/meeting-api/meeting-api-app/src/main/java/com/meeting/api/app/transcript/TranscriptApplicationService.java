package com.meeting.api.app.transcript;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.transcript.TranscriptDTO;
import com.meeting.api.client.transcript.TranscriptFacade;
import com.meeting.api.client.transcript.TranscriptSegmentDTO;
import com.meeting.api.client.transcript.UpdateSegmentCommand;
import com.meeting.api.client.transcript.UpdateSegmentResult;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TranscriptApplicationService implements TranscriptFacade {
    private static final Logger log = LoggerFactory.getLogger(TranscriptApplicationService.class);

    private final MeetingRepository meetingRepository;
    private final TranscriptRepository transcriptRepository;
    private final MinutesRepository minutesRepository;
    private final ActionItemRepository actionItemRepository;
    private final DecisionRepository decisionRepository;
    private final RiskRepository riskRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public TranscriptApplicationService(
        MeetingRepository meetingRepository,
        TranscriptRepository transcriptRepository,
        MinutesRepository minutesRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(
            meetingRepository,
            transcriptRepository,
            minutesRepository,
            actionItemRepository,
            decisionRepository,
            riskRepository,
            knowledgeChunkRepository,
            tenantScopedTransaction,
            Clock.systemUTC()
        );
    }
    public TranscriptApplicationService(
        MeetingRepository meetingRepository,
        TranscriptRepository transcriptRepository,
        MinutesRepository minutesRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.transcriptRepository = transcriptRepository;
        this.minutesRepository = minutesRepository;
        this.actionItemRepository = actionItemRepository;
        this.decisionRepository = decisionRepository;
        this.riskRepository = riskRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public Optional<TranscriptDTO> get(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () ->
            meetingRepository.findById(tenantId, meetingId)
                .map(meeting -> {
                    int version = meeting.transcriptVersion();
                    var segments = transcriptRepository.findByMeeting(tenantId, meetingId, version).stream()
                        .sorted(Comparator.comparingInt(TranscriptRepository.TranscriptSegmentRecord::segmentIndex))
                        .map(segment -> new TranscriptSegmentDTO(
                            segment.segmentId(),
                            segment.startMs(),
                            segment.endMs(),
                            segment.speakerLabel(),
                            segment.speakerDisplayName(),
                            segment.originalText(),
                            segment.editedText(),
                            segment.currentText(),
                            segment.asrConfidence(),
                            segment.diarizationConfidence(),
                            segment.timestampPrecision()
                        ))
                        .toList();
                    return new TranscriptDTO(meetingId, version, "ACTIVE", segments);
                }));
    }

    @Override
    public UpdateSegmentResult updateSegment(UpdateSegmentCommand command) {
        if (command.editedText() == null) {
            throw new IllegalArgumentException("editedText must not be null");
        }
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            int currentVersion = transcriptRepository.currentTranscriptVersion(command.tenantId(), command.meetingId());
            if (command.expectedTranscriptVersion() != currentVersion) {
                throw new TranscriptVersionConflictException(currentVersion, command.expectedTranscriptVersion());
            }
            var segment = transcriptRepository.findSegment(command.tenantId(), command.meetingId(), command.segmentId(), currentVersion)
                .orElseThrow(() -> new IllegalArgumentException("segment not found: " + command.segmentId()));

            OffsetDateTime now = OffsetDateTime.now(clock);
            transcriptRepository.applySegmentEdit(
                command.tenantId(),
                command.meetingId(),
                command.segmentId(),
                currentVersion,
                command.editedText(),
                command.requestedBy(),
                command.editReason(),
                now
            );
            propagateStale(command.tenantId(), command.meetingId());
            log.info("transcript_segment_edited tenant={} meeting={} segment={} version={}",
                command.tenantId(), command.meetingId(), command.segmentId(), currentVersion);
            return new UpdateSegmentResult(command.segmentId(), currentVersion, "EDITED", true);
        });
    }

    private void propagateStale(String tenantId, String meetingId) {
        minutesRepository.markStale(tenantId, meetingId);
        actionItemRepository.markStaleForMeeting(tenantId, meetingId);
        decisionRepository.markStaleForMeeting(tenantId, meetingId);
        riskRepository.markStaleForMeeting(tenantId, meetingId);
        knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);
    }
    public static final class TranscriptVersionConflictException extends RuntimeException {
        private final int actualVersion;
        private final int expectedVersion;
        public TranscriptVersionConflictException(int actualVersion, int expectedVersion) {
            super("transcript version mismatch: expected=" + expectedVersion + " actual=" + actualVersion);
            this.actualVersion = actualVersion;
            this.expectedVersion = expectedVersion;
        }
        public int actualVersion() { return actualVersion; }
        public int expectedVersion() { return expectedVersion; }
    }
}
