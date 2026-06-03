package com.meeting.api.app.task;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.task.CancelTaskCommand;
import com.meeting.api.client.task.CreateProcessingTaskCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.client.task.ProcessingTaskFacade;
import com.meeting.api.client.task.ResumeJavaPhaseCommand;
import com.meeting.api.client.task.RetryTaskCommand;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingDocumentRepository;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProcessingTaskApplicationService implements ProcessingTaskFacade {
    public static final String MEETING_FULL_PIPELINE = "MEETING_FULL_PIPELINE";
    public static final String SPEAKER_ENROLLMENT = "SPEAKER_ENROLLMENT";

    private static final List<ProcessingStep> MEETING_WORKER_STEPS = List.of(
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.ALIGNMENT,
        ProcessingStep.DIARIZATION,
        ProcessingStep.SPEAKER_EMBEDDING,
        ProcessingStep.SPEAKER_MATCHING,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING
    );

    private static final List<ProcessingStep> MVP0_STEPS = List.of(
        ProcessingStep.AUDIO_UPLOAD,
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.ALIGNMENT,
        ProcessingStep.DIARIZATION,
        ProcessingStep.SPEAKER_EMBEDDING,
        ProcessingStep.SPEAKER_MATCHING,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING,
        ProcessingStep.SUMMARY,
        ProcessingStep.EXTRACTION
    );

    private static final List<ProcessingStep> PHASE2_AUDIO_UPLOAD_STEPS = List.of(
        ProcessingStep.AUDIO_UPLOAD,
        ProcessingStep.AUDIO_PREPROCESS,
        ProcessingStep.ASR,
        ProcessingStep.ALIGNMENT,
        ProcessingStep.DIARIZATION,
        ProcessingStep.SPEAKER_EMBEDDING,
        ProcessingStep.SPEAKER_MATCHING,
        ProcessingStep.TRANSCRIPT_MERGE,
        ProcessingStep.RAG_INDEXING,
        ProcessingStep.SUMMARY,
        ProcessingStep.EXTRACTION
    );

    private static final List<ProcessingStep> SPEAKER_ENROLLMENT_STEPS = List.of(
        ProcessingStep.SPEAKER_EMBEDDING,
        ProcessingStep.SPEAKER_MATCHING
    );

    private final ProcessingTaskRepository taskRepository;
    private final MeetingRepository meetingRepository;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final MeetingGlossaryRepository glossaryRepository;
    private final MeetingDocumentRepository meetingDocumentRepository;
    private final JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator;

    @Autowired
    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(taskRepository, meetingRepository, messagePublisher, tenantScopedTransaction,
            Clock.systemUTC(), null, null, null);
    }
    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this(taskRepository, meetingRepository, messagePublisher, tenantScopedTransaction,
            clock, null, null, null);
    }
    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        MeetingGlossaryRepository glossaryRepository,
        MeetingDocumentRepository meetingDocumentRepository
    ) {
        this(taskRepository, meetingRepository, messagePublisher, tenantScopedTransaction,
            clock, glossaryRepository, meetingDocumentRepository, null);
    }
    public ProcessingTaskApplicationService(
        ProcessingTaskRepository taskRepository,
        MeetingRepository meetingRepository,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        MeetingGlossaryRepository glossaryRepository,
        MeetingDocumentRepository meetingDocumentRepository,
        JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator
    ) {
        this.taskRepository = taskRepository;
        this.meetingRepository = meetingRepository;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
        this.glossaryRepository = glossaryRepository;
        this.meetingDocumentRepository = meetingDocumentRepository;
        this.javaLlmPhaseOrchestrator = javaLlmPhaseOrchestrator;
    }

    @Override
    public ProcessingTaskDTO create(CreateProcessingTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            if (!MEETING_FULL_PIPELINE.equals(command.taskType())) {
                throw new IllegalArgumentException("unsupported taskType: " + command.taskType());
            }
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + command.meetingId()));

            OffsetDateTime now = OffsetDateTime.now(clock);
            ProcessingTask task = ProcessingTask.create(
                "task_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.meetingId(),
                command.taskType(),
                MVP0_STEPS,
                now,
                command.holdAtWorkerPhase()
            );
            task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, now);
            task.enqueue(now);
            task.claimLease(
                "worker_dev_001",
                "worker_dev_001:" + task.taskId() + ":" + task.attemptNo(),
                now.plusMinutes(5),
                now
            );
            ProcessingTask saved = taskRepository.save(task);
            messagePublisher.publish(new ProcessingTaskCreatedEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                saved.tenantId(),
                saved.taskId(),
                saved.meetingId(),
                saved.taskType(),
                saved.attemptNo(),
                MEETING_WORKER_STEPS,
                0,
                now,
                processingTaskMessagePayload(command, saved, meeting)
            ));
            return ProcessingTaskAssembler.toDto(saved);
        });
    }

    @Override
    public Optional<ProcessingTaskDTO> get(String tenantId, String taskId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> taskRepository.findById(tenantId, taskId).map(ProcessingTaskAssembler::toDto));
    }

    @Override
    public Optional<ProcessingTaskDTO> getLatestForMeeting(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> taskRepository.findLatestByMeetingId(tenantId, meetingId).map(ProcessingTaskAssembler::toDto));
    }
    public ProcessingTaskDTO createForCompletedAudioUpload(
        String tenantId,
        String meetingId,
        String fileId,
        String audioUri,
        String bucket,
        String objectKey,
        String fileSha256,
        long fileSizeBytes,
        String requestedBy,
        String idempotencyKey,
        String requestId,
        String traceId
    ) {
        var meeting = meetingRepository.findById(tenantId, meetingId)
            .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
        if (meeting.status() == com.meeting.api.client.enums.MeetingStatus.CREATED) {
            meetingRepository.updateStatus(tenantId, meetingId, com.meeting.api.client.enums.MeetingStatus.PROCESSING);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        ProcessingTask task = ProcessingTask.create(
            "task_" + UUID.randomUUID().toString().replace("-", ""),
            tenantId,
            meetingId,
            MEETING_FULL_PIPELINE,
            PHASE2_AUDIO_UPLOAD_STEPS,
            now
        );
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, now);
        task.enqueue(now);
        task.claimLease(
            "worker_dev_001",
            "worker_dev_001:" + task.taskId() + ":" + task.attemptNo(),
            now.plusMinutes(5),
            now
        );
        ProcessingTask saved = taskRepository.save(task);
        messagePublisher.publish(new ProcessingTaskCreatedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            saved.tenantId(),
            saved.taskId(),
            saved.meetingId(),
            saved.taskType(),
            saved.attemptNo(),
            MEETING_WORKER_STEPS,
            0,
            now,
            phase2TaskMessagePayload(
                saved,
                meeting,
                fileId,
                audioUri,
                fileSha256,
                fileSizeBytes,
                traceId
            )
        ));
        return ProcessingTaskAssembler.toDto(saved);
    }

    /**
     * Create a SPEAKER_ENROLLMENT task for a given speaker profile + enrollment + audio file.
     * meetingId is intentionally null — speaker enrollments are tenant-scoped, not meeting-scoped.
     * Callback handlers must verify {speakerProfileId, speakerEnrollmentId} belong to the current tenant
     * before persisting any embedding or candidate.
     */
    public ProcessingTaskDTO createForSpeakerEnrollment(
        String tenantId,
        String speakerProfileId,
        String speakerEnrollmentId,
        String audioFileId,
        String audioUri,
        String language,
        String requestedBy,
        String traceId
    ) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ProcessingTask task = ProcessingTask.create(
            "task_" + UUID.randomUUID().toString().replace("-", ""),
            tenantId,
            null,
            SPEAKER_ENROLLMENT,
            SPEAKER_ENROLLMENT_STEPS,
            now
        );
        task.enqueue(now);
        task.claimLease(
            "worker_dev_001",
            "worker_dev_001:" + task.taskId() + ":" + task.attemptNo(),
            now.plusMinutes(5),
            now
        );
        ProcessingTask saved = taskRepository.save(task);
        messagePublisher.publish(new ProcessingTaskCreatedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            saved.tenantId(),
            saved.taskId(),
            null,
            saved.taskType(),
            saved.attemptNo(),
            SPEAKER_ENROLLMENT_STEPS,
            0,
            now,
            speakerEnrollmentMessagePayload(saved, speakerProfileId, speakerEnrollmentId, audioFileId, audioUri, language, traceId)
        ));
        return ProcessingTaskAssembler.toDto(saved);
    }

    @Override
    public ProcessingTaskDTO retry(RetryTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            task.retry(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    @Override
    public ProcessingTaskDTO cancel(CancelTaskCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + command.taskId()));
            task.requestCancel(OffsetDateTime.now(clock));
            return ProcessingTaskAssembler.toDto(taskRepository.save(task));
        });
    }

    @Override
    public ProcessingTaskDTO resumeJavaPhase(ResumeJavaPhaseCommand command) {
        ProcessingTaskDTO gated = tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ProcessingTask task = taskRepository.findById(command.tenantId(), command.taskId())
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.TASK_NOT_FOUND, 404,
                    "task not found: " + command.taskId(), false
                ));
            ProcessingTaskPhase phase = task.phase();
            if (phase == ProcessingTaskPhase.JAVA_LLM_RUNNING || phase == ProcessingTaskPhase.TERMINAL) {
                // Idempotent: already past the gate.
                return ProcessingTaskAssembler.toDto(task);
            }
            if (phase != ProcessingTaskPhase.WORKER_DAG_DONE) {
                throw new ApplicationException(
                    ErrorCode.INVALID_TASK_PHASE, 422,
                    "task phase is " + phase + ", expected WORKER_DAG_DONE", false
                );
            }
            task.beginJavaLlm(OffsetDateTime.now(clock));
            taskRepository.save(task);
            return ProcessingTaskAssembler.toDto(task);
        });
        if (javaLlmPhaseOrchestrator != null && gated.phase() == ProcessingTaskPhase.JAVA_LLM_RUNNING) {
            return javaLlmPhaseOrchestrator.run(command.tenantId(), command.taskId());
        }
        return gated;
    }

    private Map<String, Object> processingTaskMessagePayload(
        CreateProcessingTaskCommand command,
        ProcessingTask task,
        Meeting meeting
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.taskId());
        payload.put("taskType", task.taskType());
        payload.put("tenantId", task.tenantId());
        payload.put("meetingId", task.meetingId());
        payload.put("securityLevel", "INTERNAL");
        payload.put("attemptNo", task.attemptNo());
        payload.put("pipelineSteps", MEETING_WORKER_STEPS.stream().map(Enum::name).toList());
        payload.put("expectedInputVersion", expectedInputVersionForMeeting(command.expectedInputVersion(), meeting));
        payload.put("options", command.options() == null ? Map.of() : command.options());
        payload.put("traceId", command.traceId() == null ? "" : command.traceId());
        addWorkstationContext(payload, task.tenantId(), task.meetingId());
        if (task.holdAtWorkerPhase()) {
            // Worker MUST NOT branch on this; Java reads it on the WORKER_PHASE_COMPLETED listener.
            payload.put("controlFlags", Map.of("holdAtWorkerPhase", true));
        }
        return payload;
    }

    /**
     * Workstation D1 / D2 — append meeting-scoped glossary + REFERENCE document ids to
     * the task message so ai-worker can (optionally) bias hot-words and so the Java
     * minutes generation can pull the same set deterministically. If either repository
     * is unwired (legacy tests), this is a no-op.
     */
    private void addWorkstationContext(Map<String, Object> payload, String tenantId, String meetingId) {
        if (meetingId == null) return;
        if (glossaryRepository != null) {
            glossaryRepository.findByMeetingId(tenantId, meetingId).ifPresent(terms -> {
                if (!terms.isEmpty()) {
                    payload.put("glossaryTerms",
                        terms.stream().map(MeetingGlossaryRepository.GlossaryTerm::term).toList());
                }
            });
        }
        if (meetingDocumentRepository != null) {
            List<String> referenceDocIds = new ArrayList<>();
            for (var row : meetingDocumentRepository.listByMeeting(tenantId, meetingId)) {
                if (row.role() == DocumentRole.REFERENCE) {
                    referenceDocIds.add(row.documentId());
                }
            }
            if (!referenceDocIds.isEmpty()) {
                payload.put("referenceDocumentIds", referenceDocIds);
            }
        }
    }

    private Map<String, Object> phase2TaskMessagePayload(
        ProcessingTask task,
        Meeting meeting,
        String fileId,
        String audioUri,
        String fileSha256,
        long fileSizeBytes,
        String traceId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.taskId());
        payload.put("taskType", task.taskType());
        payload.put("tenantId", task.tenantId());
        payload.put("meetingId", task.meetingId());
        payload.put("securityLevel", "INTERNAL");
        payload.put("attemptNo", task.attemptNo());
        payload.put("pipelineSteps", MEETING_WORKER_STEPS.stream().map(Enum::name).toList());
        payload.put("expectedInputVersion", expectedInputVersionForMeeting(null, meeting));
        payload.put("language", meeting.language());
        payload.put("channelMap", Map.of("channelCount", 1, "layout", "mono"));
        payload.put("knownParticipants", knownParticipantIds(meeting));
        payload.put("minSpeakers", 1);
        payload.put("maxSpeakers", 4);
        payload.put("audioFileId", fileId);
        payload.put("audioUri", audioUri);
        payload.put("options", Map.of(
            "enableAsr", true,
            "enableDiarization", true,
            "enableSpeakerRecognition", true,
            "enableRagIndexing", true,
            "enableAlignment", true,
            "inputAudioSha256", fileSha256,
            "inputAudioSizeBytes", fileSizeBytes
        ));
        payload.put("traceId", traceId == null || traceId.isBlank() ? "trace_" + task.taskId() : traceId);
        addWorkstationContext(payload, task.tenantId(), task.meetingId());
        if (task.holdAtWorkerPhase()) {
            payload.put("controlFlags", Map.of("holdAtWorkerPhase", true));
        }
        return payload;
    }

    private Map<String, Object> speakerEnrollmentMessagePayload(
        ProcessingTask task,
        String speakerProfileId,
        String speakerEnrollmentId,
        String audioFileId,
        String audioUri,
        String language,
        String traceId
    ) {
        return Map.ofEntries(
            Map.entry("taskId", task.taskId()),
            Map.entry("taskType", task.taskType()),
            Map.entry("tenantId", task.tenantId()),
            Map.entry("securityLevel", "INTERNAL"),
            Map.entry("attemptNo", task.attemptNo()),
            Map.entry("pipelineSteps", SPEAKER_ENROLLMENT_STEPS.stream().map(Enum::name).toList()),
            // expectedInputVersion.chunkStrategyVersion is required by
            // the contract schema (and ProcessingTaskMessageValidator)
            // for every taskType, including SPEAKER_ENROLLMENT — even
            // though the worker's speaker workflow doesn't chunk text.
            // embeddingModelVersion is the version that semantically
            // matters here; both fields keep schema validation and
            // ai-worker payload parsing happy.
            Map.entry("expectedInputVersion", Map.of(
                "chunkStrategyVersion", "v1",
                "embeddingModelVersion", "v1"
            )),
            Map.entry("speakerProfileId", speakerProfileId),
            Map.entry("speakerEnrollmentId", speakerEnrollmentId),
            Map.entry("audioFileId", audioFileId),
            Map.entry("audioUri", audioUri),
            Map.entry("language", language == null || language.isBlank() ? "zh" : language),
            Map.entry("options", Map.of()),
            Map.entry("traceId", traceId == null || traceId.isBlank() ? "trace_" + task.taskId() : traceId)
        );
    }

    private static List<String> knownParticipantIds(Meeting meeting) {
        return meeting.participants().stream()
            .map(participant -> {
                if (participant.personId() != null && !participant.personId().isBlank()) {
                    return participant.personId();
                }
                return participant.displayName();
            })
            .filter(value -> value != null && !value.isBlank())
            .toList();
    }

    private static Map<String, Object> expectedInputVersionForMeeting(
        Map<String, Object> requested,
        Meeting meeting
    ) {
        Map<String, Object> expectedInputVersion = new LinkedHashMap<>();
        expectedInputVersion.put("chunkStrategyVersion", "v1");
        if (requested != null) {
            expectedInputVersion.putAll(requested);
        }
        expectedInputVersion.put("transcriptVersion", meeting.transcriptVersion());
        return expectedInputVersion;
    }
}
