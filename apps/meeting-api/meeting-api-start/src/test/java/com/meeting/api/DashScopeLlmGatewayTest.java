package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.llm.LlmCallLogRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.llm.LlmProviderException;
import com.meeting.api.domain.llm.PromptTemplateRepository;
import com.meeting.api.domain.llm.SecurityLevelBlockedException;
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
        DashScopeLlmGateway gateway = gateway(new CapturingClient(), templates, logs);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.CONFIDENTIAL)))
            .isInstanceOf(SecurityLevelBlockedException.class)
            .satisfies(ex -> {
                SecurityLevelBlockedException blocked = (SecurityLevelBlockedException) ex;
                assertThat(blocked.securityLevel()).isEqualTo(SecurityLevel.CONFIDENTIAL);
                assertThat(blocked.blockedCapability()).isEqualTo("MINUTES_SUMMARY");
            });
        assertThat(logs.records).isEmpty();
    }

    @Test
    void internalSecurityLevelRendersTemplateAndCallsClient() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put("tenant_01", "MINUTES_SUMMARY",
            "Summarize meeting {{meetingTitle}}: {{transcript}}",
            "{\"required\":[\"summary\",\"sections\"]}",
            "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        CapturingClient client = new CapturingClient();
        client.next = new OpenAiCompatibleChatClient.ChatCompletion(
            "{\"summary\":\"ok\",\"sections\":[]}",
            "qwen-plus-1.0",
            12,
            34,
            42L,
            Map.of()
        );
        DashScopeLlmGateway gateway = gateway(client, templates, logs);

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

        assertThat(logs.records).hasSize(1);
        LlmCallLogRepository.LlmCallLogRecord record = logs.records.get(0);
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.inputHash()).hasSize(64);
        assertThat(record.outputHash()).hasSize(64);
        assertThat(record.tokenTotal()).isEqualTo(46);
        assertThat(record.promptTemplateId()).isEqualTo("tpl_MINUTES_SUMMARY_tenant_01");
        assertThat(record.actualModelVersion()).isEqualTo("qwen-plus-1.0");
    }

    @Test
    void schemaValidationFailsWhenRequiredFieldMissing() {
        InMemoryTemplateRepo templates = new InMemoryTemplateRepo();
        templates.put(null, "MINUTES_SUMMARY",
            "render {{transcript}}",
            "{\"required\":[\"summary\",\"sections\"]}",
            "ACTIVE");
        InMemoryCallLogRepo logs = new InMemoryCallLogRepo();
        CapturingClient client = new CapturingClient();
        client.next = new OpenAiCompatibleChatClient.ChatCompletion(
            "{\"summary\":\"ok\"}",
            "qwen-plus",
            5,
            5,
            10L,
            Map.of()
        );
        DashScopeLlmGateway gateway = gateway(client, templates, logs);

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
        OpenAiCompatibleChatClient client = req -> {
            throw new LlmProviderException(ErrorCode.LLM_PROVIDER_TIMEOUT, "timeout");
        };
        DashScopeLlmGateway gateway = gateway(client, templates, logs);

        assertThatThrownBy(() -> gateway.complete(request(SecurityLevel.INTERNAL)))
            .isInstanceOf(LlmProviderException.class);

        assertThat(logs.records).hasSize(1);
        assertThat(logs.records.get(0).status()).isEqualTo("FAILED");
        assertThat(logs.records.get(0).errorCode()).isEqualTo("LLM_PROVIDER_TIMEOUT");
    }

    @Test
    void missingPromptTemplateRaisesProviderError() {
        DashScopeLlmGateway gateway = gateway(new CapturingClient(), new InMemoryTemplateRepo(), new InMemoryCallLogRepo());

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
        LlmCallLogRepository logs
    ) {
        return new DashScopeLlmGateway(
            client,
            templates,
            logs,
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
}
