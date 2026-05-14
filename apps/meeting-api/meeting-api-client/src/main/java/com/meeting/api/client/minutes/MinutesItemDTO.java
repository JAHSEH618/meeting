package com.meeting.api.client.minutes;

import java.util.List;

public record MinutesItemDTO(
    String text,
    List<MinutesEvidenceDTO> evidence
) {
}
