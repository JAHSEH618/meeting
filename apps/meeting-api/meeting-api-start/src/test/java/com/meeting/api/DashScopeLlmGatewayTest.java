package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.artifact.ArtifactManifestRepository;
import com.meeting.api.domain.llm.LlmCallLogRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.llm.PromptTemplateRepository;
import com.meeting.api.infrastructure.gateway.llm.DashScopeLlmGateway;
import com.meeting.api.infrastructure.gateway.llm.OpenAiCompatibleChatClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeLlmGatewayTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T04:00:00Z");

    @Test
    void confidentialSecurityLevelIsFailClosed() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        InMemoryManifestRepo manifests = new InMemoryManifestRepo();
        DashScopeLlmGateway gateway = gateway(new CapturingClient(), templates, logs, manifests);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.CONFIDENTIAL)))
            .isInstanceOf(SecurityLevelBlockedException.class)
            .satisfies(ex -> {
                SecurityLevelBlockedException blocked = (SecurityLevelBlockedException) ex;
                assertThat(blocked.securityLevel()).isEqualTo(SecurityLevel.CONFIDENTIAL);
                assertThat(blocked.blockedCapability()).isEqualTo("MINUTES_SUMMARY");
            });
        assertThat(logs.records).isEmpty();
        assertThat(manifests.records).isEmpty();
    }

    @Test
    void internalSecurityLevelRendersTemplateAndCallsClient() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put("tenant_01", "MINUTES_SUMMARY",
            "Summarize meeting {{meetingTitle}}: {{transcript}}",
            "{\"required\":[\"summary\",\"sections\"]}",
            "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        InMemoryManifestRepo manifests = new InMemoryManifestRepo();
        CapturingClient client = new CapturingClient();
        client.next = new OpenAiCompatibleChatClient.ChatCompletion(
            "{\"summary\":\"ok\",\"sections\":[]}",
            "qwen-plus-1.0",
            12,
            34,
            42L,
            Map.of()
        );
        DashScopeLlmGateway gateway = gateway(client, templates, logs, manifests);

        LlmGateway.LlmResponse response = gateway.complete(new LlmGateway.LlmRequest(
            "tenant_01",
            "meeting_01",
            "task_01",
            "MINUTES_SUMMARY",
            "MINUTES_SUMMARY",
            SecurityLevel.INTERNAL,
            Map.of("meetingTitle", "Standup", "transcript", "hello world"),
            null,
            "trace_01"
        ));

        assertThat(client.lastRequest.messages()).hasSize(1);
        assertThat(client.lastRequest.messages().get(0).content())
            .isEqualTo("Summarize meeting Standup: hello world");
        assertThat(response.content()).isEqualTo("{\"summary\":\"ok\",\"sections\":[]}");
        assertThat(response.structuredJson()).isEqualTo("{\"summary\":\"ok\",\"sections\":[]}");
        assertThat(response.promptTokens()).isEqualTo(12);
        assertThat(response.completionTokens()).isEqualTo(34);
        assertThat(response.llmCallLogId()).startsWith("llmlog_");
        assertThat(response.artifactManifestId()).startsWith("art_");

        assertThat(logs.records).hasSize(1);
        LlmCallLogRepository.LlmCallLogRecord record = logs.records.get(0);
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.inputHash()).hasSize(64);
        assertThat(record.outputHash()).hasSize(64);
        assertThat(record.tokenTotal()).isEqualTo(46);
        assertThat(record.promptTemplateId()).isEqualTo("tpl_MINUTES_SUMMARY_tenant_01");
        assertThat(record.actualModelVersion()).isEqualTo("qwen-plus-1.0");

        // The manifest row is what downstream business FKs reference.
        assertThat(manifests.records).hasSize(1);
        ArtifactManifestRepository.ArtifactManifestRecord manifest = manifests.records.get(0);
        assertThat(manifest.id()).isEqualTo(response.artifactManifestId());
        assertThat(manifest.tenantId()).isEqualTo("tenant_01");
        assertThat(manifest.meetingId()).isEqualTo("meeting_01");
        assertThat(manifest.taskId()).isEqualTo("task_01");
        assertThat(manifest.artifactType()).isEqualTo("LLM_MINUTES_SUMMARY");
        assertThat(manifest.inputArtifactHash()).hasSize(64);
        assertThat(manifest.artifactHash()).hasSize(64);
        assertThat(manifest.provider()).isEqualTo("dashscope");
        assertThat(manifest.modelVersion()).isEqualTo("qwen-plus-1.0");
        assertThat(manifest.promptTemplateId()).isEqualTo("tpl_MINUTES_SUMMARY_tenant_01");
        assertThat(manifest.promptTemplateVersion()).isEqualTo("1.0.0");
    }

    @Test
    void manifestPersistFailureFailsTheLlmCall() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put(null, "MINUTES_SUMMARY", "render {{transcript}}", "{}", "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        InMemoryManifestRepo manifests = new InMemoryManifestRepo();
        manifests.throwOnNextSave = true;
        CapturingClient client = new CapturingClient();
        client.next = new OpenAiCompatibleChatClient.ChatCompletion("answer", "qwen-plus", 1, 1, 1L, Map.of());
        DashScopeLlmGateway gateway = gateway(client, templates, logs, manifests);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.INTERNAL)))
            .isInstanceOfSatisfying(LlmProviderException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.WRITEBACK_FAILED));
        // No call-log row either — manifest failure aborts before the call log is written.
        assertThat(logs.records).isEmpty();
    }

    @Test
    void schemaValidationFailsWhenRequiredFieldMissing() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put(null, "MINUTES_SUMMARY",
            "render {{transcript}}",
            "{\"required\":[\"summary\",\"sections\"]}",
            "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        InMemoryManifestRepo manifests = new InMemoryManifestRepo();
        CapturingClient client = new CapturingClient();
        client.next = new OpenAiCompatibleChatClient.ChatCompletion(
            "{\"summary\":\"ok\"}",
            "qwen-plus",
            5,
            5,
            10L,
            Map.of()
        );
        DashScopeLlmGateway gateway = gateway(client, templates, logs, manifests);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.INTERNAL)))
            .isInstanceOf(LlmProviderException.class)
            .satisfies(ex -> assertThat(((LlmProviderException) ex).errorCode())
                .isEqualTo(ErrorCode.LLM_SCHEMA_INVALID));
    }

    @Test
    void providerTimeoutIsRecordedAsFailedCall() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put(null, "MINUTES_SUMMARY", "render {{transcript}}", "{}", "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        InMemoryManifestRepo manifests = new InMemoryManifestRepo();
        OpenAiCompatibleChatClient client = req -> {
            throw new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "timeout");
        };
        DashScopeLlmGateway gateway = gateway(client, templates, logs, manifests);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.INTERNAL)))
            .isInstanceOf(LlmProviderException.class);

        assertThat(logs.records).hasSize(1);
        assertThat(logs.records.get(0).status()).isEqualTo("FAILED");
        assertThat(logs.records.get(0).errorCode()).isEqualTo("LLM_PROVIDER_TIMEOUT");
        // No manifest on a failed call — no AI artifact was produced to reference.
        assertThat(manifests.records).isEmpty();
    }

    @Test
    void missingPromptTemplateRaisesProviderError() {
        DashScopeLlmGateway gateway = gateway(
            new CapturingClient(), new InMemoryTemplateRepo(), new InMemoryCallLogRepo(), new InMemoryManifestRepo()
        );

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.PUBLIC)))
            .isInstanceOf(LlmProviderException.class)
            .satisfies(ex -> assertThat(((LlmProviderException) ex).errorCode())
                .isEqualTo(ErrorCode.LLM_SCHEMA_INVALID));
    }

    private static LlmGateway.LlmRequest request(SecurityLevel level) {
        return new LlmGateway.LlmRequest(
            "tenant_01",
            "meeting_01",
            "task_01",
            "MINUTES_SUMMARY",
            "MINUTES_SUMMARY",
            level,
            Map.of("transcript", "hello"),
            null,
            "trace_01"
        );
    }

    private static DashScopeLlmGateway gateway(
        OpenAiCompatibleChatClient client,
        PromptTemplateRepository templates,
        LlmCallLogRepository logs,
        ArtifactManifestRepository manifests
    ) {
        return new DashScopeLlmGateway(
            client,
            templates,
            logs,
            manifests,
            new ObjectMapper(),
            "qwen-plus",
            EnumSet.of(SecurityLevel.CONFIDENTIAL, SecurityLevel.SECRET),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static final class CapturingClient implements OpenAiCompatibleChatClient {
        ChatCompletionRequest lastRequest;
        ChatCompletion next = new ChatCompletion("{}", "qwen-plus", 0, 0, 0L, Map.of());

        @Override
        public ChatCompletion chatComplete(ChatCompletionRequest request) {
            this.lastRequest = request;
            return next;
        }
    }

    private static final class InMemoryTemplateRepo implements PromptTemplateRepository {
        private final java.util.Map<String, PromptTemplate> store = new java.util.HashMap<>();

        void put(String tenantId, String taskName, String body, String jsonSchema, String status) {
            String key = key(tenantId, taskName);
            store.put(key, new PromptTemplate(
                "tpl_" + taskName + "_" + (tenantId == null ? "system" : tenantId),
                tenantId,
                taskName,
                "1.0.0",
                body,
                jsonSchema,
                status
            ));
        }

        @Override
        public Optional<PromptTemplate> findActiveByTaskName(String tenantId, String taskName) {
            return Optional.ofNullable(store.get(key(tenantId, taskName)));
        }

        private static String key(String tenantId, String taskName) {
            return (tenantId == null ? "system" : tenantId) + "::" + taskName;
        }
    }

    private static final class InMemoryCallLogRepo implements LlmCallLogRepository {
        private final List<LlmCallLogRecord> records = new ArrayList<>();

        @Override
        public String record(LlmCallLogRecord record) {
            records.add(record);
            return record.id();
        }
    }

    private static final class InMemoryManifestRepo implements ArtifactManifestRepository {
        private final List<ArtifactManifestRecord> records = new ArrayList<>();
        boolean throwOnNextSave = false;

        @Override
        public String save(ArtifactManifestRecord record) {
            if (throwOnNextSave) {
                throwOnNextSave = false;
                throw new RuntimeException("fake manifest persist failure");
            }
            records.add(record);
            return record.id();
        }
    }
}
