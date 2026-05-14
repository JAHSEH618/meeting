package com.meeting.api.client.extraction;

public record ExtractionSummary(
    int actionItemsCreated,
    int decisionsCreated,
    int risksCreated
) {
}
