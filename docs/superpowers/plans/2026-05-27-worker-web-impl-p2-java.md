# P2 — Java meeting-api Implementation

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Add `POST /api/persons`, generic `POST /api/files/*`, and `SpeakerAutoConfirmService` invoked by `WorkerPhaseCompletedListener` before LLM phase.

**Working dir:** `apps/meeting-api/`

**Pre-flight:** Java 17 active (`java -version` shows 17). P1 contracts + codegen already merged. JDK Enforcer 锁 `[17,18)`.

**Run unit tests:** `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test`
**Run full verify (incl. IT, needs Docker):** `./mvnw verify -q`

---

## P2.A — Person aggregate (client + domain + app + adapter + infra)

### Task 1: Domain — PersonRepository port

**Files:**
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/person/PersonRepository.java`
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/person/Person.java`

- [ ] **Step 1: Define Person aggregate (record)**

```java
package com.meeting.api.domain.person;

import java.time.OffsetDateTime;

public record Person(
    String id,
    String tenantId,
    String displayName,
    String email,
    String externalRef,
    String status,
    OffsetDateTime createdAt
) {
    public boolean isActive() { return "ACTIVE".equals(status); }
}
```

- [ ] **Step 2: Define repository port**

```java
package com.meeting.api.domain.person;

import java.util.List;
import java.util.Optional;

public interface PersonRepository {
    Person save(Person person);
    Optional<Person> findById(String tenantId, String personId);
    List<Person> findByDisplayName(String tenantId, String displayName);
    List<Person> searchByQuery(String tenantId, String q, int limit);
}
```

- [ ] **Step 3: Commit**

```bash
git add meeting-api-domain/src/main/java/com/meeting/api/domain/person/
git commit -m "feat(meeting-api): add Person aggregate + repository port"
```

---

### Task 2: Client — PersonFacade + DTOs + Command

**Files:**
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/person/PersonDTO.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/person/CreatePersonCommand.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/person/PersonFacade.java`

- [ ] **Step 1: DTO**

```java
package com.meeting.api.client.person;

import java.time.OffsetDateTime;

public record PersonDTO(
    String personId,
    String displayName,
    String email,
    String externalId,
    OffsetDateTime createdAt
) {}
```

- [ ] **Step 2: Command**

```java
package com.meeting.api.client.person;

public record CreatePersonCommand(
    String tenantId,
    String displayName,
    String email,
    String externalId,
    boolean forceCreate,
    String userId,
    String requestId,
    String traceId,
    String idempotencyKey
) {}
```

- [ ] **Step 3: Facade**

```java
package com.meeting.api.client.person;

import java.util.List;

public interface PersonFacade {
    PersonDTO create(CreatePersonCommand cmd);
    List<PersonDTO> search(String tenantId, String q, int limit);
}
```

- [ ] **Step 4: PersonDuplicateException sentinel**

Create `meeting-api-client/src/main/java/com/meeting/api/client/person/PersonDuplicateException.java`:

```java
package com.meeting.api.client.person;

import java.util.List;

public class PersonDuplicateException extends RuntimeException {
    private final List<PersonDTO> matches;
    public PersonDuplicateException(List<PersonDTO> matches) {
        super("PERSON_DUPLICATE");
        this.matches = matches;
    }
    public List<PersonDTO> matches() { return matches; }
}
```

- [ ] **Step 5: Commit**

```bash
git add meeting-api-client/src/main/java/com/meeting/api/client/person/
git commit -m "feat(meeting-api): add PersonFacade + DTO + Command + duplicate exception"
```

---

### Task 3: Infrastructure — JdbcPersonRepository (TDD: integration test first)

**Files:**
- Create: `meeting-api-infrastructure/src/test/java/com/meeting/api/infrastructure/persistence/JdbcPersonRepositoryIT.java`
- Create: `meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/JdbcPersonRepository.java`

- [ ] **Step 1: Write failing IT (Testcontainers, mirror existing repository ITs)**

```java
package com.meeting.api.infrastructure.persistence;

