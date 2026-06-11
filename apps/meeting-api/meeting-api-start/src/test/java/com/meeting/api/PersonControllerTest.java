package com.meeting.api;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.adapter.meeting.TenantContextMissingException;
import com.meeting.api.adapter.person.PersonController;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonFacade;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonControllerTest {
    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void getReturnsJavaOwnedPersonDisplayNameForTenant() {
        StubPersonFacade facade = new StubPersonFacade();
        PersonController controller = new PersonController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.get("person_01", "req_01", "trace_01");

        assertThat(facade.lastGetTenantId).isEqualTo("tenant_01");
        assertThat(facade.lastGetPersonId).isEqualTo("person_01");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ApiResponse<PersonDTO> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.data().personId()).isEqualTo("person_01");
        assertThat(body.data().displayName()).isEqualTo("李四");
        assertThat(body.requestId()).isEqualTo("req_01");
        assertThat(body.traceId()).isEqualTo("trace_01");
    }

    @Test
    void getReturnsEnvelope404WhenPersonIsNotInTenant() {
        StubPersonFacade facade = new StubPersonFacade();
        facade.getResult = Optional.empty();
        PersonController controller = new PersonController(facade);
        TenantContextHolder.set("tenant_01", "user_01", "req_01");

        var response = controller.get("missing", "req_01", "trace_01");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.PERSON_NOT_FOUND);
    }

    @Test
    void getFailsClosedWhenTenantContextIsMissing() {
        PersonController controller = new PersonController(new StubPersonFacade());

        assertThatThrownBy(() -> controller.get("person_01", "req_01", "trace_01"))
            .isInstanceOf(TenantContextMissingException.class);
    }

    private static final class StubPersonFacade implements PersonFacade {
        private final PersonDTO person = new PersonDTO(
            "person_01",
            "李四",
            "li@example.com",
            null,
            OffsetDateTime.parse("2026-06-02T10:00:00Z")
        );
        private String lastGetTenantId;
        private String lastGetPersonId;
        private Optional<PersonDTO> getResult = Optional.of(person);

        @Override
        public PersonDTO create(CreatePersonCommand command) {
            return person;
        }

        @Override
        public Optional<PersonDTO> get(String tenantId, String personId) {
            lastGetTenantId = tenantId;
            lastGetPersonId = personId;
            return getResult;
        }

        @Override
        public List<PersonDTO> search(String tenantId, String q, int limit) {
            return List.of(person);
        }
    }
}
