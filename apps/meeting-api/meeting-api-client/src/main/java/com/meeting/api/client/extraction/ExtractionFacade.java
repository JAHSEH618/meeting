package com.meeting.api.client.extraction;

import java.util.List;
import java.util.Optional;

public interface ExtractionFacade {
    List<ActionItemDTO> listActionItems(String tenantId, String meetingId);

    List<DecisionDTO> listDecisions(String tenantId, String meetingId);

    List<RiskDTO> listRisks(String tenantId, String meetingId);

    Optional<ActionItemDTO> updateAcceptance(UpdateAcceptanceCommand command);
}
