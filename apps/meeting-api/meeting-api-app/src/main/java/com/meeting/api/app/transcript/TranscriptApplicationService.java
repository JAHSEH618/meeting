package com.meeting.api.app.transcript;

import com.meeting.api.client.transcript.TranscriptDTO;
import com.meeting.api.client.transcript.TranscriptFacade;
import com.meeting.api.client.transcript.TranscriptSegmentDTO;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TranscriptApplicationService implements TranscriptFacade {
    private final MeetingRepository meetingRepository;
    private final TranscriptRepository transcriptRepository;

    public TranscriptApplicationService(MeetingRepository meetingRepository, TranscriptRepository transcriptRepository) {
        this.meetingRepository = meetingRepository;
        this.transcriptRepository = transcriptRepository;
    }

    @Override
    public Optional<TranscriptDTO> get(String tenantId, String meetingId) {
        return meetingRepository.findById(tenantId, meetingId)
            .map(meeting -> {
                int version = meeting.transcriptVersion();
                var segments = transcriptRepository.findByMeeting(tenantId, meetingId, version).stream()
                    .sorted(Comparator.comparingInt(TranscriptRepository.TranscriptSegmentRecord::segmentIndex))
                    .map(segment -> new TranscriptSegmentDTO(
                        segment.segmentId(),
                        segment.startMs(),
                        segment.endMs(),
                        segment.speakerLabel(),
                        segment.speakerDisplayName(),
                        segment.originalText(),
                        segment.editedText(),
                        segment.currentText(),
                        segment.asrConfidence(),
                        segment.diarizationConfidence(),
                        segment.timestampPrecision()
                    ))
                    .toList();
                return new TranscriptDTO(meetingId, version, "ACTIVE", segments);
            });
    }
}
