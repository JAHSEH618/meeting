package com.meeting.api.client.person;

import java.util.List;
import java.util.Optional;

public interface PersonFacade {
    PersonDTO create(CreatePersonCommand command);

    Optional<PersonDTO> get(String tenantId, String personId);

    List<PersonDTO> search(String tenantId, String q, int limit);
}
