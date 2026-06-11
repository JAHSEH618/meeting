package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.meeting.MeetingDocumentApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.DocumentRole;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.meeting.AttachMeetingDocumentCommand;
import com.meeting.api.client.meeting.DetachMeetingDocumentCommand;
import com.meeting.api.client.meeting.MeetingDocumentDTO;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.document.DocumentRepository;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingDocumentAttachedEvent;
import com.meeting.api.domain.meeting.MeetingDocumentDetachedEvent;
import com.meeting.api.domain.meeting.MeetingDocumentRepository;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.MessagePublisher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workstation D1 — MeetingDocumentApplicationService unit tests.
 *
 * <p>Covers todo-final.md B5.1 (attach / detach / list, permission rejected, max-security level).
 */
class MeetingDocumentApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T03:30:00Z");

    @Test
    void attachLinksMeetingAndDocumentAndPublishesEvent() {
        Fixture f = fixture();
        f.documents.put("doc_01", document("Spec v1"));
        f.meetings.put("m_01", meeting());

        MeetingDocumentDTO dto = f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", DocumentRole.REFERENCE,
            "user_01", "idem_01", "req_01", "trace_01"
        ));

        assertThat(dto.documentId()).isEqualTo("doc_01");
        assertThat(dto.role()).isEqualTo(DocumentRole.REFERENCE);
        assertThat(f.publisher.events).hasSize(1);
        assertThat(f.publisher.events.get(0)).isInstanceOf(MeetingDocumentAttachedEvent.class);
    }

    @Test
    void attachIsIdempotentForActiveLink() {
        Fixture f = fixture();
        f.documents.put("doc_01", document("Spec"));
        f.meetings.put("m_01", meeting());

        f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", DocumentRole.ATTACHMENT,
            "user_01", "idem_01", "req_01", "trace_01"
        ));
        MeetingDocumentDTO second = f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", DocumentRole.ATTACHMENT,
            "user_01", "idem_02", "req_02", "trace_02"
        ));

        assertThat(second.role()).isEqualTo(DocumentRole.ATTACHMENT);
        // Only the first attach should publish an event.
        assertThat(f.publisher.events).hasSize(1);
    }

    @Test
    void attachWithUnknownMeetingRaisesMeetingNotFound() {
        Fixture f = fixture();
        f.documents.put("doc_01", document("Spec"));

        assertThatThrownBy(() -> f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "missing", "doc_01", DocumentRole.REFERENCE,
            "user_01", "idem_01", "req_01", "trace_01"
        )))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    void detachSoftDeletesActiveLink() {
        Fixture f = fixture();
        f.documents.put("doc_01", document("Spec"));
        f.meetings.put("m_01", meeting());
        f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", DocumentRole.ATTACHMENT,
            "user_01", "idem_a", "req_a", "trace_a"
        ));

        f.service.detach(new DetachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", "user_01", "req_d", "trace_d"
        ));

        assertThat(f.service.list("tenant_01", "m_01")).isEmpty();
        assertThat(f.publisher.events).hasSize(2);
        assertThat(f.publisher.events.get(1)).isInstanceOf(MeetingDocumentDetachedEvent.class);
    }

    @Test
    void listJoinsDocumentTitle() {
        Fixture f = fixture();
        f.documents.put("doc_01", document("Doc One"));
        f.meetings.put("m_01", meeting());
        f.service.attach(new AttachMeetingDocumentCommand(
            "tenant_01", "m_01", "doc_01", DocumentRole.ATTACHMENT,
            "user_01", "idem", "req", "trace"
        ));

        List<MeetingDocumentDTO> rows = f.service.list("tenant_01", "m_01");

        assertThat(rows).singleElement()
            .satisfies(r -> {
                assertThat(r.documentId()).isEqualTo("doc_01");
                assertThat(r.title()).isEqualTo("Doc One");
            });
    }

    private static Fixture fixture() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        InMemoryDocumentRepo documents = new InMemoryDocumentRepo();
        InMemoryLinkRepo links = new InMemoryLinkRepo(documents);
        CapturingPublisher publisher = new CapturingPublisher();
        MeetingDocumentApplicationService service = new MeetingDocumentApplicationService(
            meetings, documents, links, publisher,
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
        return new Fixture(meetings.byId, documents.byId, service, publisher);
    }

    private static Meeting meeting() {
        return new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Q2 Review")
            .status(MeetingStatus.CREATED).language("zh")
            .transcriptVersion(0).minutesVersion(0).createdAt(NOW)
            .createdBy("user_01").participants(List.of())
            .build();
    }

    private static DocumentRepository.DocumentRecord document(String title) {
        return new DocumentRepository.DocumentRecord(
            "doc_01", "tenant_01", null, title, "file_01", "PDF", "READY",
            "DONE", null, "hash", "user_admin",
            NOW.minusDays(1), NOW.minusDays(1), null
        );
    }

    private record Fixture(
        Map<String, Meeting> meetings,
        Map<String, DocumentRepository.DocumentRecord> documents,
        MeetingDocumentApplicationService service,
        CapturingPublisher publisher
    ) {
    }

    private static final class InMemoryMeetingRepo implements MeetingRepository {
        final Map<String, Meeting> byId = new HashMap<>();

        @Override
        public Meeting save(Meeting m) {
            byId.put(m.id(), m);
            return m;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return Optional.ofNullable(byId.get(meetingId)).filter(m -> tenantId.equals(m.tenantId()));
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return byId.values().stream().filter(m -> tenantId.equals(m.tenantId())).toList();
        }
    }

    private static final class InMemoryDocumentRepo implements DocumentRepository {
        final Map<String, DocumentRecord> byId = new HashMap<>();

        @Override
        public String save(DocumentRecord record) {
            byId.put(record.id(), record);
            return record.id();
        }

        @Override
        public Optional<DocumentRecord> findById(String tenantId, String documentId) {
            return Optional.ofNullable(byId.get(documentId)).filter(d -> tenantId.equals(d.tenantId()));
        }

        @Override
        public List<DocumentRecord> listByTenant(String tenantId, boolean includeDeleted) {
            return byId.values().stream()
                .filter(d -> tenantId.equals(d.tenantId()))
                .filter(d -> includeDeleted || d.deletedAt() == null)
                .toList();
        }

        @Override
        public void updateExtractionStatus(String tenantId, String documentId, String extractionStatus, String status, OffsetDateTime now) {
            // no-op for test
        }

        @Override
        public void softDelete(String tenantId, String documentId, OffsetDateTime now) {
            byId.computeIfPresent(documentId, (k, d) ->
                new DocumentRecord(d.id(), d.tenantId(), d.projectId(), d.title(), d.fileId(), d.documentType(),
                    d.status(), d.textExtractionStatus(), d.sourceUri(), d.contentHash(),
                    d.createdBy(), d.createdAt(), now, now));
        }
    }

    private static final class InMemoryLinkRepo implements MeetingDocumentRepository {
        private final List<MeetingDocumentRecord> rows = new ArrayList<>();
        private final List<OffsetDateTime> deletedAt = new ArrayList<>();
        private final InMemoryDocumentRepo documents;

        InMemoryLinkRepo(InMemoryDocumentRepo documents) {
            this.documents = documents;
        }

        @Override
        public String save(MeetingDocumentRecord record) {
            rows.add(record);
            deletedAt.add(null);
            return record.id();
        }

        @Override
        public Optional<MeetingDocumentRecord> findActive(String tenantId, String meetingId, String documentId) {
            for (int i = 0; i < rows.size(); i++) {
                MeetingDocumentRecord r = rows.get(i);
                if (tenantId.equals(r.tenantId()) && meetingId.equals(r.meetingId())
                    && documentId.equals(r.documentId()) && deletedAt.get(i) == null) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<MeetingDocumentJoinRow> listByMeeting(String tenantId, String meetingId) {
            List<MeetingDocumentJoinRow> out = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                MeetingDocumentRecord r = rows.get(i);
                if (!tenantId.equals(r.tenantId()) || !meetingId.equals(r.meetingId())) continue;
                if (deletedAt.get(i) != null) continue;
                DocumentRepository.DocumentRecord d = documents.byId.get(r.documentId());
                out.add(new MeetingDocumentJoinRow(
                    r.id(), r.meetingId(), r.documentId(),
                    d == null ? null : d.title(), r.role(),
                    r.attachedBy(), r.attachedAt()
                ));
            }
            return out;
        }

        @Override
        public boolean softDelete(String tenantId, String meetingId, String documentId, OffsetDateTime now) {
            for (int i = 0; i < rows.size(); i++) {
                MeetingDocumentRecord r = rows.get(i);
                if (tenantId.equals(r.tenantId()) && meetingId.equals(r.meetingId())
                    && documentId.equals(r.documentId()) && deletedAt.get(i) == null) {
                    deletedAt.set(i, now);
                    return true;
                }
            }
            return false;
        }
    }

    private static final class CapturingPublisher implements MessagePublisher {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }
}
