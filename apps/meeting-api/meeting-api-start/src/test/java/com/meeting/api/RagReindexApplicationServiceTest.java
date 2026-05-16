package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.rag.ChunkingApplicationService;
import com.meeting.api.app.rag.ChunkingApplicationService.ChunkingResult;
import com.meeting.api.app.rag.RagReindexApplicationService;
import com.meeting.api.client.rag.RagReindexResultDTO;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagReindexApplicationServiceTest {

    @Test
    void reindexMeetingDelegatesToChunkingAndMapsResult() {
        var stub = new StubChunkingApplicationService(
            (tenantId, meetingId) -> new ChunkingResult(3, List.of("ck_1", "ck_2", "ck_3", "ck_4")),
            (tenantId, documentId) -> { throw new AssertionError("document path should not be hit"); }
        );
        var service = new RagReindexApplicationService(
            TenantScopedTransaction.immediate(),
            stub
        );

        RagReindexResultDTO result = service.reindexMeeting("tenant_01", "mtg_42", "user_07");

        assertThat(result.staleCount()).isEqualTo(3);
        assertThat(result.newChunkIds()).containsExactly("ck_1", "ck_2", "ck_3", "ck_4");
        assertThat(stub.meetingCalls).containsExactly("tenant_01:mtg_42");
        assertThat(stub.documentCalls).isEmpty();
    }

    @Test
    void reindexDocumentDelegatesToChunkingAndMapsResult() {
        var stub = new StubChunkingApplicationService(
            (tenantId, meetingId) -> { throw new AssertionError("meeting path should not be hit"); },
            (tenantId, documentId) -> new ChunkingResult(0, List.of("ck_doc_1"))
        );
        var service = new RagReindexApplicationService(
            TenantScopedTransaction.immediate(),
            stub
        );

        RagReindexResultDTO result = service.reindexDocument("tenant_01", "doc_42", "user_07");

        assertThat(result.staleCount()).isEqualTo(0);
        assertThat(result.newChunkIds()).containsExactly("ck_doc_1");
        assertThat(stub.documentCalls).containsExactly("tenant_01:doc_42");
        assertThat(stub.meetingCalls).isEmpty();
    }

    @Test
    void emptyChunkingResultPropagates() {
        var stub = new StubChunkingApplicationService(
            (tenantId, meetingId) -> new ChunkingResult(0, List.of()),
            (tenantId, documentId) -> new ChunkingResult(0, List.of())
        );
        var service = new RagReindexApplicationService(
            TenantScopedTransaction.immediate(),
            stub
        );

        RagReindexResultDTO meetingResult = service.reindexMeeting("tenant_01", "mtg_empty", null);
        assertThat(meetingResult.staleCount()).isEqualTo(0);
        assertThat(meetingResult.newChunkIds()).isEmpty();
    }

    @Test
    void chunkingExceptionsAreNotSwallowed() {
        var stub = new StubChunkingApplicationService(
            (tenantId, meetingId) -> {
                throw new IllegalArgumentException("meeting not found: " + meetingId);
            },
            (tenantId, documentId) -> { throw new AssertionError(); }
        );
        var service = new RagReindexApplicationService(
            TenantScopedTransaction.immediate(),
            stub
        );

        assertThatThrownBy(() -> service.reindexMeeting("tenant_01", "mtg_ghost", "user_07"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("meeting not found");
    }

    /**
     * Stub that records the {@code (tenantId, ownerId)} of each call and
     * delegates to lambdas for the actual result. Extends the real
     * {@link ChunkingApplicationService} but every collaborator is null
     * because we override the only two methods the service under test
     * touches.
     */
    private static final class StubChunkingApplicationService extends ChunkingApplicationService {
        final List<String> meetingCalls = new java.util.ArrayList<>();
        final List<String> documentCalls = new java.util.ArrayList<>();
        private final BiFunction<String, String, ChunkingResult> meetingFn;
        private final BiFunction<String, String, ChunkingResult> documentFn;

        StubChunkingApplicationService(
            BiFunction<String, String, ChunkingResult> meetingFn,
            BiFunction<String, String, ChunkingResult> documentFn
        ) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.meetingFn = meetingFn;
            this.documentFn = documentFn;
        }

        @Override
        public ChunkingResult rebuildForMeeting(String tenantId, String meetingId) {
            meetingCalls.add(tenantId + ":" + meetingId);
            return meetingFn.apply(tenantId, meetingId);
        }

        @Override
        public ChunkingResult rebuildForDocument(String tenantId, String documentId) {
            documentCalls.add(tenantId + ":" + documentId);
            return documentFn.apply(tenantId, documentId);
        }
    }
}
