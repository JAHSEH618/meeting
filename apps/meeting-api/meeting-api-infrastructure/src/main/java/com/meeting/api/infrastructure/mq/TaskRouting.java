package com.meeting.api.infrastructure.mq;

import com.meeting.api.client.enums.ProcessingStep;
import java.util.List;

public final class TaskRouting {
    private TaskRouting() {
    }

    public static String routingKeyFor(List<ProcessingStep> pipelineSteps) {
        if (pipelineSteps == null || pipelineSteps.isEmpty()) {
            throw new IllegalArgumentException("pipelineSteps must not be empty");
        }
        ProcessingStep first = pipelineSteps.get(0);
        return switch (first) {
            case AUDIO_PREPROCESS -> "task.audio-cpu";
            case ASR -> "task.gpu-asr";
            case DIARIZATION -> "task.gpu-diar";
            case SPEAKER_EMBEDDING, SPEAKER_MATCHING -> "task.gpu-speaker";
            case TRANSCRIPT_MERGE, RAG_INDEXING -> "task.embed";
            case SUMMARY, EXTRACTION -> "task.llm";
            case EXPORT -> "task.export";
            case AUDIO_UPLOAD, ALIGNMENT -> throw new IllegalArgumentException("step is not routed as a queue entry: " + first);
        };
    }
}