import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class JdbcPersonRepositoryIT extends AbstractPgTestcontainersIT {

    @Test
    void save_then_findById_roundtrips() {
        PersonRepository repo = new JdbcPersonRepository(jdbcTemplate(), tenantScopedTransaction());
        Person p = new Person(UUID.randomUUID().toString(), tenantId(), "李四", "lisi@example.com", null, "ACTIVE", OffsetDateTime.now());
        repo.save(p);
        assertThat(repo.findById(tenantId(), p.id())).hasValueSatisfying(found ->
            assertThat(found.displayName()).isEqualTo("李四"));
    }

    @Test
    void findByDisplayName_matches_exact_only() {
        PersonRepository repo = new JdbcPersonRepository(jdbcTemplate(), tenantScopedTransaction());
        repo.save(new Person(UUID.randomUUID().toString(), tenantId(), "张三", null, null, "ACTIVE", OffsetDateTime.now()));
        repo.save(new Person(UUID.randomUUID().toString(), tenantId(), "张三", "z3@x.com", null, "ACTIVE", OffsetDateTime.now()));
        repo.save(new Person(UUID.randomUUID().toString(), tenantId(), "张三丰", null, null, "ACTIVE", OffsetDateTime.now()));
        List<Person> matches = repo.findByDisplayName(tenantId(), "张三");
        assertThat(matches).hasSize(2);
    }

    @Test
    void searchByQuery_substring_case_insensitive() {
        PersonRepository repo = new JdbcPersonRepository(jdbcTemplate(), tenantScopedTransaction());
        repo.save(new Person(UUID.randomUUID().toString(), tenantId(), "Alice Wang", null, null, "ACTIVE", OffsetDateTime.now()));
        repo.save(new Person(UUID.randomUUID().toString(), tenantId(), "Bob", null, null, "ACTIVE", OffsetDateTime.now()));
        assertThat(repo.searchByQuery(tenantId(), "alice", 10)).hasSize(1);
    }
}
```

(Reuse the existing `AbstractPgTestcontainersIT` superclass — search for it under `meeting-api-infrastructure/src/test/java/`. If not present, look in `meeting-api-start/src/test/java/` for the pattern.)

- [ ] **Step 2: Run test — expect compile failure**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-infrastructure -am test -Dtest=JdbcPersonRepositoryIT
```

Expected: FAIL — `JdbcPersonRepository` not found.

- [ ] **Step 3: Implement repository**

```java
package com.meeting.api.infrastructure.persistence;

import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcPersonRepository implements PersonRepository {
    private final JdbcTemplate jdbc;
    private final com.meeting.api.app.common.TenantScopedTransaction tx;  // adjust import to actual class

    public JdbcPersonRepository(JdbcTemplate jdbc, com.meeting.api.app.common.TenantScopedTransaction tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Person save(Person p) {
        jdbc.update("""
            INSERT INTO persons (id, tenant_id, display_name, email, external_ref, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            p.id(), p.tenantId(), p.displayName(), p.email(), p.externalRef(), p.status(), p.createdAt(), p.createdAt());
        return p;
    }

    @Override
    public Optional<Person> findById(String tenantId, String personId) {
        return jdbc.query("""
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
            FROM persons WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> new Person(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)),
            tenantId, personId).stream().findFirst();
    }

    @Override
    public List<Person> findByDisplayName(String tenantId, String displayName) {
        return jdbc.query("""
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
            FROM persons
            WHERE tenant_id = ? AND display_name = ? AND deleted_at IS NULL AND status = 'ACTIVE'
            ORDER BY created_at
            """,
            (rs, i) -> new Person(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)),
            tenantId, displayName);
    }

    @Override
    public List<Person> searchByQuery(String tenantId, String q, int limit) {
        return jdbc.query("""
            SELECT id, tenant_id, display_name, email, external_ref, status, created_at
            FROM persons
            WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
              AND (LOWER(display_name) LIKE LOWER(?) OR LOWER(COALESCE(email, '')) LIKE LOWER(?))
            ORDER BY display_name LIMIT ?
            """,
            (rs, i) -> new Person(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)),
            tenantId, "%" + q + "%", "%" + q + "%", limit);
    }
}
```

(Adjust constructor arg type to whatever `TenantScopedTransaction` is named in this codebase — grep `tenantScopedTransaction\.execute` to find it.)

- [ ] **Step 4: Run test — expect PASS**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-infrastructure -am test -Dtest=JdbcPersonRepositoryIT
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/JdbcPersonRepository.java meeting-api-infrastructure/src/test/java/com/meeting/api/infrastructure/persistence/JdbcPersonRepositoryIT.java
git commit -m "feat(meeting-api): JdbcPersonRepository with displayName + search queries"
```

---

### Task 4: Flyway migration for displayName index

**Files:**
- Create: `meeting-api-infrastructure/src/main/resources/db/migration/V202605270001__person_displayname_index.sql`

- [ ] **Step 1: Migration**

```sql
-- V202605270001 — index for exact-match dedup probe on POST /api/persons
CREATE INDEX IF NOT EXISTS idx_persons_tenant_displayname
    ON persons (tenant_id, display_name)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';
