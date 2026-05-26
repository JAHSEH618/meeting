package com.meeting.api.app.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.extraction.ExtractionSummary;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.ActionItemRepository.ActionItemRecord;
import com.meeting.api.domain.extraction.ActionItemRepository.EvidenceJson;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Generates action items, decisions, and risks for {@code MEETING_FULL_PIPELINE} tasks
 * during the {@code EXTRACTION} step.
 * <p>
 * AI-extracted records are persisted with {@code acceptance_status='DRAFT'} and the meeting's
 * current {@code transcript_version}. User confirmation later promotes them to {@code ACCEPTED};
 * regenerating only adds new drafts and never overwrites user-confirmed acceptance state
 * (see {@code markAcceptance} repository methods used by accept/reject endpoints).
 */
@Service
public class ExtractionApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ExtractionApplicationService.class);
    private static final String TASK_NAME = "ITEM_EXTRACTION";
    private static final String CAPABILITY = "ITEM_EXTRACTION";

    private static final String DEFAULT_ACTION_PRIORITY = "P2";
    private static final String DEFAULT_ACTION_STATUS = "OPEN";
    private static final String DEFAULT_DECISION_STATUS = "PROPOSED";
    private static final String DEFAULT_RISK_SEVERITY = "MEDIUM";
    private static final String DEFAULT_RISK_STATUS = "OPEN";
    private static final String DRAFT_ACCEPTANCE = "DRAFT";

    private final MeetingRepository meetingRepository;
    private final TranscriptRepository transcriptRepository;
    private final ActionItemRepository actionItemRepository;
    private final DecisionRepository decisionRepository;
    private final RiskRepository riskRepository;
    private final LlmGateway llmGateway;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ExtractionApplicationService(
        MeetingRepository meetingRepository,
        TranscriptRepository transcriptRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper
    ) {
        this(meetingRepository, transcriptRepository, actionItemRepository, decisionRepository, riskRepository, llmGateway, tenantScopedTransaction, objectMapper, Clock.systemUTC());
    }
    public ExtractionApplicationService(
        MeetingRepository meetingRepository,
        TranscriptRepository transcriptRepository,
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.meetingRepository = meetingRepository;
        this.transcriptRepository = transcriptRepository;
        this.actionItemRepository = actionItemRepository;
        this.decisionRepository = decisionRepository;
        this.riskRepository = riskRepository;
        this.llmGateway = llmGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }
    public ExtractionSummary extractForTask(String tenantId, String meetingId, String taskId) {
        return tenantScopedTransaction.execute(tenantId, null, null, () -> doExtract(tenantId, meetingId, taskId));
    }

    private ExtractionSummary doExtract(String tenantId, String meetingId, String taskId) {
        Meeting meeting = meetingRepository.findById(tenantId, meetingId)
            .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
        int transcriptVersion = transcriptRepository.currentTranscriptVersion(tenantId, meetingId);
        List<TranscriptRepository.TranscriptSegmentRecord> segments = transcriptRepository.findByMeeting(tenantId, meetingId, transcriptVersion);

        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById = new HashMap<>();
        for (var seg : segments) {
            segmentById.put(seg.segmentId(), seg);
        }

        LlmGateway.LlmResponse response = llmGateway.complete(new LlmGateway.LlmRequest(
            tenantId,
            meetingId,
            taskId,
            CAPABILITY,
            TASK_NAME,
            meeting.securityLevel(),
            Map.of(
                "meetingTitle", meeting.title(),
                "meetingId", meetingId,
                "transcript", renderTranscript(segments)
            ),
            null,
            null
        ));

        JsonNode root = parseJson(response.structuredJson() != null ? response.structuredJson() : response.content());
        OffsetDateTime now = OffsetDateTime.now(clock);
        int actions = persistActionItems(root, tenantId, meetingId, transcriptVersion, segmentById, response.artifactManifestId(), now);
        int decisions = persistDecisions(root, tenantId, meetingId, transcriptVersion, segmentById, response.artifactManifestId(), now);
        int risks = persistRisks(root, tenantId, meetingId, transcriptVersion, segmentById, response.artifactManifestId(), now);
        log.info("extraction_completed tenant={} meeting={} actions={} decisions={} risks={}", tenantId, meetingId, actions, decisions, risks);
        return new ExtractionSummary(actions, decisions, risks);
    }

    private int persistActionItems(
        JsonNode root, String tenantId, String meetingId, int transcriptVersion,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById,
        String artifactManifestId, OffsetDateTime now
    ) {
        JsonNode arr = root.get("actionItems");
        if (arr == null || !arr.isArray()) return 0;
        int count = 0;
        for (JsonNode item : arr) {
            String title = textOrNull(item, "title");
            if (title == null || title.isBlank()) continue;
            String id = "item_" + UUID.randomUUID().toString().replace("-", "");
            actionItemRepository.save(new ActionItemRecord(
                id,
                tenantId,
                meetingId,
                "AI_EXTRACTED",
                title,
                textOrNull(item, "description"),
                textOrNull(item, "ownerPersonId"),
                textOrNull(item, "ownerRawText"),
                textOrNull(item, "deadlineRawText"),
                null,
                textOrDefault(item, "priority", DEFAULT_ACTION_PRIORITY),
                textOrDefault(item, "status", DEFAULT_ACTION_STATUS),
                DRAFT_ACCEPTANCE,
                transcriptVersion,
                StaleStatus.ACTIVE,
                evidenceFromNode(item, segmentById),
                artifactManifestId,
                now,
                now
            ));
            count++;
        }
        return count;
    }

    private int persistDecisions(
        JsonNode root, String tenantId, String meetingId, int transcriptVersion,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById,
        String artifactManifestId, OffsetDateTime now
    ) {
        JsonNode arr = root.get("decisions");
        if (arr == null || !arr.isArray()) return 0;
        int count = 0;
        for (JsonNode item : arr) {
            String title = textOrNull(item, "title");
            if (title == null || title.isBlank()) continue;
            String id = "dec_" + UUID.randomUUID().toString().replace("-", "");
            decisionRepository.save(new DecisionRepository.DecisionRecord(
                id,
                tenantId,
                meetingId,
                title,
                textOrNull(item, "description"),
                textOrDefault(item, "status", DEFAULT_DECISION_STATUS),
                DRAFT_ACCEPTANCE,
                transcriptVersion,
                StaleStatus.ACTIVE,
                evidenceFromNode(item, segmentById),
                artifactManifestId,
                now,
                now
            ));
            count++;
        }
        return count;
    }

    private int persistRisks(
        JsonNode root, String tenantId, String meetingId, int transcriptVersion,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById,
        String artifactManifestId, OffsetDateTime now
    ) {
        JsonNode arr = root.get("risks");
        if (arr == null || !arr.isArray()) return 0;
        int count = 0;
        for (JsonNode item : arr) {
            String title = textOrNull(item, "title");
            if (title == null || title.isBlank()) continue;
            String id = "risk_" + UUID.randomUUID().toString().replace("-", "");
            riskRepository.save(new RiskRepository.RiskRecord(
                id,
                tenantId,
                meetingId,
                title,
                textOrNull(item, "description"),
                textOrDefault(item, "severity", DEFAULT_RISK_SEVERITY),
                textOrDefault(item, "status", DEFAULT_RISK_STATUS),
                DRAFT_ACCEPTANCE,
                transcriptVersion,
                StaleStatus.ACTIVE,
                evidenceFromNode(item, segmentById),
                artifactManifestId,
                now,
                now
            ));
            count++;
        }
        return count;
    }

    private List<EvidenceJson> evidenceFromNode(
        JsonNode item,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById
    ) {
        JsonNode evidenceNode = item.get("evidence");
        if (evidenceNode == null || !evidenceNode.isArray()) return List.of();
        List<EvidenceJson> result = new ArrayList<>();
        for (JsonNode ev : evidenceNode) {
            String segmentId = textOrNull(ev, "segmentId");
            if (segmentId == null) continue;
            var seg = segmentById.get(segmentId);
            if (seg == null) {
                log.warn("extraction_evidence_segment_missing segmentId={}", segmentId);
                continue;
            }
            String snapshot = seg.currentText() == null || seg.currentText().isEmpty() ? seg.originalText() : seg.currentText();
            result.add(new EvidenceJson(seg.segmentId(), seg.startMs(), seg.endMs(), snapshot));
        }
        return result;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM extraction returned empty output");
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM extraction output is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private static String renderTranscript(List<TranscriptRepository.TranscriptSegmentRecord> segments) {
        StringBuilder sb = new StringBuilder();
        for (var seg : segments) {
            sb.append("[").append(seg.segmentId()).append(" ").append(seg.speakerLabel()).append("] ");
            sb.append(seg.currentText() == null || seg.currentText().isEmpty() ? seg.originalText() : seg.currentText());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String text = v.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String text = textOrNull(node, field);
        return text == null ? fallback : text;
    }
}
