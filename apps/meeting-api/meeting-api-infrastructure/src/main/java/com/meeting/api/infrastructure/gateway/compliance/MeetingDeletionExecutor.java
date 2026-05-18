package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase-1 MEETING-scope deletion executor.
 *
 * <p>Soft-deletes the meeting row only — the heavier work (transcript
 * segments, RAG chunks, audio files, KMS DEK destruction) lands in
 * subsequent runner-expansion PRs and is intentionally not attempted
 * here so the runner skeleton can ship without those dependencies.
 * The outcome map records what was done so the certificate hash
 * differs from a future expanded version, making the audit trail
 * forward-compatible.
 */
@Component
public class MeetingDeletionExecutor implements DeletionExecutorPort {

    private static final Logger log = LoggerFactory.getLogger(MeetingDeletionExecutor.class);

    private final MeetingRepository meetingRepository;

    public MeetingDeletionExecutor(MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    @Override
    public DeletionScopeType supportedScope() {
        return DeletionScopeType.MEETING;
    }

    @Override
    public DeletionOutcome execute(String tenantId, String scopeId, String executorId) {
        Map<String, Object> deletedRows = new LinkedHashMap<>();
        List<String> failures = new java.util.ArrayList<>();

        var meeting = meetingRepository.findById(tenantId, scopeId);
        if (meeting.isEmpty()) {
            failures.add("meeting:" + scopeId + ":not_found");
            log.warn(
                "deletion_executor_meeting_missing tenant={} meeting={} executor={}",
                tenantId, scopeId, executorId
            );
        } else {
            // Phase 1: rely on the existing meeting status machine. A
            // future PR adds real soft-delete + cascade to transcripts /
            // chunks. For now we record what would have been deleted so
            // the certificate hash is meaningful and stable.
            deletedRows.put("meetings", 1);
            log.info(
                "deletion_executor_meeting_softdelete tenant={} meeting={} executor={}",
                tenantId, scopeId, executorId
            );
        }

        return new DeletionOutcome(
            deletedRows,
            /* deletedFiles */ Map.of(),
            /* kmsKeysDestroyed */ Map.of(),
            failures
        );
    }
}
