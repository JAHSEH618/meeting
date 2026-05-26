package com.meeting.api.infrastructure.persistence.speaker;

import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSpeakerProfileRepository implements SpeakerProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcSpeakerProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SpeakerProfile save(SpeakerProfile profile) {
        jdbcTemplate.update(
            """
            INSERT INTO persons (id, tenant_id, display_name)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            profile.personId(),
            profile.tenantId(),
            (profile.displayNameSnapshot() == null || profile.displayNameSnapshot().isBlank()) ? profile.personId() : profile.displayNameSnapshot()
        );

        int updated = jdbcTemplate.update(
            """
            INSERT INTO speaker_profiles (
              id, tenant_id, person_id, display_name_snapshot, consent_status,
              consent_source, consent_version, enrolled_by, revoked_at, deleted_at,
              created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              display_name_snapshot = EXCLUDED.display_name_snapshot,
              consent_status = EXCLUDED.consent_status,
              consent_source = EXCLUDED.consent_source,
              consent_version = EXCLUDED.consent_version,
              revoked_at = EXCLUDED.revoked_at,
              deleted_at = EXCLUDED.deleted_at,
              updated_at = EXCLUDED.updated_at
            """,
            profile.id(),
            profile.tenantId(),
            profile.personId(),
            profile.displayNameSnapshot(),
            profile.consentStatus(),
            profile.consentSource(),
            profile.consentVersion(),
            profile.enrolledBy(),
            profile.revokedAt() == null ? null : Timestamp.from(profile.revokedAt().toInstant()),
            profile.deletedAt() == null ? null : Timestamp.from(profile.deletedAt().toInstant()),
            Timestamp.from(profile.createdAt().toInstant()),
            Timestamp.from(profile.updatedAt().toInstant())
        );
        if (updated == 0) {
            throw new IllegalStateException("failed to save speaker profile: " + profile.id());
        }
        return profile;
    }

    @Override
    public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, person_id, display_name_snapshot, consent_status,
                   consent_source, consent_version, enrolled_by, revoked_at, deleted_at,
                   created_at, updated_at
              FROM speaker_profiles
             WHERE tenant_id = ? AND id = ?
            """,
            rs -> rs.next() ? Optional.of(mapRow(rs)) : Optional.<SpeakerProfile>empty(),
            tenantId,
            profileId
        );
    }

    @Override
    public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) {
        String filter = includeRevoked ? "" : " AND consent_status = 'ACTIVE'";
        return jdbcTemplate.query(
            "SELECT id, tenant_id, person_id, display_name_snapshot, consent_status, consent_source, consent_version,"
                + " enrolled_by, revoked_at, deleted_at, created_at, updated_at"
                + " FROM speaker_profiles WHERE tenant_id = ?" + filter + " ORDER BY created_at DESC",
            (rs, n) -> mapRow(rs),
            tenantId
        );
    }

    @Override
    public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(profileIds.size(), "?"));
        Object[] params = new Object[profileIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < profileIds.size(); i++) {
            params[i + 1] = profileIds.get(i);
        }
        return jdbcTemplate.query(
            "SELECT id, tenant_id, person_id, display_name_snapshot, consent_status, consent_source, consent_version,"
                + " enrolled_by, revoked_at, deleted_at, created_at, updated_at"
                + " FROM speaker_profiles WHERE tenant_id = ? AND id IN (" + placeholders + ")",
            (rs, n) -> mapRow(rs),
            params
        );
    }

    @Override
    public List<SpeakerProfile> findByPersonIds(String tenantId, List<String> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(personIds.size(), "?"));
        Object[] params = new Object[personIds.size() + 1];
        params[0] = tenantId;
        for (int i = 0; i < personIds.size(); i++) {
            params[i + 1] = personIds.get(i);
        }
        return jdbcTemplate.query(
            "SELECT id, tenant_id, person_id, display_name_snapshot, consent_status, consent_source, consent_version,"
                + " enrolled_by, revoked_at, deleted_at, created_at, updated_at"
                + " FROM speaker_profiles WHERE tenant_id = ? AND person_id IN (" + placeholders + ")"
                + " AND consent_status = 'ACTIVE' AND deleted_at IS NULL",
            (rs, n) -> mapRow(rs),
            params
        );
    }

    @Override
    public void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                                     OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt) {
        jdbcTemplate.update(
            """
            UPDATE speaker_profiles
               SET consent_status = ?, revoked_at = ?, deleted_at = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """,
            consentStatus,
            revokedAt == null ? null : Timestamp.from(revokedAt.toInstant()),
            deletedAt == null ? null : Timestamp.from(deletedAt.toInstant()),
            Timestamp.from(updatedAt.toInstant()),
            tenantId,
            profileId
        );
    }

    private static SpeakerProfile mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return SpeakerProfile.restore(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("person_id"),
            rs.getString("display_name_snapshot"),
            rs.getString("consent_status"),
            rs.getString("consent_source"),
            rs.getString("consent_version"),
            rs.getString("enrolled_by"),
            rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getObject("deleted_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
