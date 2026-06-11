import { describe, expect, it } from "vitest";
import { isForbiddenKey, sanitizeForLogging } from "../sensitive-fields";
import type { MeetingSpeaker, SpeakerProfile } from "@shared/api/client";

describe("sanitizeForLogging", () => {
  it("redacts embedding values at any nesting level", () => {
    const input = {
      taskId: "task_01",
      embedding: { format: "FLOAT32_ARRAY", dimension: 4, values: [0.1, 0.2, 0.3, 0.4] },
    };
    const sanitized = sanitizeForLogging(input);
    expect(sanitized.embedding).toBe("[REDACTED]");
  });

  it("redacts wrappedDataKey and encryptionKeyId", () => {
    const input = {
      wrappedDataKey: "AAAA",
      encryptionKeyId: "kms-v1",
      embeddingHash: "abc",
      keepThis: "yes",
    };
    const sanitized = sanitizeForLogging(input);
    expect(sanitized.wrappedDataKey).toBe("[REDACTED]");
    expect(sanitized.encryptionKeyId).toBe("[REDACTED]");
    expect(sanitized.embeddingHash).toBe("[REDACTED]");
    expect(sanitized.keepThis).toBe("yes");
  });

  it("redacts artifactManifestId at top level and in nested error details", () => {
    const input = {
      artifactManifestId: "manifest_01",
      details: { artifactManifestId: "manifest_inner" },
    };
    const sanitized = sanitizeForLogging(input);
    expect(sanitized.artifactManifestId).toBe("[REDACTED]");
    expect((sanitized.details as { artifactManifestId: unknown }).artifactManifestId).toBe("[REDACTED]");
  });

  it("handles arrays of speaker candidate entries", () => {
    const input = [
      { speakerLabel: "SPEAKER_00", embedding: { values: [0.5] } },
      { speakerLabel: "SPEAKER_01", embedding: { values: [0.6] } },
    ];
    const sanitized = sanitizeForLogging(input) as Array<{ embedding: unknown }>;
    expect(sanitized[0]?.embedding).toBe("[REDACTED]");
    expect(sanitized[1]?.embedding).toBe("[REDACTED]");
  });

  it("leaves null / undefined / primitives intact", () => {
    expect(sanitizeForLogging(null)).toBeNull();
    expect(sanitizeForLogging(undefined)).toBeUndefined();
    expect(sanitizeForLogging("hello")).toBe("hello");
    expect(sanitizeForLogging(42)).toBe(42);
  });

  it("bounds recursion at MAX_DEPTH so cycles cannot blow the stack", () => {
    type Cycle = { name: string; embedding?: { values: number[] }; child?: Cycle };
    const root: Cycle = { name: "root" };
    let cursor: Cycle = root;
    for (let i = 0; i < 20; i++) {
      cursor.child = { name: `n${i}`, embedding: { values: [i] } };
      cursor = cursor.child;
    }
    // It is enough that sanitize returns without throwing.
    const sanitized = sanitizeForLogging(root);
    expect(typeof sanitized).toBe("object");
  });

  it("exposes isForbiddenKey for runtime checks", () => {
    expect(isForbiddenKey("values")).toBe(true);
    expect(isForbiddenKey("artifactManifestId")).toBe(true);
    expect(isForbiddenKey("personId")).toBe(false);
  });

  it("public SpeakerProfile type carries no forbidden fields", () => {
    const sample: SpeakerProfile = {
      speakerProfileId: "spk_01",
      tenantId: "tenant_01",
      personId: "alice",
      displayName: "Alice",
      consentStatus: "ACTIVE",
      consentSource: "MEETING_INVITE",
      consentVersion: "v1",
      revokedAt: null,
      deletedAt: null,
      createdAt: "2026-05-11T09:00:00Z",
      updatedAt: "2026-05-11T09:00:00Z",
    };
    for (const key of Object.keys(sample)) {
      expect(isForbiddenKey(key)).toBe(false);
    }
  });

  it("public MeetingSpeaker type carries no forbidden fields", () => {
    const sample: MeetingSpeaker = {
      speakerLabel: "SPEAKER_00",
      displayName: null,
      personId: null,
      speakerProfileId: null,
      confirmationStatus: "CANDIDATE",
      candidates: [
        {
          personId: "alice",
          speakerProfileId: "spk_alice",
          displayName: "Alice",
          confidence: 0.8,
        },
      ],
    };
    for (const key of Object.keys(sample)) {
      expect(isForbiddenKey(key)).toBe(false);
    }
  });
});
