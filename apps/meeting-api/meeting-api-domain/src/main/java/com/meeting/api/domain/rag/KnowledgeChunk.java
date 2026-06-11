package com.meeting.api.domain.rag;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * RAG aggregate representing one chunked, embeddable piece of text.
 *
 * <p>A chunk is born without an embedding ({@code embedding == null,
 * embeddingModelVersion == null, status == ACTIVE, staleStatus == ACTIVE}).
 * The async TEXT_EMBEDDING worker fills the vector in via
 * {@link #markEmbedding(float[], String)} once a callback lands. Edits to
 * the source content flip {@link #markStale(StaleStatus)} so the RAG
 * query layer can skip stale chunks until the next reindex.
 *
 * <p>The class is intentionally mutable — chunk identity is the primary
 * key, embedding / freshness are operational attributes. All transitions
 * go through methods so the {@code updatedAt} bookkeeping is centralised.
 */
public final class KnowledgeChunk {

    private final String id;
    private final String tenantId;
    private final String projectId;
    private final String meetingId;
    private final String documentId;
    private final KnowledgeSourceType sourceType;
    private final String sourceId;
    private final String sourceSegmentId;
    private final String content;
    private final String contentHash;
    private final String chunkStrategyVersion;
    private final Integer transcriptVersion;
    private final Integer minutesVersion;
    private final SecurityLevel securityLevel;
    private final int chunkVersion;
    private final OffsetDateTime createdAt;

    private float[] embedding;
    private String embeddingModelVersion;
    private ChunkStatus status;
    private StaleStatus staleStatus;
    private OffsetDateTime updatedAt;

    private KnowledgeChunk(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.projectId = b.projectId;
        this.meetingId = b.meetingId;
        this.documentId = b.documentId;
        this.sourceType = Objects.requireNonNull(b.sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(b.sourceId, "sourceId");
        this.sourceSegmentId = b.sourceSegmentId;
        this.content = Objects.requireNonNull(b.content, "content");
        this.contentHash = Objects.requireNonNull(b.contentHash, "contentHash");
        this.chunkStrategyVersion = Objects.requireNonNull(b.chunkStrategyVersion, "chunkStrategyVersion");
        this.transcriptVersion = b.transcriptVersion;
        this.minutesVersion = b.minutesVersion;
        this.securityLevel = Objects.requireNonNull(b.securityLevel, "securityLevel");
        this.chunkVersion = b.chunkVersion;
        this.embedding = b.embedding == null ? null : b.embedding.clone();
        this.embeddingModelVersion = b.embeddingModelVersion;
        this.status = b.status == null ? ChunkStatus.ACTIVE : b.status;
        this.staleStatus = b.staleStatus == null ? StaleStatus.ACTIVE : b.staleStatus;
        this.createdAt = b.createdAt == null ? OffsetDateTime.now() : b.createdAt;
        this.updatedAt = b.updatedAt == null ? this.createdAt : b.updatedAt;

        if (sourceType == KnowledgeSourceType.DOCUMENT && documentId == null) {
            throw new IllegalArgumentException("DOCUMENT-sourced chunk requires documentId");
        }
        if (sourceType != KnowledgeSourceType.DOCUMENT && meetingId == null) {
            throw new IllegalArgumentException(
                sourceType + "-sourced chunk requires meetingId (got documentId=" + documentId + ")"
            );
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (chunkVersion < 1) {
            throw new IllegalArgumentException("chunkVersion must be >= 1");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String projectId() { return projectId; }
    public String meetingId() { return meetingId; }
    public String documentId() { return documentId; }
    public KnowledgeSourceType sourceType() { return sourceType; }
    public String sourceId() { return sourceId; }
    public String sourceSegmentId() { return sourceSegmentId; }
    public String content() { return content; }
    public String contentHash() { return contentHash; }
    public String chunkStrategyVersion() { return chunkStrategyVersion; }
    public Integer transcriptVersion() { return transcriptVersion; }
    public Integer minutesVersion() { return minutesVersion; }
    public SecurityLevel securityLevel() { return securityLevel; }
    public int chunkVersion() { return chunkVersion; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public ChunkStatus status() { return status; }
    public StaleStatus staleStatus() { return staleStatus; }
    public String embeddingModelVersion() { return embeddingModelVersion; }

    /** @return a defensive copy so callers can't mutate the stored embedding. */
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    public boolean hasEmbedding() {
        return embedding != null;
    }

    public boolean isActiveAndFresh() {
        return status == ChunkStatus.ACTIVE && staleStatus == StaleStatus.ACTIVE;
    }

    /**
     * Attach the dense vector returned by ai-worker. Stores a defensive
     * copy of the values array so the caller can clear plaintext after
     * the persist completes.
     */
    public void markEmbedding(float[] values, String modelVersion, OffsetDateTime at) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(modelVersion, "modelVersion");
        if (values.length == 0) {
            throw new IllegalArgumentException("embedding values must not be empty");
        }
        this.embedding = values.clone();
        this.embeddingModelVersion = modelVersion;
        this.updatedAt = at == null ? OffsetDateTime.now() : at;
    }

    /**
     * Flip the freshness flag (typically {@code STALE} after a transcript
     * edit, or {@code REBUILD_QUEUED} once a reindex is scheduled).
     */
    public void markStale(StaleStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        this.staleStatus = newStatus;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Soft-delete a chunk; the row stays for audit. */
    public void markDeleted() {
        this.status = ChunkStatus.DELETED;
        this.staleStatus = StaleStatus.DELETED;
        this.updatedAt = OffsetDateTime.now();
    }

    public static final class Builder {
        private String id;
        private String tenantId;
        private String projectId;
        private String meetingId;
        private String documentId;
        private KnowledgeSourceType sourceType;
        private String sourceId;
        private String sourceSegmentId;
        private String content;
        private String contentHash;
        private String chunkStrategyVersion;
        private Integer transcriptVersion;
        private Integer minutesVersion;
        private SecurityLevel securityLevel;
        private int chunkVersion = 1;
        private float[] embedding;
        private String embeddingModelVersion;
        private ChunkStatus status;
        private StaleStatus staleStatus;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder projectId(String projectId) { this.projectId = projectId; return this; }
        public Builder meetingId(String meetingId) { this.meetingId = meetingId; return this; }
        public Builder documentId(String documentId) { this.documentId = documentId; return this; }
        public Builder sourceType(KnowledgeSourceType sourceType) { this.sourceType = sourceType; return this; }
        public Builder sourceId(String sourceId) { this.sourceId = sourceId; return this; }
        public Builder sourceSegmentId(String sourceSegmentId) { this.sourceSegmentId = sourceSegmentId; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder contentHash(String contentHash) { this.contentHash = contentHash; return this; }
        public Builder chunkStrategyVersion(String v) { this.chunkStrategyVersion = v; return this; }
        public Builder transcriptVersion(Integer v) { this.transcriptVersion = v; return this; }
        public Builder minutesVersion(Integer v) { this.minutesVersion = v; return this; }
        public Builder securityLevel(SecurityLevel v) { this.securityLevel = v; return this; }
        public Builder chunkVersion(int v) { this.chunkVersion = v; return this; }
        public Builder embedding(float[] v) { this.embedding = v; return this; }
        public Builder embeddingModelVersion(String v) { this.embeddingModelVersion = v; return this; }
        public Builder status(ChunkStatus v) { this.status = v; return this; }
        public Builder staleStatus(StaleStatus v) { this.staleStatus = v; return this; }
        public Builder createdAt(OffsetDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(OffsetDateTime v) { this.updatedAt = v; return this; }

        public KnowledgeChunk build() {
            return new KnowledgeChunk(this);
        }
    }
}