```

- [ ] **Step 2: Smoke-apply locally**

```bash
docker run --rm -e POSTGRES_PASSWORD=test -p 55432:5432 -d --name pg-smoke pgvector/pgvector:pg15
sleep 3
psql postgresql://postgres:test@localhost:55432/postgres -c 'CREATE TABLE IF NOT EXISTS persons(id text, tenant_id text, display_name text, deleted_at timestamptz, status text);'
psql -v ON_ERROR_STOP=1 postgresql://postgres:test@localhost:55432/postgres -f apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202605270001__person_displayname_index.sql
docker rm -f pg-smoke
```

Expected: `CREATE INDEX` returned with no error.

- [ ] **Step 3: Commit**

```bash
git add meeting-api-infrastructure/src/main/resources/db/migration/V202605270001__person_displayname_index.sql
git commit -m "feat(meeting-api): Flyway index for persons displayName dedup"
```

---

### Task 5: PersonApplicationService — TDD

**Files:**
- Create: `meeting-api-app/src/test/java/com/meeting/api/app/person/PersonApplicationServiceTest.java`
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/person/PersonApplicationService.java`

- [ ] **Step 1: Failing test**

```java
package com.meeting.api.app.person;

import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonApplicationServiceTest {

    private final PersonRepository repo = mock(PersonRepository.class);
    private final FakeTenantScopedTransaction tx = new FakeTenantScopedTransaction();  // copy from other *ServiceTest files
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC);
    private final PersonApplicationService svc = new PersonApplicationService(repo, tx, clock);

    @Test
    void create_happy_path_returns_dto() {
        when(repo.findByDisplayName("t1", "李四")).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PersonDTO dto = svc.create(new CreatePersonCommand("t1", "李四", "lisi@example.com",
                                                          null, false, "u1", "req1", "tr1", "idem1"));
        assertThat(dto.displayName()).isEqualTo("李四");
        assertThat(dto.email()).isEqualTo("lisi@example.com");
    }

    @Test
    void create_duplicate_short_circuits_when_force_false() {
        Person existing = new Person("p1", "t1", "李四", null, null, "ACTIVE", java.time.OffsetDateTime.now(clock));
        when(repo.findByDisplayName("t1", "李四")).thenReturn(List.of(existing));
        assertThatThrownBy(() -> svc.create(new CreatePersonCommand("t1", "李四", null, null, false, "u1", "req1", "tr1", "idem1")))
            .isInstanceOf(PersonDuplicateException.class)
            .satisfies(e -> assertThat(((PersonDuplicateException) e).matches()).hasSize(1));
        verify(repo, never()).save(any());
    }

    @Test
    void create_duplicate_with_force_creates_anyway() {
        Person existing = new Person("p1", "t1", "李四", null, null, "ACTIVE", java.time.OffsetDateTime.now(clock));
        when(repo.findByDisplayName("t1", "李四")).thenReturn(List.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PersonDTO dto = svc.create(new CreatePersonCommand("t1", "李四", null, null, true, "u1", "req1", "tr1", "idem1"));
        assertThat(dto.personId()).isNotEqualTo("p1");
    }

    @Test
    void create_blank_displayName_throws() {
        assertThatThrownBy(() -> svc.create(new CreatePersonCommand("t1", " ", null, null, false, "u1", "req1", "tr1", "idem1")))
            .hasMessageContaining("PERSON_DISPLAY_NAME_REQUIRED");
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=PersonApplicationServiceTest
```

Expected: FAIL — `PersonApplicationService` not found.

- [ ] **Step 3: Implement service**

```java
package com.meeting.api.app.person;

import com.meeting.api.app.common.TenantScopedTransaction;  // adjust to actual package
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PersonApplicationService implements com.meeting.api.client.person.PersonFacade {
    private final PersonRepository repo;
    private final TenantScopedTransaction tx;
    private final Clock clock;

    public PersonApplicationService(PersonRepository repo, TenantScopedTransaction tx, Clock clock) {
        this.repo = repo; this.tx = tx; this.clock = clock;
    }

    @Override
    public PersonDTO create(CreatePersonCommand c) {
        if (c.displayName() == null || c.displayName().isBlank()) {
            throw new IllegalArgumentException("PERSON_DISPLAY_NAME_REQUIRED");
        }
        return tx.execute(c.tenantId(), c.userId(), c.requestId(), () -> {
            if (!c.forceCreate()) {
                List<Person> dupes = repo.findByDisplayName(c.tenantId(), c.displayName().trim());
                if (!dupes.isEmpty()) {
                    throw new PersonDuplicateException(dupes.stream().map(PersonApplicationService::toDto).toList());
                }
            }
            Person p = new Person(
                UUID.randomUUID().toString(), c.tenantId(), c.displayName().trim(),
                c.email(), c.externalId(), "ACTIVE", OffsetDateTime.now(clock));
            repo.save(p);
            return toDto(p);
        });
    }

    @Override
    public List<PersonDTO> search(String tenantId, String q, int limit) {
        if (q == null || q.isBlank()) return List.of();
        return tx.execute(tenantId, null, null,
            () -> repo.searchByQuery(tenantId, q.trim(), Math.min(Math.max(limit, 1), 50))
                      .stream().map(PersonApplicationService::toDto).toList());
    }

    private static PersonDTO toDto(Person p) {
        return new PersonDTO(p.id(), p.displayName(), p.email(), p.externalRef(), p.createdAt());
    }
}
```

