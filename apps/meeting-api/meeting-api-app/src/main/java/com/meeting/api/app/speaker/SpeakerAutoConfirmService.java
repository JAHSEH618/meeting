package com.meeting.api.app.speaker;

import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditActorType;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.MeetingSpeakerRecord;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SpeakerAutoConfirmService {
    private static final Logger log = LoggerFactory.getLogger(SpeakerAutoConfirmService.class);
    public static final double AUTO_CONFIRM_THRESHOLD = 0.85;
    public static final String AUTO_CONFIRM_ACTOR = "auto-confirm@system";
    public static final String AUTO_CONFIRM_REASON = "auto_confirm";

    private final ProcessingTaskRepository taskRepository;
    private final MeetingSpeakerRepository meetingSpeakerRepository;
    private final MeetingSpeakerApplicationService meetingSpeakerApplicationService;
    private final AuditEventLogger auditEventLogger;

    public SpeakerAutoConfirmService(
        ProcessingTaskRepository taskRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        MeetingSpeakerApplicationService meetingSpeakerApplicationService,
        AuditEventLogger auditEventLogger
    ) {
        this.taskRepository = taskRepository;
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.meetingSpeakerApplicationService = meetingSpeakerApplicationService;
        this.auditEventLogger = auditEventLogger;
    }

    public void autoConfirmAboveThreshold(String tenantId, String taskId) {
        Optional<ProcessingTask> taskOpt = taskRepository.findById(tenantId, taskId);
        if (taskOpt.isEmpty() || taskOpt.get().meetingId() == null || taskOpt.get().meetingId().isBlank()) {
            log.debug("auto_confirm_skipped_no_meeting task={} tenant={}", taskId, tenantId);
            return;
        }
        String meetingId = taskOpt.get().meetingId();
        for (MeetingSpeakerRecord speaker : meetingSpeakerRepository.findByMeeting(tenantId, meetingId)) {
            autoConfirmOne(tenantId, taskId, meetingId, speaker);
        }
    }

    private void autoConfirmOne(String tenantId, String taskId, String meetingId, MeetingSpeakerRecord speaker) {
        try {
            if (!isAutoConfirmableStatus(speaker.verificationStatus())) {
                return;
            }
            if (speaker.autoMatchScore() == null || speaker.autoMatchScore() < AUTO_CONFIRM_THRESHOLD) {
                return;
            }
            if (speaker.candidatePersonIds() == null || speaker.candidatePersonIds().size() != 1) {
                return;
            }
            String personId = speaker.candidatePersonIds().get(0);
            String speakerProfileId = singleCandidateProfileId(speaker, personId);
            meetingSpeakerApplicationService.confirm(
                tenantId,
                meetingId,
                speaker.speakerLabel(),
                personId,
                speakerProfileId,
                AUTO_CONFIRM_ACTOR
            );
            logAudit(tenantId, taskId, meetingId, speaker, personId);
            log.info(
                "speaker_auto_confirmed tenant={} task={} meeting={} label={} person={} confidence={}",
                tenantId, taskId, meetingId, speaker.speakerLabel(), personId, speaker.autoMatchScore()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "speaker_auto_confirm_failed tenant={} task={} meeting={} label={} reason={}",
                tenantId, taskId, meetingId, speaker.speakerLabel(), ex.getMessage()
            );
        }
    }

    private void logAudit(
        String tenantId,
        String taskId,
        String meetingId,
        MeetingSpeakerRecord speaker,
        String personId
    ) {
        try {
            auditEventLogger.log(new AuditEventLogger.AuditEntry(
                tenantId,
                null,
                AuditActorType.SYSTEM,
                AuditAction.UPDATE,
                "MEETING_SPEAKER",
                meetingId + ":" + speaker.speakerLabel(),
                AuditResult.SUCCESS,
                Map.of(
                    "taskId", taskId,
                    "meetingId", meetingId,
                    "speakerLabel", speaker.speakerLabel(),
                    "personId", personId,
                    "confidence", speaker.autoMatchScore()
                ),
                AUTO_CONFIRM_REASON,
                null
            ));
        } catch (RuntimeException ex) {
            log.warn(
                "speaker_auto_confirm_audit_failed tenant={} task={} meeting={} label={} reason={}",
                tenantId, taskId, meetingId, speaker.speakerLabel(), ex.getMessage()
            );
        }
    }

    private static boolean isAutoConfirmableStatus(String status) {
        return "CANDIDATE".equals(status) || "UNCONFIRMED".equals(status);
    }

    private static String singleCandidateProfileId(MeetingSpeakerRecord speaker, String personId) {
        if (speaker.candidates() == null || speaker.candidates().size() != 1) {
            return null;
        }
        MeetingSpeakerRepository.SpeakerCandidate candidate = speaker.candidates().get(0);
        if (candidate == null || candidate.speakerProfileId() == null || candidate.speakerProfileId().isBlank()) {
            return null;
        }
        return personId.equals(candidate.personId()) ? candidate.speakerProfileId() : null;
    }
}
