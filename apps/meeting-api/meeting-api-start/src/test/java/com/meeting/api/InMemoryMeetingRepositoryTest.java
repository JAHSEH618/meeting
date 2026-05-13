package com.meeting.api;

import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.infrastructure.meeting.InMemoryMeetingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMeetingRepositoryTest {

    private final InMemoryMeetingRepository repository = new InMemoryMeetingRepository();

    @Test
    void findByIdRequiresMatchingTenant() {
        Meeting meeting = Meeting.create("m_01", "tenant_01", "Weekly", SecurityLevel.INTERNAL, "zh", List.of(), "user_01");

        repository.save(meeting);

        assertThat(repository.findById("tenant_01", "m_01")).contains(meeting);
        assertThat(repository.findById("tenant_02", "m_01")).isEmpty();
    }

    @Test
    void listReturnsOnlyRequestedTenantMeetings() {
        Meeting first = Meeting.create("m_01", "tenant_01", "Weekly", SecurityLevel.INTERNAL, "zh", List.of(), "user_01");
        Meeting second = Meeting.create("m_02", "tenant_02", "Retro", SecurityLevel.PUBLIC, "en", List.of(), "user_02");

        repository.save(first);
        repository.save(second);

        assertThat(repository.findByTenantId("tenant_01")).containsExactly(first);
        assertThat(repository.findByTenantId("tenant_02")).containsExactly(second);
    }
}
