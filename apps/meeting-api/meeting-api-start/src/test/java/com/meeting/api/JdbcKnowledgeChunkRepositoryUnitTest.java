package com.meeting.api;

import com.meeting.api.infrastructure.persistence.rag.JdbcKnowledgeChunkRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcKnowledgeChunkRepositoryUnitTest {

    @Test
    void formatVectorWritesPgvectorTextFormat() {
        String s = JdbcKnowledgeChunkRepository.formatVector(new float[] {0.1f, 0.2f, 0.3f});
        assertThat(s).isEqualTo("[0.1,0.2,0.3]");
    }

    @Test
    void formatVectorReturnsNullForNullInput() {
        assertThat(JdbcKnowledgeChunkRepository.formatVector(null)).isNull();
    }

    @Test
    void parseVectorAcceptsBracketedAndUnbracketedInput() {
        float[] a = JdbcKnowledgeChunkRepository.parseVector("[1.5,2.5,3.5]");
        assertThat(a).containsExactly(1.5f, 2.5f, 3.5f);

        float[] b = JdbcKnowledgeChunkRepository.parseVector("0.1,0.2");
        assertThat(b).containsExactly(0.1f, 0.2f);
    }

    @Test
    void parseVectorRoundTripsThroughFormat() {
        float[] original = new float[] {0.0f, -1.5f, 3.14f, 99.99f};
        float[] back = JdbcKnowledgeChunkRepository.parseVector(
            JdbcKnowledgeChunkRepository.formatVector(original)
        );
        assertThat(back).containsExactly(original);
    }

    @Test
    void parseVectorTreatsNullOrBlankAsNull() {
        assertThat(JdbcKnowledgeChunkRepository.parseVector(null)).isNull();
        assertThat(JdbcKnowledgeChunkRepository.parseVector("   ")).isNull();
    }

    @Test
    void parseVectorReturnsEmptyArrayForEmptyBrackets() {
        float[] empty = JdbcKnowledgeChunkRepository.parseVector("[]");
        assertThat(empty).isEmpty();
    }
}
