package com.meeting.api;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingStepUpdateSource;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.enums.StaleStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.client.enums.TaskEventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientEnumConsistencyTest {

    @Test
    void clientEnumsMatchContractsFactSource() throws IOException {
        Map<String, List<String>> enums = parseEnumYaml(findRepoRoot().resolve("packages/meeting-contracts/schemas/common/enums.yaml"));

        assertThat(names(MeetingStatus.class)).containsExactlyElementsOf(enums.get("meetingStatus"));
        assertThat(names(SecurityLevel.class)).containsExactlyElementsOf(enums.get("securityLevel"));
        assertThat(names(ProcessingTaskStatus.class)).containsExactlyElementsOf(enums.get("processingTaskStatus"));
        assertThat(names(ProcessingTaskPhase.class)).containsExactlyElementsOf(enums.get("processingTaskPhase"));
        assertThat(names(StepStatus.class)).containsExactlyElementsOf(enums.get("stepStatus"));
        assertThat(names(ProcessingStep.class)).containsExactlyElementsOf(enums.get("processingStep"));
        assertThat(names(ProcessingStepUpdateSource.class)).containsExactlyElementsOf(enums.get("processingStepUpdateSource"));
        assertThat(names(RagAnswerCoverage.class)).containsExactlyElementsOf(enums.get("ragAnswerCoverage"));
        assertThat(names(StaleStatus.class)).containsExactlyElementsOf(enums.get("staleStatus"));
        assertThat(names(TaskEventType.class)).containsExactlyElementsOf(enums.get("taskEventType"));
    }

    private static List<String> names(Class<? extends Enum<?>> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
            .map(Enum::name)
            .toList();
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("packages/meeting-contracts/schemas/common/enums.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }

    private static Map<String, List<String>> parseEnumYaml(Path path) throws IOException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        String currentKey = null;
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.equals("version: 0.1.0")) {
                continue;
            }
            if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                currentKey = line.substring(0, line.length() - 1);
                values.putIfAbsent(currentKey, new java.util.ArrayList<>());
                continue;
            }
            if (currentKey != null && line.startsWith("- ")) {
                values.get(currentKey).add(line.substring(2));
            }
        }
        return values;
    }
}
