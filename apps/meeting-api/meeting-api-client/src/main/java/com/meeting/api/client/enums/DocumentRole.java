package com.meeting.api.client.enums;

/**
 * Workstation D1 — role a tenant document plays when attached to a meeting.
 * REFERENCE = injected into the minutes prompt + RAG context.
 * ATTACHMENT = displayed in the workstation but not fed into LLM.
 */
public enum DocumentRole {
    REFERENCE,
    ATTACHMENT
}
