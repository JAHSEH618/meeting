package com.meeting.api.client.person;

import java.util.List;

public class PersonDuplicateException extends RuntimeException {
    private final List<PersonDTO> matches;

    public PersonDuplicateException(List<PersonDTO> matches) {
        super("PERSON_DUPLICATE");
        this.matches = List.copyOf(matches);
    }

    public List<PersonDTO> matches() {
        return matches;
    }
}
