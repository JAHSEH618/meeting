package com.meeting.api;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.infrastructure.mq.TaskRouting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfrastructureRoutingTest {

    @Test
    void routesWorkerPipelineByFirstExecutableStep() {
        assertThat(TaskRouting.routingKeyFor(List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR)))
            .isEqualTo("task.audio-cpu");
        assertThat(TaskRouting.routingKeyFor(List.of(ProcessingStep.ASR)))
            .isEqualTo("task.gpu-asr");
        assertThat(TaskRouting.routingKeyFor(List.of(ProcessingStep.DIARIZATION)))
            .isEqualTo("task.gpu-diar");
        assertThat(TaskRouting.routingKeyFor(List.of(ProcessingStep.SPEAKER_EMBEDDING)))
            .isEqualTo("task.gpu-speaker");
        assertThat(TaskRouting.routingKeyFor(List.of(ProcessingStep.RAG_INDEXING)))
            .isEqualTo("task.embed");
    }

    @Test
    void rejectsNonWorkerEntrySteps() {
        assertThatThrownBy(() -> TaskRouting.routingKeyFor(List.of(ProcessingStep.AUDIO_UPLOAD)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
