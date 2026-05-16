package com.meeting.api.client.rag;

/**
 * Adapter-facing entry point for {@code POST /api/rag/query}.
 *
 * <p>The implementation orchestrates the full RAG pipeline:
 *
 * <ol>
 *   <li>Authorize the caller-supplied scope (drop meetings / documents
 *       the user cannot read).</li>
 *   <li>Embed the question and run vector + keyword retrieval against
 *       {@code knowledge_chunks}, fused by RRF.</li>
 *   <li>Second-pass authorize the surviving candidates (security level
 *       + owner readability), since pgvector is a candidate retriever
 *       only.</li>
 *   <li>Rerank with ai-worker, take top-N, build a numbered context
 *       block.</li>
 *   <li>Call the LLM under the meeting / document security level — any
 *       {@code CONFIDENTIAL} / {@code SECRET} chunk fails closed inside
 *       the gateway.</li>
 *   <li>Map LLM-cited indices back to chunk citations and return the
 *       audited DTO.</li>
 * </ol>
 *
 * <p>Returns a degraded "no information" answer with empty citations
 * when retrieval / authorization yields no chunks — the LLM is not
 * called in that case.
 */
public interface RagQueryFacade {

    RagAnswerDTO query(RagQueryCommand command);
}
