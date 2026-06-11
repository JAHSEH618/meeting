package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.meeting.MeetingGlossaryApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.meeting.GlossaryTermDTO;
import com.meeting.api.client.meeting.MeetingGlossaryDTO;
import com.meeting.api.client.meeting.UpdateMeetingGlossaryCommand;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingGlossaryRepository;
import com.meeting.api.domain.meeting.MeetingGlossaryUpdatedEvent;
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
 * Workstation D2 — MeetingGlossaryApplicationService unit tests.
 * Covers todo-final.md B5.2: overwrite update, length limits, outbox landing.
 */
class MeetingGlossaryApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-19T04:00:00Z");

    @Test
    void updateOverwritesPreviousTermsAndPublishesEvent() {
        Fixture f = fixture();
        f.meetings.put("m_01", meeting());

        f.service.update(cmd(List.of(termOf("KPI", "Key Performance Indicator"))));
        MeetingGlossaryDTO snapshot = f.service.update(cmd(List.of(
            termOf("ROI", "Return on Investment"),
            termOf("OKR", null)
        )));

        assertThat(snapshot.terms())
            .extracting(GlossaryTermDTO::term)
            .containsExactly("ROI", "OKR");
        assertThat(f.publisher.events).hasSize(2);
        assertThat(f.publisher.events.get(1)).isInstanceOf(MeetingGlossaryUpdatedEvent.class);
        assertThat(((MeetingGlossaryUpdatedEvent) f.publisher.events.get(1)).termCount()).isEqualTo(2);
    }

    @Test
    void updateRejectsTooManyTerms() {
        Fixture f = fixture();
        f.meetings.put("m_01", meeting());
        List<GlossaryTermDTO> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(termOf("t" + i, null));
        }

        assertThatThrownBy(() -> f.service.update(cmd(tooMany)))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updateRejectsTermLengthOverLimit() {
        Fixture f = fixture();
        f.meetings.put("m_01", meeting());

        String longTerm = "x".repeat(65);
        assertThatThrownBy(() -> f.service.update(cmd(List.of(termOf(longTerm, null)))))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updateRejectsDuplicateTermsCaseInsensitively() {
        Fixture f = fixture();
        f.meetings.put("m_01", meeting());

        assertThatThrownBy(() -> f.service.update(cmd(List.of(termOf("KPI", null), termOf("kpi", null)))))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updateUnknownMeetingRaisesMeetingNotFound() {
        Fixture f = fixture();

        assertThatThrownBy(() -> f.service.update(cmd(List.of(termOf("KPI", null)))))
            .isInstanceOf(ApplicationException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    void getReturnsEmptyTermsWhenNoneStored() {
        Fixture f = fixture();
        f.meetings.put("m_01", meeting());

        MeetingGlossaryDTO dto = f.service.get("tenant_01", "m_01").orElseThrow();

        assertThat(dto.terms()).isEmpty();
    }

    private static GlossaryTermDTO termOf(String term, String definition) {
        return new GlossaryTermDTO(term, definition, List.of());
    }

    private static UpdateMeetingGlossaryCommand cmd(List<GlossaryTermDTO> terms) {
        return new UpdateMeetingGlossaryCommand(
            "tenant_01", "m_01", terms, "user_01",
            "idem_01", "req_01", "trace_01"
        );
    }

    private static Fixture fixture() {
        InMemoryMeetingRepo meetings = new InMemoryMeetingRepo();
        InMemoryGlossaryRepo glossary = new InMemoryGlossaryRepo();
        CapturingPublisher publisher = new CapturingPublisher();
        MeetingGlossaryApplicationService service = new MeetingGlossaryApplicationService(
            meetings, glossary, publisher,
            TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
        return new Fixture(meetings.byId, service, publisher);
    }

    private static Meeting meeting() {
        return new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Q2 Review")
            .status(MeetingStatus.CREATED).language("zh")
            .transcriptVersion(0).minutesVersion(0).createdAt(NOW)
            .createdBy("user_01").participants(List.of())
            .build();
    }

    private record Fixture(Map<String, Meeting> meetings, MeetingGlossaryApplicationService service, CapturingPublisher publisher) {
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

    private static final class InMemoryGlossaryRepo implements MeetingGlossaryRepository {
        private final Map<String, List<GlossaryTerm>> store = new HashMap<>();

        @Override
        public Optional<List<GlossaryTerm>> findByMeetingId(String tenantId, String meetingId) {
            return Optional.ofNullable(store.get(key(tenantId, meetingId)));
        }

        @Override
        public OffsetDateTime replace(String tenantId, String meetingId, List<GlossaryTerm> terms, OffsetDateTime now) {
            store.put(key(tenantId, meetingId), terms);
            return now;
        }

        private static String key(String t, String m) {
            return t + ":" + m;
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