(If `TenantScopedTransaction` lives in another package, adjust import.)

- [ ] **Step 4: Test passes**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=PersonApplicationServiceTest
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add meeting-api-app/src/main/java/com/meeting/api/app/person/ meeting-api-app/src/test/java/com/meeting/api/app/person/
git commit -m "feat(meeting-api): PersonApplicationService with dedup-or-force semantics"
```

---

### Task 6: PersonController + integration test

**Files:**
- Create: `meeting-api-adapter/src/main/java/com/meeting/api/adapter/person/PersonController.java`
- Create: `meeting-api-start/src/test/java/com/meeting/api/PersonControllerIT.java`

- [ ] **Step 1: Controller**

```java
package com.meeting.api.adapter.person;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.client.person.PersonFacade;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/persons")
public class PersonController {
    private final PersonFacade facade;

    public PersonController(PersonFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PersonDTO>>> search(
        @RequestParam(value = "q", required = false) String q,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        List<PersonDTO> items = q == null || q.isBlank()
            ? List.of()
            : facade.search(TenantContextHolder.currentTenantId(), q, 20);
        return ResponseEntity.ok(ApiResponse.ok(items, requestId, traceId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PersonDTO>> create(
        @RequestBody CreatePersonRequest body,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        try {
            PersonDTO dto = facade.create(new CreatePersonCommand(
                TenantContextHolder.currentTenantId(),
                body.displayName(),
                body.email(),
                body.externalId(),
                body.forceCreate() != null && body.forceCreate(),
                userId, requestId, traceId, idempotencyKey
            ));
            return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
        } catch (PersonDuplicateException dup) {
            return ResponseEntity.status(409).body(
                ApiResponse.error("PERSON_DUPLICATE", "已存在同名人员", false,
                    Map.of("matches", dup.matches()), requestId, traceId));
        }
    }

    public record CreatePersonRequest(
        String displayName, String email, String externalId, Boolean forceCreate
    ) {}
}
```

(If `ApiResponse.error(...)` overload doesn't match this signature, grep an existing controller using error envelope and mirror it. `ControllerAdvice` may already handle the exception — see CLAUDE.md §11; if so, the try/catch becomes unnecessary, just throw `PersonDuplicateException`.)

- [ ] **Step 2: Integration test**

```java
package com.meeting.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonControllerIT extends AbstractTestcontainersIT {

    @Autowired TestRestTemplate http;

    @Test
    void create_then_search_roundtrips() {
        HttpHeaders h = authHeaders("t1", "u1");  // helper from base IT
        ResponseEntity<String> created = http.exchange("/api/persons", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>("""
                {"displayName":"张三","email":"zs@example.com"}
                """, h), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getBody()).contains("\"displayName\":\"张三\"");

        ResponseEntity<String> searched = http.exchange("/api/persons?q=zha",
            org.springframework.http.HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(searched.getBody()).contains("张三");
    }

    @Test
    void duplicate_returns_409_with_matches() {
        HttpHeaders h = authHeaders("t1", "u1");
        http.exchange("/api/persons", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>("""{"displayName":"重名"}""", h), String.class);
        ResponseEntity<String> second = http.exchange("/api/persons", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>("""{"displayName":"重名"}""", h), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("PERSON_DUPLICATE");
    }

    @Test
    void force_create_bypasses_duplicate() {
        HttpHeaders h = authHeaders("t1", "u1");
        http.exchange("/api/persons", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>("""{"displayName":"强制"}""", h), String.class);
        ResponseEntity<String> forced = http.exchange("/api/persons", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>("""{"displayName":"强制","forceCreate":true}""", h), String.class);
        assertThat(forced.getStatusCode().value()).isEqualTo(200);
    }
}
```

(Adjust `AbstractTestcontainersIT` to the project's actual base class — likely `BaseMeetingApiIT` or similar. Grep for `@SpringBootTest` in `meeting-api-start/src/test`.)

- [ ] **Step 3: Run unit tests + IT**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test
./mvnw verify -q -pl meeting-api-start -Dtest=PersonControllerIT
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add meeting-api-adapter/src/main/java/com/meeting/api/adapter/person/ meeting-api-start/src/test/java/com/meeting/api/PersonControllerIT.java
git commit -m "feat(meeting-api): POST /api/persons + duplicate 409 IT"
```

---

## P2.B — Generic file uploads

### Task 7: Domain + Client for generic file upload

**Files:**
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/storage/GenericFileUploadRepository.java`
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/storage/GenericFileUploadSession.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/GenericFileFacade.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/CreateGenericFileUploadCommand.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/CreateGenericFilePartCommand.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/CompleteGenericFileUploadCommand.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/AbortGenericFileUploadCommand.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/GenericFileUploadSessionDTO.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/GenericFileUploadPartDTO.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/storage/GenericFileCompleteDTO.java`

- [ ] **Step 1: Mirror AudioUploadFacade**

Read `meeting-api-client/src/main/java/com/meeting/api/client/storage/AudioUploadFacade.java` then create parallel `GenericFileFacade`. Method signatures (replace `meetingId` with no scope; tenantId only):

```java
package com.meeting.api.client.storage;
import java.util.Optional;
public interface GenericFileFacade {
    GenericFileUploadSessionDTO createSession(CreateGenericFileUploadCommand cmd);
    GenericFileUploadPartDTO createPart(CreateGenericFilePartCommand cmd);
    GenericFileCompleteDTO complete(CompleteGenericFileUploadCommand cmd);
    void abort(AbortGenericFileUploadCommand cmd);
    Optional<GenericFileUploadSessionDTO> get(String tenantId, String uploadId);
}
```

Define DTOs/Commands mirroring `AudioUploadFacade`'s siblings; substitute `meetingId` with `null` everywhere (or simply drop the field). `GenericFileCompleteDTO`:

```java
package com.meeting.api.client.storage;
public record GenericFileCompleteDTO(String fileId, String sha256, long sizeBytes, String contentType) {}
```

Repository port `GenericFileUploadRepository` mirrors `AudioUploadSessionRepository`.

- [ ] **Step 2: Compile**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-client -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add meeting-api-domain/src/main/java/com/meeting/api/domain/storage/ meeting-api-client/src/main/java/com/meeting/api/client/storage/Generic*
git commit -m "feat(meeting-api): generic file upload facade + commands + DTOs"
```

---

### Task 8: GenericFileUploadApplicationService + Controller

**Files:**
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/storage/GenericFileUploadApplicationService.java`
- Create: `meeting-api-app/src/test/java/com/meeting/api/app/storage/GenericFileUploadApplicationServiceTest.java`
- Create: `meeting-api-adapter/src/main/java/com/meeting/api/adapter/storage/FileUploadController.java`
- Create: `meeting-api-start/src/test/java/com/meeting/api/FileUploadControllerIT.java`

- [ ] **Step 1: Read AudioUploadApplicationService as template**

Open and read `meeting-api-app/.../app/storage/AudioUploadApplicationService.java` (or similar). Copy its structure verbatim; remove all references to `meetingId`. MIME whitelist enforcement is the only behavioral difference.

- [ ] **Step 2: TDD test for MIME whitelist**

```java
package com.meeting.api.app.storage;
// imports …
class GenericFileUploadApplicationServiceTest {
    @Test
    void rejects_disallowed_mime() {
        GenericFileUploadApplicationService svc = newSvc();
        CreateGenericFileUploadCommand cmd = new CreateGenericFileUploadCommand(
            "t1", "evil.exe", "application/x-msdownload", 1024L, "deadbeef".repeat(8),
            null, "u1", "req1", "tr1", "idem1");
        assertThatThrownBy(() -> svc.createSession(cmd))
            .hasMessageContaining("FILE_MIME_NOT_ALLOWED");
    }

    @Test
    void accepts_pdf() {
        GenericFileUploadApplicationService svc = newSvc();
        var sess = svc.createSession(new CreateGenericFileUploadCommand(
            "t1", "ref.pdf", "application/pdf", 1024L, "deadbeef".repeat(8),
            null, "u1", "req1", "tr1", "idem1"));
        assertThat(sess.uploadId()).isNotBlank();
    }
}
```

Set `MIME_WHITELIST = Set.of("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/plain", "text/markdown")`.

- [ ] **Step 3: Run failing test**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=GenericFileUploadApplicationServiceTest
```

Expected: FAIL.

- [ ] **Step 4: Implement service (mirror AudioUploadApplicationService, +MIME check at createSession entry)**

(Use the source you read in Step 1 as template — only diff is `MIME_WHITELIST.contains(cmd.contentType())` check at the very top of `createSession`.)

- [ ] **Step 5: Implement controller (mirror AudioUploadController; drop meetingId path param)**

```java
package com.meeting.api.adapter.storage;
// imports …
@RestController
@RequestMapping("/api/files")
public class FileUploadController {
    private final GenericFileFacade facade;
    public FileUploadController(GenericFileFacade facade) { this.facade = facade; }

    @PostMapping
    public ApiResponse<GenericFileUploadSessionDTO> create(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateRequest req
    ) {
        GenericFileUploadSessionDTO s = facade.createSession(new CreateGenericFileUploadCommand(
            TenantContextHolder.currentTenantId(), req.fileName(), req.contentType(),
            req.fileSizeBytes(), req.fileSha256(), req.partSizeBytes(),
            TenantContextHolder.currentUserId(), idempotencyKey, requestId, traceId));
        return ApiResponse.ok(s, requestId, traceId);
    }
    // mirror parts/complete/abort/get from AudioUploadController, sans meetingId

    public record CreateRequest(String fileName, String contentType, long fileSizeBytes,
                                String fileSha256, Integer partSizeBytes) {}
    // mirror other Request records
}
```

- [ ] **Step 6: IT mirroring AudioUploadControllerIT (happy path init → parts → complete; 415 path; abort path)**

(Read existing AudioUploadControllerIT then create FileUploadControllerIT with the same test methods.)

- [ ] **Step 7: Run unit + IT**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test
./mvnw verify -q -pl meeting-api-start -Dtest=FileUploadControllerIT
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add meeting-api-app/src/main/java/com/meeting/api/app/storage/Generic* meeting-api-app/src/test/java/com/meeting/api/app/storage/Generic* meeting-api-adapter/src/main/java/com/meeting/api/adapter/storage/FileUploadController.java meeting-api-start/src/test/java/com/meeting/api/FileUploadControllerIT.java
git commit -m "feat(meeting-api): POST /api/files generic multipart with MIME whitelist"
```

---

## P2.C — SpeakerAutoConfirmService + WorkerPhaseCompletedListener

### Task 9: SpeakerAutoConfirmService — TDD

**Files:**
- Create: `meeting-api-app/src/test/java/com/meeting/api/app/speaker/SpeakerAutoConfirmServiceTest.java`
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/speaker/SpeakerAutoConfirmService.java`

- [ ] **Step 1: Failing test**

```java
package com.meeting.api.app.speaker;

import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.ProcessingTask;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeakerAutoConfirmServiceTest {
    private final ProcessingTaskRepository taskRepo = mock(ProcessingTaskRepository.class);
    private final MeetingSpeakerRepository speakerRepo = mock(MeetingSpeakerRepository.class);
    private final MeetingSpeakerApplicationService confirmSvc = mock(MeetingSpeakerApplicationService.class);
    private final SpeakerAutoConfirmService svc = new SpeakerAutoConfirmService(taskRepo, speakerRepo, confirmSvc);

    @Test
    void confirms_top1_when_above_threshold() {
        when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithMeeting("t1", "m1")));
        when(speakerRepo.findByMeeting("t1", "m1")).thenReturn(List.of(
            speakerRec("SPEAKER_00", List.of("p1", "p2"), 0.92, "UNCONFIRMED"),
            speakerRec("SPEAKER_01", List.of("p3"), 0.60, "UNCONFIRMED")));
        svc.autoConfirmAboveThreshold("t1", "task1");
        verify(confirmSvc).confirm("t1", "m1", "SPEAKER_00", "p1", null, "auto-confirm@system");
        verify(confirmSvc, never()).confirm(eq("t1"), eq("m1"), eq("SPEAKER_01"), any(), any(), any());
    }

    @Test
    void skips_already_confirmed() {
        when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithMeeting("t1", "m1")));
        when(speakerRepo.findByMeeting("t1", "m1")).thenReturn(List.of(
            speakerRec("SPEAKER_00", List.of("p1"), 0.99, "CONFIRMED")));
        svc.autoConfirmAboveThreshold("t1", "task1");
        verify(confirmSvc, never()).confirm(any(), any(), any(), any(), any(), any());
    }

    @Test
    void no_candidates_no_score_does_nothing() {
        when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithMeeting("t1", "m1")));
        when(speakerRepo.findByMeeting("t1", "m1")).thenReturn(List.of(
            speakerRec("SPEAKER_00", List.of(), null, "UNCONFIRMED")));
        svc.autoConfirmAboveThreshold("t1", "task1");
        verify(confirmSvc, never()).confirm(any(), any(), any(), any(), any(), any());
    }

