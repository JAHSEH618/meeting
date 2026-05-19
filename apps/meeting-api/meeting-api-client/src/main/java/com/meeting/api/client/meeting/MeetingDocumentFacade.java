package com.meeting.api.client.meeting;

import java.util.List;

public interface MeetingDocumentFacade {
    MeetingDocumentDTO attach(AttachMeetingDocumentCommand command);

    void detach(DetachMeetingDocumentCommand command);

    List<MeetingDocumentDTO> list(String tenantId, String meetingId);
}
