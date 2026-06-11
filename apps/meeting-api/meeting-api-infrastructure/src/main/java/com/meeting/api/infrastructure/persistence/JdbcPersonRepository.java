package com.meeting.api.infrastructure.persistence;

import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPersonRepository implements PersonRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcPersonRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Person save(Person person) {
        jdbcTemplate.update(
            """
            INSERT INTO persons (
              id, tenant_id, display_name, normalized_name, email, external_ref,
              status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            person.id(),
            person.tenantId(),
            person.displayName(),
            normalize(person.displayName()),
            person.email(),
            person.externalRef(),
            person.status(),
            toTimestamp(person.createdAt()),
            toTimestamp(person.createdAt())
        );
        return person;
    }

    @Override
    public Optional<Person> findById(String tenantId, String personId) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
              FROM persons
             WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> new Person(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("external_ref"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at"))
            ),
            tenantId,
            personId
        ).stream().findFirst();
    }

    @Override
    public List<Person> findByDisplayName(String tenantId, String displayName) {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
              FROM persons
             WHERE tenant_id = ? AND display_name = ? AND deleted_at IS NULL AND status = 'ACTIVE'
             ORDER BY created_at ASC, id ASC
            """,
            (rs, rowNum) -> new Person(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("external_ref"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at"))
            ),
            tenantId,
            displayName
        );
    }

    @Override
    public List<Person> searchByQuery(String tenantId, String q, int limit) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
              FROM persons
             WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
               AND (? = '' OR LOWER(display_name) LIKE LOWER(?) OR LOWER(COALESCE(email, '')) LIKE LOWER(?))
             ORDER BY display_name ASC, created_at ASC
             LIMIT ?
            """,
            (rs, rowNum) -> new Person(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("external_ref"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at"))
            ),
            tenantId,
            q == null ? "" : q.trim(),
            like,
            like,
            limit
        );
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