    @Test
    void single_confirm_failure_does_not_block_others() {
        when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithMeeting("t1", "m1")));
        when(speakerRepo.findByMeeting("t1", "m1")).thenReturn(List.of(
            speakerRec("SPEAKER_00", List.of("p1"), 0.95, "UNCONFIRMED"),
            speakerRec("SPEAKER_01", List.of("p2"), 0.90, "UNCONFIRMED")));
        doThrow(new RuntimeException("boom"))
            .when(confirmSvc).confirm("t1", "m1", "SPEAKER_00", "p1", null, "auto-confirm@system");
        svc.autoConfirmAboveThreshold("t1", "task1");
        verify(confirmSvc).confirm("t1", "m1", "SPEAKER_01", "p2", null, "auto-confirm@system");
    }

    // helpers — adjust ProcessingTask / MeetingSpeakerRecord constructors to match real records
    private static ProcessingTask taskWithMeeting(String tenant, String meetingId) {
        // pattern: real ProcessingTask record requires many fields; use a builder if present
        // else mock the only method used: task.meetingId()
        ProcessingTask t = mock(ProcessingTask.class);
        when(t.meetingId()).thenReturn(meetingId);
        when(t.tenantId()).thenReturn(tenant);
        return t;
    }
    private static MeetingSpeakerRepository.MeetingSpeakerRecord speakerRec(
            String label, List<String> candidates, Double score, String status) {
        return new MeetingSpeakerRepository.MeetingSpeakerRecord(
            label, /*globalLabel*/ label, /*confirmedPersonId*/ null, /*confirmedAt*/ null,
            status, score, candidates, /*confirmedBy*/ null);
        // ^^ order/fields per actual record definition — grep MeetingSpeakerRecord to adjust
    }
}
```

(Open `MeetingSpeakerRepository.java` near line 32 to align record-field order in the helper.)

- [ ] **Step 2: Failing test fails**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=SpeakerAutoConfirmServiceTest
```

