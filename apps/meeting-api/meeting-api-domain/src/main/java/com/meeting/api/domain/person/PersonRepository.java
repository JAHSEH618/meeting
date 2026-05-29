package com.meeting.api.domain.person;

import java.util.List;
import java.util.Optional;

public interface PersonRepository {
    Person save(Person person);

    Optional<Person> findById(String tenantId, String personId);

    List<Person> findByDisplayName(String tenantId, String displayName);

    List<Person> searchByQuery(String tenantId, String q, int limit);
}
