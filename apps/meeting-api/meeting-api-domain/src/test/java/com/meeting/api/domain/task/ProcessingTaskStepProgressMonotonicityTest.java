package com.meeting.api.domain.task;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.StepStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for I7: Progress monotonicity guard in heartbeat
 */
class ProcessingTaskStepProgressMonotonicityTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    @Test
    void shouldRejectProgressRegression() {
        ProcessingTaskStep step = ProcessingTaskStep.pending(ProcessingStep.ASR, ProcessingStepUpdateSource.AI_WORKER_CALLBACK);

        // 初始心跳 progress=30
        step.heartbeat(30, 1, "worker:task:1", NOW);
        assertThat(step.progress()).isEqualTo(30);

        // 尝试回退到 progress=20，应该被拒绝
        step.heartbeat(20, 1, "worker:task:1", NOW.plusSeconds(10));

        // progress 应该保持不变
        assertThat(step.progress()).isEqualTo(30);

        // 但租约应该续期
        assertThat(step.heartbeatAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void shouldAllowProgressAdvance() {
        ProcessingTaskStep step = ProcessingTaskStep.pending(ProcessingStep.ASR, ProcessingStepUpdateSource.AI_WORKER_CALLBACK);

        step.heartbeat(30, 1, "worker:task:1", NOW);
        assertThat(step.progress()).isEqualTo(30);

        // 前进到 progress=50，应该成功
        step.heartbeat(50, 1, "worker:task:1", NOW.plusSeconds(10));

        assertThat(step.progress()).isEqualTo(50);
        assertThat(step.heartbeatAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void shouldAllowSameProgress() {
        ProcessingTaskStep step = ProcessingTaskStep.pending(ProcessingStep.ASR, ProcessingStepUpdateSource.AI_WORKER_CALLBACK);

        step.heartbeat(30, 1, "worker:task:1", NOW);
        assertThat(step.progress()).isEqualTo(30);

        // 相同 progress，应该成功（租约续期）
        step.heartbeat(30, 1, "worker:task:1", NOW.plusSeconds(10));

        assertThat(step.progress()).isEqualTo(30);
        assertThat(step.heartbeatAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void shouldRenewLeaseEvenWhenProgressRegresses() {
        ProcessingTaskStep step = ProcessingTaskStep.pending(ProcessingStep.ASR, ProcessingStepUpdateSource.AI_WORKER_CALLBACK);

        step.heartbeat(80, 1, "worker:task:1", NOW);
        OffsetDateTime firstHeartbeat = step.heartbeatAt();

        // progress 回退，但租约仍然续期
        step.heartbeat(50, 1, "worker:task:1", NOW.plusSeconds(30));

        assertThat(step.progress()).isEqualTo(80); // progress 不变
        assertThat(step.heartbeatAt()).isAfter(firstHeartbeat); // 租约已续期
        assertThat(step.heartbeatAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void shouldUpdateLeaseOwnerEvenWhenProgressRegresses() {
        ProcessingTaskStep step = ProcessingTaskStep.pending(ProcessingStep.ASR, ProcessingStepUpdateSource.AI_WORKER_CALLBACK);

        step.heartbeat(80, 1, "worker:task:1", NOW);

        // progress 回退，但 leaseOwner 和 attemptNo 应该更新
        step.heartbeat(50, 2, "worker:task:2", NOW.plusSeconds(30));

        assertThat(step.progress()).isEqualTo(80); // progress 不变
        assertThat(step.attemptNo()).isEqualTo(2); // attemptNo 更新
        assertThat(step.leaseOwner()).isEqualTo("worker:task:2"); // leaseOwner 更新
    }
}