Expected: FAIL — class not found.

- [ ] **Step 3: Implement**

```java
package com.meeting.api.app.speaker;

import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.MeetingSpeakerRecord;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.ProcessingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class SpeakerAutoConfirmService {
    private static final Logger log = LoggerFactory.getLogger(SpeakerAutoConfirmService.class);
    public static final double AUTO_CONFIRM_THRESHOLD = 0.85;
    public static final String AUTO_CONFIRM_ACTOR = "auto-confirm@system";

    private final ProcessingTaskRepository taskRepo;
    private final MeetingSpeakerRepository speakerRepo;
    private final MeetingSpeakerApplicationService confirmService;

    public SpeakerAutoConfirmService(ProcessingTaskRepository taskRepo,
                                     MeetingSpeakerRepository speakerRepo,
                                     MeetingSpeakerApplicationService confirmService) {
        this.taskRepo = taskRepo;
        this.speakerRepo = speakerRepo;
        this.confirmService = confirmService;
    }

    public void autoConfirmAboveThreshold(String tenantId, String taskId) {
        Optional<ProcessingTask> taskOpt = taskRepo.findById(tenantId, taskId);
        if (taskOpt.isEmpty() || taskOpt.get().meetingId() == null) {
            log.debug("auto_confirm_skipped_no_meeting task={} tenant={}", taskId, tenantId);
            return;
        }
        String meetingId = taskOpt.get().meetingId();
        for (MeetingSpeakerRecord r : speakerRepo.findByMeeting(tenantId, meetingId)) {
            try {
                if (!"UNCONFIRMED".equals(r.verificationStatus())) continue;
                if (r.candidatePersonIds() == null || r.candidatePersonIds().isEmpty()) continue;
                if (r.autoMatchScore() == null || r.autoMatchScore() < AUTO_CONFIRM_THRESHOLD) continue;
                String topPerson = r.candidatePersonIds().get(0);
                confirmService.confirm(tenantId, meetingId, r.speakerLabel(),
                                       topPerson, null, AUTO_CONFIRM_ACTOR);
                log.info("auto_confirm tenant={} task={} meeting={} label={} person={} score={}",
                    tenantId, taskId, meetingId, r.speakerLabel(), topPerson, r.autoMatchScore());
            } catch (RuntimeException ex) {
                log.warn("auto_confirm_label_failed task={} label={} reason={}",
                    taskId, r.speakerLabel(), ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Test passes**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=SpeakerAutoConfirmServiceTest
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add meeting-api-app/src/main/java/com/meeting/api/app/speaker/SpeakerAutoConfirmService.java meeting-api-app/src/test/java/com/meeting/api/app/speaker/SpeakerAutoConfirmServiceTest.java
git commit -m "feat(meeting-api): SpeakerAutoConfirmService — auto-confirm above 0.85"
```

