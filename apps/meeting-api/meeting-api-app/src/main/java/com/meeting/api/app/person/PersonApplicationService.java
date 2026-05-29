package com.meeting.api.app.person;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.client.person.PersonFacade;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonCreatedEvent;
import com.meeting.api.domain.person.PersonRepository;
import com.meeting.api.domain.task.MessagePublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PersonApplicationService implements PersonFacade {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final String RESOURCE_TYPE_PERSON = "PERSON";

    private final PersonRepository personRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final MessagePublisher messagePublisher;
    private final AuditEventLogger auditLogger;
    private final Clock clock;

    @Autowired
    public PersonApplicationService(
        PersonRepository personRepository,
        TenantScopedTransaction tenantScopedTransaction,
        MessagePublisher messagePublisher,
        AuditEventLogger auditLogger
    ) {
        this(personRepository, tenantScopedTransaction, messagePublisher, auditLogger, Clock.systemUTC());
    }

    public PersonApplicationService(
        PersonRepository personRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this(personRepository, tenantScopedTransaction, null, null, clock);
    }

    public PersonApplicationService(
        PersonRepository personRepository,
        TenantScopedTransaction tenantScopedTransaction,
        MessagePublisher messagePublisher,
        AuditEventLogger auditLogger,
        Clock clock
    ) {
        this.personRepository = personRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.messagePublisher = messagePublisher;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public PersonDTO create(CreatePersonCommand command) {
        String displayName = normalizeDisplayName(command.displayName());
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            if (!command.forceCreate()) {
                List<PersonDTO> matches = personRepository.findByDisplayName(command.tenantId(), displayName)
                    .stream()
                    .map(PersonApplicationService::toDto)
                    .toList();
                if (!matches.isEmpty()) {
                    throw new PersonDuplicateException(matches);
                }
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            Person saved = personRepository.save(new Person(
                "person_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                displayName,
                blankToNull(command.email()),
                blankToNull(command.externalId()),
                "ACTIVE",
                now
            ));
            publishPersonCreated(saved, command.requestedBy());
            auditPersonCreated(saved, command.requestedBy(), command.requestId());
            return toDto(saved);
        });
    }

    @Override
    public List<PersonDTO> search(String tenantId, String q, int limit) {
        int resolvedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String query = q == null ? "" : q.trim();
        return tenantScopedTransaction.execute(tenantId, null, null, () ->
            personRepository.searchByQuery(tenantId, query, resolvedLimit)
                .stream()
                .map(PersonApplicationService::toDto)
                .toList()
        );
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ApplicationException(
                ErrorCode.PERSON_DISPLAY_NAME_REQUIRED,
                422,
                "displayName must not be blank",
                false
            );
        }
        return displayName.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static PersonDTO toDto(Person person) {
        return new PersonDTO(
            person.id(),
            person.displayName(),
            person.email(),
            person.externalRef(),
            person.createdAt()
        );
    }

    private void publishPersonCreated(Person person, String createdBy) {
        if (messagePublisher == null) {
            return;
        }
        messagePublisher.publish(new PersonCreatedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            person.tenantId(),
            person.id(),
            person.displayName(),
            person.email(),
            person.externalRef(),
            createdBy,
            0,
            person.createdAt()
        ));
    }

    private void auditPersonCreated(Person person, String actorUserId, String requestId) {
        if (auditLogger == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("displayName", person.displayName());
        if (person.email() != null) {
            payload.put("email", person.email());
        }
        if (person.externalRef() != null) {
            payload.put("externalId", person.externalRef());
        }
        auditLogger.log(AuditEntry.success(
            person.tenantId(),
            actorUserId,
            AuditAction.CREATE,
            RESOURCE_TYPE_PERSON,
            person.id(),
            payload,
            requestId
        ));
    }
}
