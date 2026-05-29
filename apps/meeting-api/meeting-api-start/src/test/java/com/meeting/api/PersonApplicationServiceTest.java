package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.person.PersonApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonCreatedEvent;
import com.meeting.api.domain.person.PersonRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void createReturnsPersonDto() {
        InMemoryPersons people = new InMemoryPersons();
        PersonApplicationService service = new PersonApplicationService(people, TenantScopedTransaction.immediate(), CLOCK);

        var dto = service.create(command("李四", "lisi@example.com", false));

        assertThat(dto.personId()).startsWith("person_");
        assertThat(dto.displayName()).isEqualTo("李四");
        assertThat(dto.email()).isEqualTo("lisi@example.com");
        assertThat(dto.createdAt()).isEqualTo(OffsetDateTime.parse("2026-05-27T10:00:00Z"));
    }

    @Test
    void createPublishesPersonCreatedEventAndAuditEntry() {
        InMemoryPersons people = new InMemoryPersons();
        CapturingPublisher publisher = new CapturingPublisher();
        CapturingAudit audit = new CapturingAudit();
        PersonApplicationService service = new PersonApplicationService(
            people,
            TenantScopedTransaction.immediate(),
            publisher,
            audit,
            CLOCK
        );

        var dto = service.create(command("李四", "lisi@example.com", false));

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(PersonCreatedEvent.class);
        PersonCreatedEvent event = (PersonCreatedEvent) publisher.events.get(0);
        assertThat(event.tenantId()).isEqualTo("tenant_01");
        assertThat(event.personId()).isEqualTo(dto.personId());
        assertThat(event.displayName()).isEqualTo("李四");
        assertThat(event.email()).isEqualTo("lisi@example.com");
        assertThat(event.aggregateType()).isEqualTo("Person");
        assertThat(event.aggregateId()).isEqualTo(dto.personId());
        assertThat(event.payload()).containsEntry("personId", dto.personId())
            .containsEntry("displayName", "李四")
            .containsEntry("createdBy", "user_01");

        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.resourceType()).isEqualTo("PERSON");
        assertThat(entry.resourceId()).isEqualTo(dto.personId());
        assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.payload()).containsEntry("displayName", "李四")
            .containsEntry("email", "lisi@example.com");
        assertThat(entry.traceId()).isEqualTo("req_01");
    }

    @Test
    void duplicateDisplayNameReturnsMatchesWhenForceCreateFalse() {
        InMemoryPersons people = new InMemoryPersons();
        people.save(new Person("person_existing", "tenant_01", "李四", "old@example.com", null, "ACTIVE", now()));
        PersonApplicationService service = new PersonApplicationService(people, TenantScopedTransaction.immediate(), CLOCK);

        assertThatThrownBy(() -> service.create(command("李四", "new@example.com", false)))
            .isInstanceOf(PersonDuplicateException.class)
            .satisfies(ex -> {
                PersonDuplicateException duplicate = (PersonDuplicateException) ex;
                assertThat(duplicate.matches()).hasSize(1);
                assertThat(duplicate.matches().get(0).personId()).isEqualTo("person_existing");
            });
        assertThat(people.people).hasSize(1);
    }

    @Test
    void duplicateDisplayNameCanBeForced() {
        InMemoryPersons people = new InMemoryPersons();
        people.save(new Person("person_existing", "tenant_01", "李四", "old@example.com", null, "ACTIVE", now()));
        PersonApplicationService service = new PersonApplicationService(people, TenantScopedTransaction.immediate(), CLOCK);

        var dto = service.create(command("李四", "new@example.com", true));

        assertThat(dto.personId()).isNotEqualTo("person_existing");
        assertThat(people.people).hasSize(2);
    }

    @Test
    void blankDisplayNameUsesStableErrorCode() {
        PersonApplicationService service = new PersonApplicationService(new InMemoryPersons(), TenantScopedTransaction.immediate(), CLOCK);

        assertThatThrownBy(() -> service.create(command(" ", null, false)))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException app = (ApplicationException) ex;
                assertThat(app.errorCode()).isEqualTo(ErrorCode.PERSON_DISPLAY_NAME_REQUIRED);
                assertThat(app.httpStatus()).isEqualTo(422);
            });
    }

    @Test
    void searchListsTenantPersonsWhenQueryBlankAndFiltersWhenPresent() {
        InMemoryPersons people = new InMemoryPersons();
        people.save(new Person("person_1", "tenant_01", "Alice Wang", "alice@example.com", null, "ACTIVE", now()));
        people.save(new Person("person_2", "tenant_01", "Bob", "bob@example.com", null, "ACTIVE", now()));
        people.save(new Person("person_3", "tenant_other", "Alice Other", "alice.other@example.com", null, "ACTIVE", now()));
        PersonApplicationService service = new PersonApplicationService(people, TenantScopedTransaction.immediate(), CLOCK);

        assertThat(service.search("tenant_01", " ", 20))
            .extracting("personId")
            .containsExactly("person_1", "person_2");
        assertThat(service.search("tenant_01", "alice", 20))
            .extracting("personId")
            .containsExactly("person_1");
    }

    private static CreatePersonCommand command(String displayName, String email, boolean forceCreate) {
        return new CreatePersonCommand(
            "tenant_01",
            displayName,
            email,
            null,
            forceCreate,
            "user_01",
            "idem_01",
            "req_01",
            "trace_01"
        );
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.parse("2026-05-27T10:00:00Z");
    }

    private static final class InMemoryPersons implements PersonRepository {
        private final List<Person> people = new ArrayList<>();

        @Override
        public Person save(Person person) {
            people.add(person);
            return person;
        }

        @Override
        public Optional<Person> findById(String tenantId, String personId) {
            return people.stream()
                .filter(p -> tenantId.equals(p.tenantId()) && personId.equals(p.id()))
                .findFirst();
        }

        @Override
        public List<Person> findByDisplayName(String tenantId, String displayName) {
            return people.stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .filter(Person::isActive)
                .filter(p -> displayName.equals(p.displayName()))
                .sorted(Comparator.comparing(Person::createdAt))
                .toList();
        }

        @Override
        public List<Person> searchByQuery(String tenantId, String q, int limit) {
            String query = q == null ? "" : q.toLowerCase();
            return people.stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .filter(Person::isActive)
                .filter(p -> query.isBlank()
                    || p.displayName().toLowerCase().contains(query)
                    || (p.email() != null && p.email().toLowerCase().contains(query)))
                .sorted(Comparator.comparing(Person::displayName))
                .limit(limit)
                .toList();
        }
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    private static final class CapturingAudit implements AuditEventLogger {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void log(AuditEntry entry) {
            entries.add(entry);
        }
    }
}
