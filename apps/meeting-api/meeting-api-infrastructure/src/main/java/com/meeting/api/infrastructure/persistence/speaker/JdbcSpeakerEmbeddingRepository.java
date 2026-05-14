package com.meeting.api.infrastructure.persistence.speaker;

import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSpeakerEmbeddingRepository implements SpeakerEmbeddingRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcSpeakerEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(SpeakerEmbeddingRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO speaker_embeddings (
              id, tenant_id, speaker_profile_id, person_id, consent_status,
              encryption_key_id, wrapped_data_key, encryption_algorithm,
              embedding_ciphertext, embedding_hash, source_audio_file_id,
              quality_score, model_version, revoked_at, deleted_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.id(),
            record.tenantId(),
            record.speakerProfileId(),
            record.personId(),
            record.consentStatus(),
            record.encryptionKeyId(),
            record.wrappedDataKey(),
            record.encryptionAlgorithm(),
            record.embeddingCiphertext(),
            record.embeddingHash(),
            record.sourceAudioFileId(),
            record.qualityScore(),
            record.modelVersion(),
            record.revokedAt() == null ? null : Timestamp.from(record.revokedAt().toInstant()),
            record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()),
            Timestamp.from(record.createdAt().toInstant())
        );
    }

    @Override
    public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) {
        return jdbcTemplate.query(
            "SELECT id, tenant_id, speaker_profile_id, person_id, consent_status,"
                + " encryption_key_id, wrapped_data_key, encryption_algorithm,"
                + " embedding_ciphertext, embedding_hash, source_audio_file_id,"
                + " quality_score, model_version, revoked_at, deleted_at, created_at"
                + " FROM speaker_embeddings WHERE tenant_id = ? AND speaker_profile_id = ?"
                + " ORDER BY created_at DESC",
            (rs, n) -> {
                Double qualityScore = rs.getObject("quality_score") == null ? null : rs.getDouble("quality_score");
                return new SpeakerEmbeddingRecord(
                    rs.getString("id"),
                    rs.getString("tenant_id"),
                    rs.getString("speaker_profile_id"),
                    rs.getString("person_id"),
                    rs.getString("consent_status"),
                    rs.getString("encryption_key_id"),
                    rs.getBytes("wrapped_data_key"),
                    rs.getString("encryption_algorithm"),
                    rs.getBytes("embedding_ciphertext"),
                    rs.getString("embedding_hash"),
                    rs.getString("source_audio_file_id"),
                    qualityScore,
                    rs.getString("model_version"),
                    rs.getObject("revoked_at", OffsetDateTime.class),
                    rs.getObject("deleted_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class)
                );
            },
            tenantId,
            speakerProfileId
        );
    }

    @Override
    public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
        return jdbcTemplate.update(
            "UPDATE speaker_embeddings SET consent_status = 'REVOKED', revoked_at = ?"
                + " WHERE tenant_id = ? AND speaker_profile_id = ? AND consent_status = 'ACTIVE'",
            Timestamp.from(now.toInstant()),
            tenantId,
            speakerProfileId
        );
    }

    @Override
    public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) {
        return jdbcTemplate.update(
            "UPDATE speaker_embeddings SET consent_status = 'DELETED', deleted_at = ?"
                + " WHERE tenant_id = ? AND speaker_profile_id = ? AND consent_status <> 'DELETED'",
            Timestamp.from(now.toInstant()),
            tenantId,
            speakerProfileId
        );
    }
}