---

### Task 10: Wire SpeakerAutoConfirmService into WorkerPhaseCompletedListener

**Files:**
- Modify: `meeting-api-app/src/main/java/com/meeting/api/app/task/WorkerPhaseCompletedListener.java`
- Modify: `meeting-api-start/src/test/java/com/meeting/api/WorkerPhaseCompletedListenerTest.java`

- [ ] **Step 1: Extend existing test**

Add to existing test class:

```java
@Test
void invokes_auto_confirm_before_llm_phase_when_not_held() {
    SpeakerAutoConfirmService autoConfirm = mock(SpeakerAutoConfirmService.class);
    WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(
        taskStepProgressService, taskRepo, javaLlmPhaseOrchestrator, autoConfirm);
    when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithHold("t1", "task1", false)));
    listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
        "t1", "task1", "MEETING_FULL_PIPELINE", ProcessingTaskStatus.RUNNING));
    InOrder order = inOrder(autoConfirm, javaLlmPhaseOrchestrator);
    order.verify(autoConfirm).autoConfirmAboveThreshold("t1", "task1");
    order.verify(javaLlmPhaseOrchestrator).run("t1", "task1");
}

@Test
void auto_confirm_exception_does_not_block_llm_phase() {
    SpeakerAutoConfirmService autoConfirm = mock(SpeakerAutoConfirmService.class);
    doThrow(new RuntimeException("auto-confirm boom"))
        .when(autoConfirm).autoConfirmAboveThreshold(any(), any());
    WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(
        taskStepProgressService, taskRepo, javaLlmPhaseOrchestrator, autoConfirm);
    when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithHold("t1", "task1", false)));
    listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
        "t1", "task1", "MEETING_FULL_PIPELINE", ProcessingTaskStatus.RUNNING));
    verify(javaLlmPhaseOrchestrator).run("t1", "task1");
}

@Test
void held_task_skips_auto_confirm_and_llm() {
    SpeakerAutoConfirmService autoConfirm = mock(SpeakerAutoConfirmService.class);
    WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(
        taskStepProgressService, taskRepo, javaLlmPhaseOrchestrator, autoConfirm);
    when(taskRepo.findById("t1", "task1")).thenReturn(Optional.of(taskWithHold("t1", "task1", true)));
    listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
        "t1", "task1", "MEETING_FULL_PIPELINE", ProcessingTaskStatus.RUNNING));
    verify(autoConfirm, never()).autoConfirmAboveThreshold(any(), any());
    verify(javaLlmPhaseOrchestrator, never()).run(any(), any());
}
```

