package com.meeting.api.app.extraction;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.extraction.ActionItemDTO;
import com.meeting.api.client.extraction.DecisionDTO;
import com.meeting.api.client.extraction.EvidenceDTO;
import com.meeting.api.client.extraction.ExtractionFacade;
import com.meeting.api.client.extraction.RiskDTO;
import com.meeting.api.client.extraction.UpdateAcceptanceCommand;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ExtractionQueryService implements ExtractionFacade {
    private final ActionItemRepository actionItemRepository;
    private final DecisionRepository decisionRepository;
    private final RiskRepository riskRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public ExtractionQueryService(
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(actionItemRepository, decisionRepository, riskRepository, tenantScopedTransaction, Clock.systemUTC());
    }

    public ExtractionQueryService(
        ActionItemRepository actionItemRepository,
        DecisionRepository decisionRepository,
        RiskRepository riskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.actionItemRepository = actionItemRepository;
        this.decisionRepository = decisionRepository;
        this.riskRepository = riskRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    @Override
    public List<ActionItemDTO> listActionItems(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> actionItemRepository.findByMeeting(tenantId, meetingId).stream()
                .map(ExtractionQueryService::toDto)
                .toList());
    }

    @Override
    public List<DecisionDTO> listDecisions(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> decisionRepository.findByMeeting(tenantId, meetingId).stream()
                .map(ExtractionQueryService::toDto)
                .toList());
    }

    @Override
    public List<RiskDTO> listRisks(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> riskRepository.findByMeeting(tenantId, meetingId).stream()
                .map(ExtractionQueryService::toDto)
                .toList());
    }

    @Override
    public Optional<ActionItemDTO> updateAcceptance(UpdateAcceptanceCommand command) {
        UpdateAcceptanceCommand.ItemKind kind = UpdateAcceptanceCommand.ItemKind.valueOf(command.itemKind());
        return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            switch (kind) {
                case ACTION_ITEM -> actionItemRepository.markAcceptance(command.tenantId(), command.itemId(), command.acceptanceStatus(), command.requestedBy(), now);
                case DECISION -> decisionRepository.markAcceptance(command.tenantId(), command.itemId(), command.acceptanceStatus(), command.requestedBy(), now);
                case RISK -> riskRepository.markAcceptance(command.tenantId(), command.itemId(), command.acceptanceStatus(), command.requestedBy(), now);
            }
            return Optional.empty();
        });
    }

    private static ActionItemDTO toDto(ActionItemRepository.ActionItemRecord r) {
        return new ActionItemDTO(
            r.id(), r.tenantId(), r.meetingId(), r.origin(), r.title(), r.description(),
            r.ownerPersonId(), r.ownerRawText(), r.priority(), r.status(), r.acceptanceStatus(),
            r.sourceTranscriptVersion(), r.staleStatus(),
            r.evidence().stream().map(ExtractionQueryService::toEvidenceDto).toList(),
            r.createdAt(), r.updatedAt()
        );
    }

    private static DecisionDTO toDto(DecisionRepository.DecisionRecord r) {
        return new DecisionDTO(
            r.id(), r.tenantId(), r.meetingId(), r.title(), r.description(),
            r.status(), r.acceptanceStatus(), r.sourceTranscriptVersion(), r.staleStatus(),
            r.evidence().stream().map(ExtractionQueryService::toEvidenceDto).toList(),
            r.createdAt(), r.updatedAt()
        );
    }

    private static RiskDTO toDto(RiskRepository.RiskRecord r) {
        return new RiskDTO(
            r.id(), r.tenantId(), r.meetingId(), r.title(), r.description(),
            r.severity(), r.status(), r.acceptanceStatus(),
            r.sourceTranscriptVersion(), r.staleStatus(),
            r.evidence().stream().map(ExtractionQueryService::toEvidenceDto).toList(),
            r.createdAt(), r.updatedAt()
        );
    }

    private static EvidenceDTO toEvidenceDto(ActionItemRepository.EvidenceJson e) {
        return new EvidenceDTO(e.segmentId(), e.startMs(), e.endMs(), e.evidenceTextSnapshot());
    }
}
