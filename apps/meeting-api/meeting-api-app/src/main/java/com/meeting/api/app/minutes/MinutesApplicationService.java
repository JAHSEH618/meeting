package com.meeting.api.app.minutes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.minutes.MinutesDTO;
import com.meeting.api.client.minutes.MinutesEvidenceDTO;
import com.meeting.api.client.minutes.MinutesFacade;
import com.meeting.api.client.minutes.MinutesItemDTO;
import com.meeting.api.client.minutes.MinutesSectionDTO;
import com.meeting.api.client.minutes.RegenerateMinutesCommand;
import com.meeting.api.domain.document.DocumentChunkRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingDocumentRepository;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesGeneratedEvent;
import com.meeting.api.domain.minutes.MinutesRepository;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Generates AI meeting minutes for {@code MEETING_FULL_PIPELINE} tasks during the JAVA_LLM_RUNNING phase.
 * <p>
 * Owns the {@code SUMMARY} step's business effects: load the current transcript, invoke
 * {@link LlmGateway} with the {@code MINUTES_SUMMARY} template, enrich evidence with text snapshots
 * from the transcript at generation time, and persist a new {@code meeting_minutes} version.
 */
@Service
public class MinutesApplicationService implements MinutesFacade {
    private static final Logger log = LoggerFactory.getLogger(MinutesApplicationService.class);
    private static final String TASK_NAME = "MINUTES_SUMMARY";
    private static final String CAPABILITY = "MINUTES_SUMMARY";

    private final MeetingRepository meetingRepository;
    private final MinutesRepository minutesRepository;
    private final TranscriptRepository transcriptRepository;
    private final LlmGateway llmGateway;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MessagePublisher messagePublisher;
    private final MeetingGlossaryRepository glossaryRepository;
    private final MeetingDocumentRepository meetingDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    /** Token budget for glossary + reference document snippets (~2k chars, R3). */
    static final int WORKSTATION_CONTEXT_CHAR_BUDGET = 2048;

    @Autowired
    public MinutesApplicationService(
        MeetingRepository meetingRepository,
        MinutesRepository minutesRepository,
        TranscriptRepository transcriptRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper
    ) {
        this(meetingRepository, minutesRepository, transcriptRepository, llmGateway,
            tenantScopedTransaction, objectMapper, Clock.systemUTC(), null, null, null, null, null);
    }
    public MinutesApplicationService(
        MeetingRepository meetingRepository,
        MinutesRepository minutesRepository,
        TranscriptRepository transcriptRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this(meetingRepository, minutesRepository, transcriptRepository, llmGateway,
            tenantScopedTransaction, objectMapper, clock, null, null, null, null, null);
    }
    public MinutesApplicationService(
        MeetingRepository meetingRepository,
        MinutesRepository minutesRepository,
        TranscriptRepository transcriptRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper,
        Clock clock,
        ApplicationEventPublisher applicationEventPublisher,
        MessagePublisher messagePublisher
    ) {
        this(meetingRepository, minutesRepository, transcriptRepository, llmGateway,
            tenantScopedTransaction, objectMapper, clock, applicationEventPublisher, messagePublisher,
            null, null, null);
    }
    public MinutesApplicationService(
        MeetingRepository meetingRepository,
        MinutesRepository minutesRepository,
        TranscriptRepository transcriptRepository,
        LlmGateway llmGateway,
        TenantScopedTransaction tenantScopedTransaction,
        ObjectMapper objectMapper,
        Clock clock,
        ApplicationEventPublisher applicationEventPublisher,
        MessagePublisher messagePublisher,
        MeetingGlossaryRepository glossaryRepository,
        MeetingDocumentRepository meetingDocumentRepository,
        DocumentChunkRepository documentChunkRepository
    ) {
        this.meetingRepository = meetingRepository;
        this.minutesRepository = minutesRepository;
        this.transcriptRepository = transcriptRepository;
        this.llmGateway = llmGateway;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
        this.messagePublisher = messagePublisher;
        this.glossaryRepository = glossaryRepository;
        this.meetingDocumentRepository = meetingDocumentRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Override
    public Optional<MinutesDTO> get(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> minutesRepository.findCurrent(tenantId, meetingId).map(MinutesApplicationService::toDto));
    }

