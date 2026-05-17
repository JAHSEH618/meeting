package com.meeting.api.infrastructure.persistence.artifact;

import com.meeting.api.domain.artifact.ArtifactManifestRepository;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArtifactManifestRepository implements ArtifactManifestRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcArtifactManifestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String save(ArtifactManifestRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO artifact_manifests (
              id, tenant_id, meeting_id, task_id, artifact_type, artifact_uri, artifact_hash,
              input_artifact_hash, input_json, output_json, models_json,
              prompt_template_id, prompt_template_version, provider, model_version,
              pipeline_version, code_version, data_boundary_policy_version, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?)
            """,
            record.id(),
            record.tenantId(),
            record.meetingId(),
            record.taskId(),
            record.artifactType(),
            record.artifactUri(),
            record.artifactHash(),
            record.inputArtifactHash(),
            record.inputJson() == null ? "{}" : record.inputJson(),
            record.outputJson() == null ? "{}" : record.outputJson(),
            record.modelsJson() == null ? "[]" : record.modelsJson(),
            record.promptTemplateId(),
            record.promptTemplateVersion(),
            record.provider(),
            record.modelVersion(),
            record.pipelineVersion(),
            record.codeVersion(),
            record.dataBoundaryPolicyVersion(),
            Timestamp.from(record.createdAt().toInstant())
        );
        return record.id();
    }
}
