package com.meeting.api;

import com.meeting.api.domain.rag.KnowledgeChunkCandidate;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.infrastructure.persistence.rag.JdbcKnowledgeChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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

    @Test
    void searchByKeywordBindsEscapedPhraseAndTermFallbacks() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcKnowledgeChunkRepository repo = new JdbcKnowledgeChunkRepository(jdbc);

        repo.searchByKeyword(
            "tenant_01",
            "三季度 预算_50%",
            new KnowledgeChunkRepository.RetrievalScope(List.of("mtg_01"), List.of("doc_01")),
            7
        );

        assertThat(jdbc.sql)
            .contains("plainto_tsquery('simple', ?)")
            .contains("content ILIKE ? ESCAPE '\\'")
            .contains("meeting_id IN (?) OR document_id IN (?)");
        assertThat(countPlaceholders(jdbc.sql)).isEqualTo(jdbc.args.length);
        assertThat(jdbc.args).containsExactly(
            "三季度 预算_50%",
            "%三季度 预算\\_50\\%%",
            "%三季度%",
            "%预算\\_50\\%%",
            "tenant_01",
            "%三季度 预算\\_50\\%%",
            "%三季度%",
            "%预算\\_50\\%%",
            "mtg_01",
            "doc_01",
            7
        );
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args;
            return List.of();
        }
    }
}
