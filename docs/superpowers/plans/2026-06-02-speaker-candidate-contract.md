# Speaker Candidate Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve worker speaker candidate `personId`, `speakerProfileId`, and `confidence` in Java and expose API candidates compatible with the public OpenAPI contract.

**Architecture:** Keep Java as the source of meeting speaker data. Store full candidate objects in `meeting_speakers` while retaining `candidatePersonIds` for existing auto-confirm logic, and map the preserved candidates into API DTOs with display names resolved from speaker profile snapshots or persons.

**Tech Stack:** Java 17, Spring JDBC, Flyway SQL, JUnit 5, AssertJ, Maven.

---

### Task 1: Callback Service Preserves Full Candidates

**Files:**
- Test: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/SpeakerCandidatesCallbackApplicationServiceTest.java`
- Modify: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/speaker/MeetingSpeakerRepository.java`
- Modify: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/speaker/SpeakerCandidatesCallbackApplicationService.java`

- [x] **Step 1: Write the failing test**

Add `writeCandidatesStoresOnlyAuthorizedFullCandidateObjects`:

```java
assertThat(speakers.savedCandidates).containsExactly(new MeetingSpeakerRepository.SpeakerCandidate(
    "person_01",
    "profile_01",
    0.91
));
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-start -am -Dtest=SpeakerCandidatesCallbackApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `MeetingSpeakerRepository.SpeakerCandidate` does not exist or `saveCandidates` only accepts `List<String>`.

- [x] **Step 3: Implement minimal domain/app change**

Add a `SpeakerCandidate` record to `MeetingSpeakerRepository`, extend `saveCandidates` and `MeetingSpeakerRecord` to include `List<SpeakerCandidate> candidates`, and pass filtered authorized candidates from the callback service.

- [x] **Step 4: Run the focused test and verify GREEN**

Run the same Maven command. Expected: the new test passes.

### Task 2: Meeting Speaker List Exposes OpenAPI Candidates

**Files:**
- Test: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/SpeakerAutoConfirmServiceTest.java`
- Modify: `apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/speaker/MeetingSpeakerDTO.java`
- Create: `apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/speaker/MeetingSpeakerCandidateDTO.java`
- Modify: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/speaker/MeetingSpeakerApplicationService.java`

- [x] **Step 1: Write the failing DTO mapping test**

Add a service-list test that expects:

```java
assertThat(confirmService.list("tenant_01", "meeting_01"))
    .singleElement()
    .satisfies(dto -> assertThat(dto.candidates())
        .singleElement()
        .satisfies(candidate -> {
            assertThat(candidate.personId()).isEqualTo("person_01");
            assertThat(candidate.speakerProfileId()).isEqualTo("profile_01");
            assertThat(candidate.displayName()).isEqualTo("Alice Profile");
            assertThat(candidate.confidence()).isEqualTo(0.91);
        }));
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-start -am -Dtest=SpeakerAutoConfirmServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `MeetingSpeakerDTO.candidates()` does not exist.

- [x] **Step 3: Implement minimal DTO mapping**

Add `MeetingSpeakerCandidateDTO`, add `List<MeetingSpeakerCandidateDTO> candidates` to `MeetingSpeakerDTO`, and map `MeetingSpeakerRecord.candidates()` into DTO candidates using active profile snapshot first and person display name fallback.

- [x] **Step 4: Run the focused test and verify GREEN**

Run the same Maven command. Expected: focused tests pass.

### Task 3: JDBC Persists Candidate JSON

**Files:**
- Modify: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/speaker/JdbcMeetingSpeakerRepository.java`
- Create: `apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202606020002__meeting_speakers_candidates.sql`

- [x] **Step 1: Add `candidates jsonb NOT NULL DEFAULT '[]'::jsonb` migration**

Migration:

```sql
ALTER TABLE meeting_speakers
  ADD COLUMN IF NOT EXISTS candidates jsonb NOT NULL DEFAULT '[]'::jsonb;
```

- [x] **Step 2: Leave the existing initial migration checksum stable**

Do not modify `V202605110001__initial_schema.sql`; existing databases may already have applied it, and the forward migration covers both deployed and fresh databases.

- [x] **Step 3: Update JDBC writes and reads**

Persist candidate object JSON to `candidates`, keep `candidate_person_ids` populated from those objects, and map legacy rows without `candidates` to an empty candidate list.

- [x] **Step 4: Run Java tests**

Run:

```bash
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-start -am -DskipITs test
```

Expected: tests pass.

### Task 4: Contract Verification and Commit

**Files:**
- Verify all changed Java and contract-facing files.

- [x] **Step 1: Run contract check**

Run:

```bash
cd packages/meeting-contracts && JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run check
```

Expected: contract check passes or reports only pre-existing generated drift.

- [x] **Step 2: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [x] **Step 3: Commit and push**

Stage only touched paths:

```bash
git add docs/superpowers/plans/2026-06-02-speaker-candidate-contract.md apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/speaker/MeetingSpeakerDTO.java apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/speaker/MeetingSpeakerCandidateDTO.java apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/speaker/MeetingSpeakerRepository.java apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/speaker/SpeakerCandidatesCallbackApplicationService.java apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/speaker/MeetingSpeakerApplicationService.java apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/speaker/JdbcMeetingSpeakerRepository.java apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202606020002__meeting_speakers_candidates.sql apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/SpeakerCandidatesCallbackApplicationServiceTest.java apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/SpeakerAutoConfirmServiceTest.java apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/JdbcMeetingSpeakerRepositoryUnitTest.java
git commit -m "fix(meeting-api): preserve speaker candidate profiles"
git push
```
