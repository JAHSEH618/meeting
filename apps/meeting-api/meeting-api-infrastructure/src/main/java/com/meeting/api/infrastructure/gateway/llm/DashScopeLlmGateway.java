package com.meeting.api.infrastructure.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.artifact.ArtifactManifestRepository;
import com.meeting.api.domain.llm.LlmCallLogRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.llm.PromptTemplateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DashScope-flavored {@link LlmGateway} implementation.
 *
 * Responsibilities:
 * <ol>
 *   <li>Load active {@link PromptTemplateRepository.PromptTemplate} by task name.</li>
 *   <li>Render template by substituting {@code {{var}}} placeholders.</li>
 *   <li>Call the LLM via {@link OpenAiCompatibleChatClient}.</li>
 *   <li>Validate the response against the template's JSON schema (top-level required keys check).</li>
 *   <li>On success, persist an {@code artifact_manifests} row (provenance ledger for §12.5
 *       traceability) and an {@code llm_call_logs} row (per-call audit log). The manifest id
 *       is returned in {@link LlmResponse#artifactManifestId()} so downstream business rows
 *       (e.g. {@code meeting_minutes.artifact_manifest_id}) can satisfy their FK to this
 *       ledger; the call-log id stays available for raw-call audit linkage.</li>
 *   <li>On failure, record only the call-log row (no business artifact was produced).</li>
 * </ol>
 */
@Component
public class DashScopeLlmGateway implements LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(DashScopeLlmGateway.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final String PROVIDER = "dashscope";

    private final OpenAiCompatibleChatClient client;
    private final PromptTemplateRepository promptTemplateRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final ArtifactManifestRepository artifactManifestRepository;
    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final Clock clock;

    @Autowired
    public DashScopeLlmGateway(
        OpenAiCompatibleChatClient client,
        PromptTemplateRepository promptTemplateRepository,
        LlmCallLogRepository llmCallLogRepository,
        ArtifactManifestRepository artifactManifestRepository,
        ObjectMapper objectMapper,
        @Value("${meeting.llm.dashscope.default-model:qwen-plus}") String defaultModel,
        Clock clock
    ) {
        this.client = client;
        this.promptTemplateRepository = promptTemplateRepository;
        this.llmCallLogRepository = llmCallLogRepository;
        this.artifactManifestRepository = artifactManifestRepository;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
        this.clock = clock;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        PromptTemplateRepository.PromptTemplate template = promptTemplateRepository
            .findActiveByTaskName(request.tenantId(), request.taskName())
            .or(() -> promptTemplateRepository.findActiveByTaskName(null, request.taskName()))
            .orElseThrow(() -> new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID,
                "active prompt template not found for task " + request.taskName()));
        String rendered = renderTemplate(template.templateBody(), request.variables());
        // Loud signal instead of silent data loss: a placeholder the caller
        // didn't provide renders as an empty string — with a template/code
        // variable-name drift that can mean the entire transcript silently
        // missing from the prompt while the call still "succeeds".
        java.util.List<String> unresolved = findUnresolvedVariables(template.templateBody(), request.variables());
        if (!unresolved.isEmpty()) {
            log.error(
                "llm_template_unresolved_variables task={} template={} version={} vars={} — "
                    + "these placeholders rendered EMPTY; align the template body with the caller's context keys",
                request.taskName(), template.id(), template.version(), unresolved
            );
        }
        String inputHash = sha256(rendered);
        OffsetDateTime startedAt = OffsetDateTime.now(clock);

        OpenAiCompatibleChatClient.ChatCompletion completion;
        String errorCode = null;
        String status = "SUCCEEDED";
        try {
            completion = client.chatComplete(new OpenAiCompatibleChatClient.ChatCompletionRequest(
                defaultModel,
                java.util.List.of(OpenAiCompatibleChatClient.ChatMessage.user(rendered)),
                0.2,
                null,
                request.expectedJsonSchema() != null && !request.expectedJsonSchema().isBlank()
                    ? request.expectedJsonSchema()
                    : template.jsonSchema()
            ));
        } catch (LlmProviderException ex) {
            recordFailedCall(request, template, inputHash, ex.errorCode().name(), 0, startedAt);
            throw ex;
        }
        String structuredJson = extractStructuredJson(completion.content(), template.jsonSchema());
        if (structuredJson != null) {
            validateAgainstSchema(structuredJson, template.jsonSchema());
        }
        String outputHash = sha256(completion.content());
        OffsetDateTime now = OffsetDateTime.now(clock);
        String manifestId = recordArtifactManifest(
            request, template, inputHash, outputHash, completion, now
        );
        String callLogId = recordSuccessfulCall(
            request,
            template,
            inputHash,
            outputHash,
            completion,
            startedAt
        );
        return new LlmResponse(
            completion.content(),
            structuredJson,
            completion.promptTokens(),
            completion.completionTokens(),
            completion.latencyMs(),
            completion.modelVersion(),
            callLogId,
            manifestId
        );
    }

    private String recordArtifactManifest(
        LlmRequest request,
        PromptTemplateRepository.PromptTemplate template,
        String inputHash,
        String outputHash,
        OpenAiCompatibleChatClient.ChatCompletion completion,
        OffsetDateTime now
    ) {
        String id = "art_" + UUID.randomUUID().toString().replace("-", "");
        String modelsJson = String.format(
            "[{\"role\":\"llm\",\"provider\":\"%s\",\"modelVersion\":%s}]",
            PROVIDER, quoteJson(completion.modelVersion())
        );
        // Don't persist raw input/output text — we already have content
        // hashes on llm_call_logs and full content can be re-derived from
        // the prompt template + input variables when reproducing the call.
        String inputJson = String.format(
            "{\"capability\":%s,\"taskName\":%s}",
            quoteJson(request.capability()),
            quoteJson(request.taskName())
        );
        String outputJson = String.format(
            "{\"promptTokens\":%d,\"completionTokens\":%d,\"latencyMs\":%d}",
            completion.promptTokens(), completion.completionTokens(), completion.latencyMs()
        );
        try {
            return artifactManifestRepository.save(new ArtifactManifestRepository.ArtifactManifestRecord(
                id,
                request.tenantId(),
                request.meetingId(),
                request.taskId(),
                "LLM_" + request.capability(),
                null,
                outputHash,
                inputHash,
                inputJson,
                outputJson,
                modelsJson,
                template.id(),
                template.version(),
                PROVIDER,
                completion.modelVersion(),
                null,
                null,
                null,
                now
            ));
        } catch (RuntimeException ex) {
            // The downstream business write FKs to artifact_manifests; if we
            // can't persist the manifest the caller must fail closed rather
            // than write an orphaned business row.
            throw new LlmProviderException(
                ErrorCode.WRITEBACK_FAILED,
                "artifact_manifest persist failed: " + ex.getMessage(),
                ex
            );
        }
    }

    private static String quoteJson(String value) {
        if (value == null) return "null";
        // Minimal JSON string escape — sufficient for our short, audited inputs.
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String renderTemplate(String template, Map<String, Object> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables == null ? null : variables.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Placeholder names present in the template body but absent (or null) in
     * the caller-provided variables. Checked against the template, not the
     * rendered output, so substituted values containing literal braces can't
     * produce false positives. */
    static java.util.List<String> findUnresolvedVariables(String template, Map<String, Object> variables) {
        java.util.List<String> unresolved = new java.util.ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if ((variables == null || variables.get(key) == null) && !unresolved.contains(key)) {
                unresolved.add(key);
            }
        }
        return unresolved;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String extractStructuredJson(String content, String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank() || jsonSchema.equals("{}")) {
            return null;
        }
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // tolerant: extract first {...} block
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    private void validateAgainstSchema(String json, String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank() || jsonSchema.equals("{}")) {
            return;
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            JsonNode schema = objectMapper.readTree(jsonSchema);
            JsonNode required = schema.get("required");
            if (required == null || !required.isArray()) {
                return;
            }
            for (JsonNode field : required) {
                if (!parsed.has(field.asText())) {
                    throw new LlmProviderException(
                        ErrorCode.LLM_SCHEMA_INVALID,
                        "LLM response missing required field: " + field.asText()
                    );
                }
            }
        } catch (LlmProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID, "LLM response is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private String recordSuccessfulCall(
        LlmRequest request,
        PromptTemplateRepository.PromptTemplate template,
        String inputHash,
        String outputHash,
        OpenAiCompatibleChatClient.ChatCompletion completion,
        OffsetDateTime startedAt
    ) {
        int latencyMs = (int) completion.latencyMs();
        int tokenTotal = completion.promptTokens() + completion.completionTokens();
        String id = "llmlog_" + UUID.randomUUID().toString().replace("-", "");
        try {
            llmCallLogRepository.record(new LlmCallLogRepository.LlmCallLogRecord(
                id,
                request.tenantId(),
                request.meetingId(),
                request.taskId(),
                request.capability(),
                PROVIDER,
                defaultModel,
                completion.modelVersion(),
                template.id(),
                template.version(),
                inputHash,
                outputHash,
                completion.promptTokens(),
                completion.completionTokens(),
                tokenTotal,
                latencyMs,
                "SUCCEEDED",
                null,
                startedAt
            ));
        } catch (RuntimeException ex) {
            log.warn("llm_call_log_persist_failed task={} reason={}", request.taskName(), ex.getMessage());
        }
        return id;
    }

    private void recordFailedCall(
        LlmRequest request,
        PromptTemplateRepository.PromptTemplate template,
        String inputHash,
        String errorCode,
        int latencyMs,
        OffsetDateTime startedAt
    ) {
        String id = "llmlog_" + UUID.randomUUID().toString().replace("-", "");
        try {
            llmCallLogRepository.record(new LlmCallLogRepository.LlmCallLogRecord(
                id,
                request.tenantId(),
                request.meetingId(),
                request.taskId(),
                request.capability(),
                PROVIDER,
                defaultModel,
                null,
                template.id(),
                template.version(),
                inputHash,
                null,
                null,
                null,
                null,
                latencyMs,
                "FAILED",
                errorCode,
                startedAt
            ));
        } catch (RuntimeException ex) {
            log.warn("llm_call_log_persist_failed_on_error task={} reason={}", request.taskName(), ex.getMessage());
        }
    }
}