    @Override
    public MinutesDTO regenerate(RegenerateMinutesCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(),
            () -> doRegenerate(command, null));
    }

    /**
     * Generate minutes as part of the worker-callback driven LLM phase.
     * Invoked by the Java LLM orchestrator; not a public endpoint.
     */
    public MinutesDTO generateForTask(String tenantId, String meetingId, String taskId, Integer expectedTranscriptVersion) {
        RegenerateMinutesCommand command = new RegenerateMinutesCommand(
            tenantId,
            meetingId,
            null,
            null,
            null,
            expectedTranscriptVersion,
            null
        );
        return doRegenerate(command, taskId);
    }

    private MinutesDTO doRegenerate(RegenerateMinutesCommand command, String taskId) {
        // Short TX #1: load meeting + transcript, validate versions
        GenerationContext context = tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + command.meetingId()));

            int currentTranscriptVersion = transcriptRepository.currentTranscriptVersion(command.tenantId(), command.meetingId());
            if (command.expectedTranscriptVersion() != null && command.expectedTranscriptVersion() != currentTranscriptVersion) {
                throw new VersionConflictException(
                    "transcript version mismatch: expected=" + command.expectedTranscriptVersion() + " actual=" + currentTranscriptVersion
                );
            }
            int currentMinutesVersion = minutesRepository.currentMinutesVersion(command.tenantId(), command.meetingId());
            if (command.expectedMinutesVersion() != null && command.expectedMinutesVersion() != currentMinutesVersion) {
                throw new VersionConflictException(
                    "minutes version mismatch: expected=" + command.expectedMinutesVersion() + " actual=" + currentMinutesVersion
                );
            }

            List<TranscriptRepository.TranscriptSegmentRecord> segments = transcriptRepository.findByMeeting(
                command.tenantId(),
                command.meetingId(),
                currentTranscriptVersion
            );
            Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById = new HashMap<>();
            for (var seg : segments) {
                segmentById.put(seg.segmentId(), seg);
            }

            return new GenerationContext(meeting, segments, segmentById, currentTranscriptVersion, currentMinutesVersion);
        });

        // No TX: call LLM gateway
        LlmGateway.LlmResponse response;
        try {
            response = llmGateway.complete(new LlmGateway.LlmRequest(
                command.tenantId(),
                command.meetingId(),
                taskId,
                CAPABILITY,
                TASK_NAME,
                buildLlmContext(context.meeting, command, context.segments),
                (String) null,
                (String) null
            ));
        } catch (RuntimeException ex) {
            log.warn("minutes_llm_failed tenant={} meeting={} error={}", command.tenantId(), command.meetingId(), ex.getMessage());
            throw ex;
        }

        // Short TX #2: parse, enrich, persist minutes + outbox event
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            ParsedMinutes parsed = parse(response.structuredJson() != null ? response.structuredJson() : response.content());
            List<MinutesRepository.SectionRecord> sectionRecords = enrichEvidence(parsed.sections, context.segmentById);

            int newMinutesVersion = context.currentMinutesVersion + 1;
            OffsetDateTime now = OffsetDateTime.now(clock);
            String minutesId = "min_" + UUID.randomUUID().toString().replace("-", "");
            MinutesRepository.MinutesRecord record = new MinutesRepository.MinutesRecord(
                minutesId,
                command.tenantId(),
                command.meetingId(),
                newMinutesVersion,
                context.currentTranscriptVersion,
                parsed.title,
                parsed.markdown,
                sectionRecords,
                "PUBLISHED",
                StaleStatus.ACTIVE,
                response.artifactManifestId(),
                command.requestedBy(),
                now,
                now
            );
            minutesRepository.save(record);
            minutesRepository.incrementMeetingMinutesVersion(command.tenantId(), command.meetingId(), newMinutesVersion);
            log.info("minutes_regenerated tenant={} meeting={} minutesVersion={}", command.tenantId(), command.meetingId(), newMinutesVersion);
            publishMinutesGenerated(command.tenantId(), command.meetingId(), minutesId, newMinutesVersion, context.currentTranscriptVersion, now);
            return toDto(record);
        });
    }

    private void publishMinutesGenerated(
        String tenantId,
        String meetingId,
        String minutesId,
        int minutesVersion,
        int transcriptVersion,
        OffsetDateTime now
    ) {
        MinutesGeneratedEvent event = new MinutesGeneratedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            tenantId, meetingId, minutesId, minutesVersion, transcriptVersion,
            1L, now
        );
        // In-process: MinutesGeneratedRagIndexer @TransactionalEventListener(AFTER_COMMIT)
        // picks this up to trigger chunking + embed-task dispatch (D4).
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(event);
        }
        // Outbox: downstream analytics / SSE finalize signal consumers.
        if (messagePublisher != null) {
            messagePublisher.publish(event);
        }
    }

    private Map<String, Object> buildLlmContext(
        Meeting meeting,
        RegenerateMinutesCommand command,
        List<TranscriptRepository.TranscriptSegmentRecord> segments
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("meetingTitle", meeting.title());
        context.put("meetingId", command.meetingId());
        context.put("transcript", renderTranscript(segments));

        // Workstation D2 — glossary terms (token-budget capped, R3).
        String glossaryBlock = glossaryBlockFor(command.tenantId(), command.meetingId());
        if (!glossaryBlock.isEmpty()) {
            context.put("glossary", glossaryBlock);
        }
        // Workstation D1 — REFERENCE document summaries.
        // The LlmGateway already fail-closes on CONFIDENTIAL / SECRET meetings, so we don't need
        // to re-check here. If the gateway lets the call through, the meeting is PUBLIC / INTERNAL
        // and references with effectively-elevated security were rejected at attach time (R4).
        String referenceBlock = referenceBlockFor(command.tenantId(), command.meetingId());
        if (!referenceBlock.isEmpty()) {
            context.put("referenceDocuments", referenceBlock);
        }
        return context;
    }

    private String glossaryBlockFor(String tenantId, String meetingId) {
        if (glossaryRepository == null) return "";
        var stored = glossaryRepository.findByMeetingId(tenantId, meetingId);
        if (stored.isEmpty() || stored.get().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int budget = WORKSTATION_CONTEXT_CHAR_BUDGET / 2;
        for (var term : stored.get()) {
            String line = term.term()
                + (term.definition() != null && !term.definition().isBlank()
                    ? ": " + term.definition() : "")
                + (term.aliases() != null && !term.aliases().isEmpty()
                    ? " (aka " + String.join(", ", term.aliases()) + ")" : "")
                + "\n";
            if (sb.length() + line.length() > budget) {
                sb.append("…\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private String referenceBlockFor(String tenantId, String meetingId) {
        if (meetingDocumentRepository == null || documentChunkRepository == null) return "";
        var links = meetingDocumentRepository.listByMeeting(tenantId, meetingId);
        if (links.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int budget = WORKSTATION_CONTEXT_CHAR_BUDGET / 2;
        for (var link : links) {
            if (link.role() != DocumentRole.REFERENCE) continue;
            sb.append("## ").append(link.documentTitle() == null ? link.documentId() : link.documentTitle()).append("\n");
            var chunks = documentChunkRepository.findByDocument(tenantId, link.documentId());
            for (var chunk : chunks) {
                if (chunk.content() == null || chunk.content().isBlank()) continue;
                if (sb.length() >= budget) {
                    sb.append("…\n");
                    return sb.toString();
                }
                int remaining = budget - sb.length();
                String content = chunk.content().strip();
                sb.append(content.length() > remaining ? content.substring(0, remaining) + "…" : content);
                sb.append("\n");
            }
        }
        return sb.toString();
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

    private ParsedMinutes parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM produced empty minutes output");
        }
        try {
            JsonNode root = objectMapper.readTree(llmOutput);
            String title = root.has("title") ? root.get("title").asText() : null;
            String markdown = root.has("markdown") ? root.get("markdown").asText() : llmOutput;
            List<MinutesRepository.SectionRecord> sections = new ArrayList<>();
            JsonNode sectionsNode = root.get("sections");
            if (sectionsNode != null && sectionsNode.isArray()) {
                for (JsonNode sectionNode : sectionsNode) {
                    sections.add(parseSection(sectionNode));
                }
            }
            return new ParsedMinutes(title, markdown, sections);
        } catch (LlmProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM minutes output is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private MinutesRepository.SectionRecord parseSection(JsonNode node) {
        String type = node.has("type") ? node.get("type").asText() : "GENERIC";
        String title = node.has("title") ? node.get("title").asText() : type;
        List<MinutesRepository.ItemRecord> items = new ArrayList<>();
        JsonNode itemsNode = node.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                String text = itemNode.has("text") ? itemNode.get("text").asText() : "";
                List<MinutesRepository.EvidenceRecord> evidence = new ArrayList<>();
                JsonNode evidenceNode = itemNode.get("evidence");
                if (evidenceNode != null && evidenceNode.isArray()) {
                    for (JsonNode ev : evidenceNode) {
                        evidence.add(new MinutesRepository.EvidenceRecord(
                            ev.has("segmentId") ? ev.get("segmentId").asText() : null,
                            ev.has("startMs") ? ev.get("startMs").asLong() : null,
                            ev.has("endMs") ? ev.get("endMs").asLong() : null,
                            ev.has("evidenceTextSnapshot") ? ev.get("evidenceTextSnapshot").asText() : null
                        ));
                    }
                }
                items.add(new MinutesRepository.ItemRecord(text, evidence));
            }
        }
        return new MinutesRepository.SectionRecord(type, title, items);
    }

    private static List<MinutesRepository.SectionRecord> enrichEvidence(
        List<MinutesRepository.SectionRecord> sections,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById
    ) {
        List<MinutesRepository.SectionRecord> enriched = new ArrayList<>();
        for (var section : sections) {
            List<MinutesRepository.ItemRecord> items = new ArrayList<>();
            for (var item : section.items()) {
                List<MinutesRepository.EvidenceRecord> ev = new ArrayList<>();
                for (var e : item.evidence()) {
                    if (e.segmentId() != null && segmentById.containsKey(e.segmentId())) {
                        var seg = segmentById.get(e.segmentId());
                        String snapshot = seg.currentText() == null || seg.currentText().isEmpty() ? seg.originalText() : seg.currentText();
                        ev.add(new MinutesRepository.EvidenceRecord(seg.segmentId(), seg.startMs(), seg.endMs(), snapshot));
                    } else if (e.segmentId() != null) {
                        // segment id was hallucinated; drop it to avoid evidence drift
                        log.warn("minutes_evidence_segment_missing segmentId={}", e.segmentId());
                    } else {
                        ev.add(e);
                    }
                }
                items.add(new MinutesRepository.ItemRecord(item.text(), ev));
            }
            enriched.add(new MinutesRepository.SectionRecord(section.type(), section.title(), items));
        }
        return enriched;
    }

    private static MinutesDTO toDto(MinutesRepository.MinutesRecord record) {
        List<MinutesSectionDTO> sections = record.sections().stream()
            .map(s -> new MinutesSectionDTO(
                s.type(),
                s.title(),
                s.items().stream()
                    .map(item -> new MinutesItemDTO(
                        item.text(),
                        item.evidence().stream()
                            .map(e -> new MinutesEvidenceDTO(e.segmentId(), e.startMs(), e.endMs(), e.evidenceTextSnapshot()))
                            .toList()
                    ))
                    .toList()
            ))
            .toList();
        return new MinutesDTO(
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.minutesVersion(),
            record.sourceTranscriptVersion(),
            record.title(),
            record.markdown(),
            sections,
            record.status(),
            record.staleStatus(),
            record.artifactManifestId(),
            record.createdAt(),
            record.updatedAt()
        );
    }

    private record ParsedMinutes(String title, String markdown, List<MinutesRepository.SectionRecord> sections) {
    }

    private record GenerationContext(
        Meeting meeting,
        List<TranscriptRepository.TranscriptSegmentRecord> segments,
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById,
        int currentTranscriptVersion,
        int currentMinutesVersion
    ) {
    }

    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(String message) {
            super(message);
        }
    }
}
