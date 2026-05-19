package com.meeting.api.client.meeting;

import java.util.List;

public record GlossaryTermDTO(
    String term,
    String definition,
    List<String> aliases
) {
}