- [ ] **Step 2: Run — expect FAIL (constructor mismatch)**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=WorkerPhaseCompletedListenerTest
```

Expected: FAIL or compile error.

- [ ] **Step 3: Update listener**

Modify `WorkerPhaseCompletedListener.java`:

```java
// add field
private final SpeakerAutoConfirmService speakerAutoConfirmService;

// add new primary @Autowired constructor (4 args)
@Autowired
public WorkerPhaseCompletedListener(
    TaskStepProgressService taskStepProgressService,
    ProcessingTaskRepository taskRepository,
    JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator,
    SpeakerAutoConfirmService speakerAutoConfirmService
) {
    this.taskStepProgressService = taskStepProgressService;
    this.taskRepository = taskRepository;
    this.javaLlmPhaseOrchestrator = javaLlmPhaseOrchestrator;
    this.speakerAutoConfirmService = speakerAutoConfirmService;
}

// keep the two-arg / three-arg constructors as test seams, passing null/no-op for autoConfirm.
// In onWorkerPhaseCompleted, replace the body of the MEETING_FULL_PIPELINE branch:
if (MEETING_FULL_PIPELINE.equals(event.taskType())) {
    Optional<ProcessingTask> taskOpt = taskRepository.findById(event.tenantId(), event.taskId());
    if (taskOpt.isPresent() && taskOpt.get().holdAtWorkerPhase()) {
        log.info("worker_phase_completed_held task={} tenant={}", event.taskId(), event.tenantId());
        return;
    }
    if (speakerAutoConfirmService != null) {
        try {
            speakerAutoConfirmService.autoConfirmAboveThreshold(event.tenantId(), event.taskId());
        } catch (RuntimeException ex) {
            log.warn("auto_confirm_failed task={} tenant={} reason={}", event.taskId(), event.tenantId(), ex.getMessage());
        }
    }
    if (javaLlmPhaseOrchestrator != null) {
        javaLlmPhaseOrchestrator.run(event.tenantId(), event.taskId());
    } else {
        taskStepProgressService.beginJavaPhase(event.tenantId(), event.taskId());
    }
    log.info("worker_phase_completed_started_java_llm task={} tenant={}", event.taskId(), event.tenantId());
    return;
}
```

- [ ] **Step 4: Tests pass**

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -pl meeting-api-app -am test -Dtest=WorkerPhaseCompletedListenerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add meeting-api-app/src/main/java/com/meeting/api/app/task/WorkerPhaseCompletedListener.java meeting-api-start/src/test/java/com/meeting/api/WorkerPhaseCompletedListenerTest.java
git commit -m "feat(meeting-api): invoke SpeakerAutoConfirmService before LLM phase"
```

---

### Task 11: Full P2 phase gate

- [ ] **Step 1: Run full unit suite**

```bash
cd apps/meeting-api
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test
```

Expected: BUILD SUCCESS, all tests green, including ArchUnit `ArchitectureBoundaryTest`.

- [ ] **Step 2: Run integration tests (requires Docker)**

```bash
./mvnw verify -q
```

Expected: BUILD SUCCESS — including `PersonControllerIT`, `FileUploadControllerIT`, `JdbcPersonRepositoryIT`, `MeetingFinalizeFlowIT`.

- [ ] **Step 3: DDL check**

```bash
docker run --rm -e POSTGRES_PASSWORD=test -p 55432:5432 -d --name pg-ddl pgvector/pgvector:pg15
sleep 4
for sql in apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql; do
  psql -v ON_ERROR_STOP=1 postgresql://postgres:test@localhost:55432/postgres -f "$sql"
done
docker rm -f pg-ddl
```

Expected: every migration applies cleanly.

**P2 complete.**
