package com.meeting.api.client.person;

import java.util.List;

public interface PersonFacade {
    PersonDTO create(CreatePersonCommand command);

    List<PersonDTO> search(String tenantId, String q, int limit);
}
