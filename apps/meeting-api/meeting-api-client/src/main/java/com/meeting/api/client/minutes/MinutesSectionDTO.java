package com.meeting.api.client.minutes;

import java.util.List;

public record MinutesSectionDTO(
    String type,
    String title,
    List<MinutesItemDTO> items
) {
}
