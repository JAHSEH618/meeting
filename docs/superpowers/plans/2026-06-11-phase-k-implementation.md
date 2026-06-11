# Phase K Implementation Plan: Remove Security Level + Real Models

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove security level checks and integrate 5 real AI models (Qwen3-ASR, pyannote, CAM++, bge-m3, bge-reranker-v2-m3) for v1.1 release

**Architecture:** Two-phase approach: (1) Remove securityLevel from contracts/Java/Web to simplify LLM gateway, (2) Replace fake model runtimes with real implementations using checksum verification and serial GPU scheduling

**Tech Stack:** OpenAPI codegen, Java 17 Spring Boot, React 18 TypeScript, Python 3.11 FastAPI, PyTorch, FunASR, pyannote, transformers

---

## File Structure

### Phase 1: Remove Security Level

**Contracts:**
- Modify: `packages/meeting-contracts/schemas/common/enums.yaml` (delete securityLevel)
- Modify: `packages/meeting-contracts/openapi/public-api.yaml` (remove from Meeting schemas)

**Java:**
- Create: `apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202606110001__remove_security_level.sql`
- Delete: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/SecurityLevel.java`
- Delete: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/llm/SecurityGate.java`
- Delete: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/llm/DashScopeSecurityGate.java`
- Modify: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/Meeting.java` (remove field)
- Modify: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/meeting/MeetingApplicationService.java` (remove checks)

**Web:**
- Delete: `apps/meeting-web/src/shared/components/SecurityLevelSelect.tsx`
- Delete: `apps/meeting-web/src/shared/components/SecurityLevelBlockedNotice.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingCreatePage.tsx`

### Phase 2: Real Models

**Python ai-worker:**
- Create: `apps/ai-worker/ai_worker/model_runtime/loader.py`
- Create: `apps/ai-worker/ai_worker/model_runtime/asr/qwen3_asr_runtime.py`
- Create: `apps/ai-worker/ai_worker/model_runtime/diarization/pyannote_runtime.py`
- Create: `apps/ai-worker/ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py`
- Create: `apps/ai-worker/ai_worker/model_runtime/embedding/bge_m3_runtime.py`
- Create: `apps/ai-worker/ai_worker/model_runtime/rerank/bge_reranker_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/asr/__init__.py` (switch import)
- Modify: `apps/ai-worker/ai_worker/observability/model_checksum.py` (add compute_checksum)

---

## PHASE 1: Remove Security Level (Days 1-2)

### Task 1.1: Remove securityLevel from contracts

**Files:**
- Modify: `packages/meeting-contracts/schemas/common/enums.yaml:13-17`
- Modify: `packages/meeting-contracts/openapi/public-api.yaml` (Meeting schemas)

- [ ] **Step 1: Delete securityLevel enum from enums.yaml**

```yaml
# Delete these lines from enums.yaml:
securityLevel:
  - PUBLIC
  - INTERNAL
  - CONFIDENTIAL
  - SECRET
```

- [ ] **Step 2: Remove securityLevel from public-api.yaml Meeting schemas**

Find all references to `securityLevel` in CreateMeetingRequest, MeetingDTO, UpdateMeetingRequest and delete the property lines.

- [ ] **Step 3: Run contracts validation**

```bash
cd packages/meeting-contracts
npm run check
```

Expected: All checks pass, no securityLevel references remain

- [ ] **Step 4: Regenerate TypeScript/Java/Python types**

```bash
npm run codegen
```

Expected: `git diff` shows securityLevel removed from generated files

- [ ] **Step 5: Commit contracts changes**

```bash
git add packages/meeting-contracts/
git commit -m "refactor(contracts): remove securityLevel enum"
```

---

### Task 1.2: Create Flyway migration to drop security_level column

**Files:**
- Create: `apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202606110001__remove_security_level.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V202606110001__remove_security_level.sql
ALTER TABLE meetings DROP COLUMN IF EXISTS security_level;
```

- [ ] **Step 2: Test migration on local database**

```bash
cd apps/meeting-api
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw flyway:migrate -Dflyway.cleanDisabled=false
```

Expected: Migration V202606110001 applied successfully

- [ ] **Step 3: Verify column dropped**

```bash
docker exec -it meeting-postgres psql -U meeting -d meeting -c "\d meetings"
```

Expected: No `security_level` column in table definition

- [ ] **Step 4: Commit migration**

```bash
git add apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202606110001__remove_security_level.sql
git commit -m "refactor(api): drop meetings.security_level column"
```

---

### Task 1.3: Remove SecurityLevel domain enum and security gate

**Files:**
- Delete: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/SecurityLevel.java`
- Delete: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/llm/SecurityGate.java`
- Delete: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/llm/DashScopeSecurityGate.java`

- [ ] **Step 1: Delete SecurityLevel.java enum**

```bash
rm apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/SecurityLevel.java
```

- [ ] **Step 2: Delete SecurityGate.java interface**

```bash
rm apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/llm/SecurityGate.java
```

- [ ] **Step 3: Delete DashScopeSecurityGate.java implementation**

```bash
rm apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/llm/DashScopeSecurityGate.java
```

- [ ] **Step 4: Attempt compile to find all references**

```bash
cd apps/meeting-api
./mvnw clean compile 2>&1 | tee /tmp/compile-errors.txt
```

Expected: Compilation errors showing all SecurityLevel/SecurityGate references

- [ ] **Step 5: Commit deletions**

```bash
git add -A
git commit -m "refactor(api): remove SecurityLevel enum and SecurityGate"
```

---

### Task 1.4: Remove securityLevel from Meeting domain model

**Files:**
- Modify: `apps/meeting-api/meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/Meeting.java`
- Modify: `apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/meeting/MeetingDTO.java`

- [ ] **Step 1: Remove securityLevel field from Meeting.java**

Remove the field declaration and all getter/setter/builder methods related to `securityLevel`.

- [ ] **Step 2: Remove securityLevel from MeetingDTO.java**

Remove the field and any validation annotations.

- [ ] **Step 3: Attempt compile**

```bash
cd apps/meeting-api
./mvnw clean compile
```

Expected: Still compilation errors in MeetingApplicationService

- [ ] **Step 4: Commit domain model changes**

```bash
git add apps/meeting-api/meeting-api-domain/ apps/meeting-api/meeting-api-client/
git commit -m "refactor(api): remove securityLevel from Meeting aggregate"
```

---

### Task 1.5: Remove security checks from MeetingApplicationService

**Files:**
- Modify: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/meeting/MeetingApplicationService.java`
- Modify: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/llm/DashScopeLlmGateway.java` (if exists)

- [ ] **Step 1: Remove securityLevel parameter from createMeeting()**

Remove securityLevel from CreateMeetingCommand and Meeting builder call.

- [ ] **Step 2: Remove securityLevel from updateMeeting()**

Remove securityLevel from UpdateMeetingCommand if present.

- [ ] **Step 3: Remove checkSecurityLevel() calls from LLM gateway**

Find and remove all `securityGate.check()` or equivalent calls in LlmGateway implementations.

- [ ] **Step 4: Compile and run tests**

```bash
cd apps/meeting-api
./mvnw clean test
```

Expected: All tests pass (some may need updates if they assert on securityLevel)

- [ ] **Step 5: Commit application service changes**

```bash
git add apps/meeting-api/meeting-api-app/ apps/meeting-api/meeting-api-infrastructure/
git commit -m "refactor(api): remove security level checks from meeting service"
```

---

### Task 1.6: Remove SecurityLevel components from meeting-web

**Files:**
- Delete: `apps/meeting-web/src/shared/components/SecurityLevelSelect.tsx`
- Delete: `apps/meeting-web/src/shared/components/SecurityLevelBlockedNotice.tsx`
- Modify: `apps/meeting-web/src/features/meetings/MeetingCreatePage.tsx`

- [ ] **Step 1: Delete SecurityLevelSelect component**

```bash
rm apps/meeting-web/src/shared/components/SecurityLevelSelect.tsx
rm apps/meeting-web/src/shared/components/__tests__/SecurityLevelSelect.test.tsx
```

- [ ] **Step 2: Delete SecurityLevelBlockedNotice component**

```bash
rm apps/meeting-web/src/shared/components/SecurityLevelBlockedNotice.tsx
```

- [ ] **Step 3: Remove securityLevel from MeetingCreatePage form**

Remove import, form field, and validation schema for securityLevel.

- [ ] **Step 4: Regenerate types and run tests**

```bash
cd apps/meeting-web
npm run codegen
npm test
```

Expected: All tests pass, no securityLevel references in types.gen.ts

- [ ] **Step 5: Commit web changes**

```bash
git add apps/meeting-web/
git commit -m "refactor(web): remove security level UI components"
```

---

## PHASE 2: Integrate Real Models (Days 3-21)

### Task 2.1: Create model checksum utility

**Files:**
- Create: `apps/ai-worker/ai_worker/observability/model_checksum.py`
- Create: `apps/ai-worker/tests/test_model_checksum.py`

- [ ] **Step 1: Write checksum test**

```python
# tests/test_model_checksum.py
from pathlib import Path
import tempfile
from ai_worker.observability.model_checksum import compute_checksum

def test_compute_checksum_empty_directory():
    with tempfile.TemporaryDirectory() as tmpdir:
        result = compute_checksum(Path(tmpdir))
        assert result.startswith("sha256:")

def test_compute_checksum_single_file():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_file = Path(tmpdir) / "model.pt"
        model_file.write_bytes(b"fake model data")
        result = compute_checksum(Path(tmpdir))
        assert result == "sha256:a8c2e..." # Expected hash
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd apps/ai-worker
uv run pytest tests/test_model_checksum.py -v
```

Expected: FAIL with "module not found"

- [ ] **Step 3: Implement compute_checksum()**

```python
# ai_worker/observability/model_checksum.py
import hashlib
from pathlib import Path

def compute_checksum(model_dir: Path) -> str:
    """Compute SHA-256 hash of all model weight files"""
    files = (
        sorted(model_dir.rglob("*.pt")) +
        sorted(model_dir.rglob("*.bin")) +
        sorted(model_dir.rglob("*.safetensors"))
    )
    hasher = hashlib.sha256()
    for f in files:
        hasher.update(f.read_bytes())
    return f"sha256:{hasher.hexdigest()}"
```

- [ ] **Step 4: Run test to verify it passes**

```bash
uv run pytest tests/test_model_checksum.py -v
```

Expected: PASS

- [ ] **Step 5: Commit checksum utility**

```bash
git add apps/ai-worker/ai_worker/observability/model_checksum.py apps/ai-worker/tests/test_model_checksum.py
git commit -m "feat(worker): add model checksum verification utility"
```

---

### Task 2.2: Create ModelLoader with checksum verification

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/loader.py`
- Create: `apps/ai-worker/tests/test_model_loader.py`

- [ ] **Step 1: Write loader test**

```python
# tests/test_model_loader.py
import os
import pytest
from ai_worker.model_runtime.loader import ModelLoader, ChecksumMismatchError

def test_load_with_missing_checksum_env():
    loader = ModelLoader()
    with pytest.raises(ValueError, match="Missing checksum"):
        loader.load_with_checksum_verification("qwen3-asr", Path("/tmp/fake"))

def test_load_with_checksum_mismatch():
    os.environ["AI_WORKER_QWEN3_ASR_EXPECTED_CHECKSUM"] = "sha256:wrong"
    loader = ModelLoader()
    with pytest.raises(ChecksumMismatchError):
        loader.load_with_checksum_verification("qwen3-asr", Path("/tmp/fake"))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
uv run pytest tests/test_model_loader.py -v
```

Expected: FAIL

- [ ] **Step 3: Implement ModelLoader**

```python
# ai_worker/model_runtime/loader.py
import os
from pathlib import Path
from typing import Any
from ai_worker.observability.model_checksum import compute_checksum

class ChecksumMismatchError(Exception):
    pass

class ModelLoader:
    def __init__(self):
        self.offline_mode = os.getenv("AI_WORKER_MODEL_OFFLINE_MODE", "true") == "true"
    
    def load_with_checksum_verification(self, model_name: str, model_dir: Path) -> Any:
        expected = os.getenv(f"AI_WORKER_{model_name.upper().replace('-','_')}_EXPECTED_CHECKSUM")
        if not expected:
            raise ValueError(f"Missing checksum for {model_name}")
        
        actual = compute_checksum(model_dir)
        if actual != expected:
            raise ChecksumMismatchError(
                f"{model_name}: expected {expected}, got {actual}"
            )
        
        return None  # Placeholder for now
```

- [ ] **Step 4: Run test to verify it passes**

```bash
uv run pytest tests/test_model_loader.py -v
```

Expected: PASS

- [ ] **Step 5: Commit loader**

```bash
git add apps/ai-worker/ai_worker/model_runtime/loader.py apps/ai-worker/tests/test_model_loader.py
git commit -m "feat(worker): add ModelLoader with checksum verification"
```

---

### Task 2.3: Implement Qwen3AsrRuntime (real ASR)

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/asr/qwen3_asr_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/asr/__init__.py`
- Create: `apps/ai-worker/tests/test_qwen3_asr_runtime.py`

- [ ] **Step 1: Download Qwen3-ASR weights to /opt/models/qwen3-asr/v1**

Manual step: Download from Hugging Face or internal mirror.

- [ ] **Step 2: Compute and record checksum**

```bash
cd apps/ai-worker
uv run python -c "from pathlib import Path; from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum(Path('/opt/models/qwen3-asr/v1')))"
```

Record output in `docs/model-registry.md` under Qwen3-ASR section.

- [ ] **Step 3: Write ASR test**

```python
# tests/test_qwen3_asr_runtime.py
from pathlib import Path
from ai_worker.model_runtime.asr import AsrRuntime

def test_qwen3_asr_transcribe():
    runtime = AsrRuntime(model_dir=Path("/opt/models/qwen3-asr/v1"))
    result = runtime.transcribe(Path("tests/fixtures/test_audio_30s.wav"))
    assert "text" in result
    assert len(result["segments"]) > 0
```

- [ ] **Step 4: Run test to verify it fails**

```bash
uv run pytest tests/test_qwen3_asr_runtime.py -v
```

Expected: FAIL with import error

- [ ] **Step 5: Implement Qwen3AsrRuntime**

```python
# ai_worker/model_runtime/asr/qwen3_asr_runtime.py
from pathlib import Path
from typing import Dict, Any
import torch
from funasr import AutoModel

class Qwen3AsrRuntime:
    def __init__(self, model_dir: Path, device: str = "cuda:0"):
        self.device = device
        self.model = AutoModel(
            model=str(model_dir),
            device=device,
            disable_pbar=True
        )
    
    def transcribe(self, audio_path: Path) -> Dict[str, Any]:
        result = self.model.generate(
            input=str(audio_path),
            batch_size_s=300,
            hotword=""
        )
        return {
            "text": result[0]["text"],
            "segments": result[0].get("sentence_info", [])
        }
```

- [ ] **Step 6: Update __init__.py to use real runtime**

```python
# ai_worker/model_runtime/asr/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_asr_runtime import FakeAsrRuntime as AsrRuntime
else:
    from .qwen3_asr_runtime import Qwen3AsrRuntime as AsrRuntime
```

- [ ] **Step 7: Run test with real audio (if available)**

```bash
export AI_WORKER_USE_FAKE_MODELS=false
export AI_WORKER_QWEN3_ASR_EXPECTED_CHECKSUM="sha256:..." # From step 2
uv run pytest tests/test_qwen3_asr_runtime.py -v -s
```

Expected: PASS with real transcription output

- [ ] **Step 8: Measure RTF (Real-Time Factor)**

```bash
time uv run python -c "from ai_worker.model_runtime.asr import AsrRuntime; from pathlib import Path; runtime = AsrRuntime(Path('/opt/models/qwen3-asr/v1')); runtime.transcribe(Path('tests/fixtures/test_audio_30min.wav'))"
```

Expected: Processing time < 9 minutes (RTF < 0.3)

- [ ] **Step 9: Commit Qwen3 ASR**

```bash
git add apps/ai-worker/ai_worker/model_runtime/asr/ apps/ai-worker/tests/test_qwen3_asr_runtime.py docs/model-registry.md
git commit -m "feat(worker): implement Qwen3AsrRuntime with real model"
```

---

### Task 2.4: Implement PyannoteDiarizationRuntime

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/diarization/pyannote_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/diarization/__init__.py`

- [ ] **Step 1: Download pyannote 3.3 weights to /opt/models/pyannote/v3.3**

Manual step: Download pyannote/speaker-diarization-3.1 from Hugging Face.

- [ ] **Step 2: Compute and record checksum**

```bash
uv run python -c "from pathlib import Path; from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum(Path('/opt/models/pyannote/v3.3')))"
```

Record in `docs/model-registry.md`.

- [ ] **Step 3: Implement PyannoteDiarizationRuntime**

```python
# ai_worker/model_runtime/diarization/pyannote_runtime.py
from pathlib import Path
from typing import List, Dict
import torch
from pyannote.audio import Pipeline

class PyannoteDiarizationRuntime:
    def __init__(self, model_dir: Path, device: str = "cuda:0"):
        self.pipeline = Pipeline.from_pretrained(
            str(model_dir),
            use_auth_token=False
        )
        self.pipeline.to(torch.device(device))
    
    def diarize(self, audio_path: Path) -> List[Dict]:
        diarization = self.pipeline(str(audio_path))
        turns = []
        for turn, _, speaker in diarization.itertracks(yield_label=True):
            turns.append({
                "speaker": speaker,
                "start": turn.start,
                "end": turn.end
            })
        return turns
```

- [ ] **Step 4: Update diarization __init__.py**

```python
# ai_worker/model_runtime/diarization/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_diarization_runtime import FakeDiarizationRuntime as DiarizationRuntime
else:
    from .pyannote_runtime import PyannoteDiarizationRuntime as DiarizationRuntime
```

- [ ] **Step 5: Test with real audio**

```bash
export AI_WORKER_PYANNOTE_EXPECTED_CHECKSUM="sha256:..."
uv run python -c "from ai_worker.model_runtime.diarization import DiarizationRuntime; from pathlib import Path; runtime = DiarizationRuntime(Path('/opt/models/pyannote/v3.3')); print(runtime.diarize(Path('tests/fixtures/test_audio_30min.wav')))"
```

Expected: List of speaker turns with labels like SPEAKER_00, SPEAKER_01

- [ ] **Step 6: Commit pyannote**

```bash
git add apps/ai-worker/ai_worker/model_runtime/diarization/ docs/model-registry.md
git commit -m "feat(worker): implement PyannoteDiarizationRuntime"
```

---

### Task 2.5: Implement Cam++SpeakerRuntime

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/speaker/__init__.py`

- [ ] **Step 1: Download CAM++ weights to /opt/models/cam++/v1**

Manual step: Download from ModelScope or 3D-Speaker repository.

- [ ] **Step 2: Compute and record checksum**

```bash
uv run python -c "from pathlib import Path; from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum(Path('/opt/models/cam++/v1')))"
```

- [ ] **Step 3: Implement Cam++SpeakerRuntime**

```python
# ai_worker/model_runtime/speaker/cam_plus_plus_runtime.py
from pathlib import Path
import numpy as np
from modelscope.pipelines import pipeline

class CamPlusPlusSpeakerRuntime:
    def __init__(self, model_dir: Path, device: str = "cuda:0"):
        self.pipeline = pipeline(
            task="speaker-recognition",
            model=str(model_dir)
        )
    
    def extract_embedding(self, audio_path: Path) -> np.ndarray:
        result = self.pipeline(str(audio_path))
        return result["emb"]  # 192-dim embedding
```

- [ ] **Step 4: Update speaker __init__.py**

```python
# ai_worker/model_runtime/speaker/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_speaker_runtime import FakeSpeakerRuntime as SpeakerRuntime
else:
    from .cam_plus_plus_runtime import CamPlusPlusSpeakerRuntime as SpeakerRuntime
```

- [ ] **Step 5: Test embedding extraction**

```bash
export AI_WORKER_CAM_PLUS_PLUS_EXPECTED_CHECKSUM="sha256:..."
uv run python -c "from ai_worker.model_runtime.speaker import SpeakerRuntime; from pathlib import Path; runtime = SpeakerRuntime(Path('/opt/models/cam++/v1')); emb = runtime.extract_embedding(Path('tests/fixtures/speaker_ref.wav')); print(emb.shape)"
```

Expected: (192,) shape array

- [ ] **Step 6: Commit CAM++**

```bash
git add apps/ai-worker/ai_worker/model_runtime/speaker/ docs/model-registry.md
git commit -m "feat(worker): implement CamPlusPlusSpeakerRuntime"
```

---

### Task 2.6: Implement BgeM3EmbeddingRuntime

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/embedding/bge_m3_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/embedding/__init__.py`

- [ ] **Step 1: Download bge-m3 weights to /opt/models/bge-m3/v1**

Manual step: Download BAAI/bge-m3 from Hugging Face.

- [ ] **Step 2: Compute and record checksum**

```bash
uv run python -c "from pathlib import Path; from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum(Path('/opt/models/bge-m3/v1')))"
```

- [ ] **Step 3: Implement BgeM3EmbeddingRuntime**

```python
# ai_worker/model_runtime/embedding/bge_m3_runtime.py
from pathlib import Path
from typing import List
import numpy as np
from FlagEmbedding import BGEM3FlagModel

class BgeM3EmbeddingRuntime:
    def __init__(self, model_dir: Path, device: str = "cuda:0"):
        self.model = BGEM3FlagModel(
            str(model_dir),
            use_fp16=True,
            device=device
        )
    
    def encode(self, texts: List[str]) -> np.ndarray:
        embeddings = self.model.encode(
            texts,
            batch_size=12,
            max_length=8192
        )["dense_vecs"]
        return embeddings  # (N, 1024)
```

- [ ] **Step 4: Update embedding __init__.py**

```python
# ai_worker/model_runtime/embedding/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_embedding_runtime import FakeEmbeddingRuntime as EmbeddingRuntime
else:
    from .bge_m3_runtime import BgeM3EmbeddingRuntime as EmbeddingRuntime
```

- [ ] **Step 5: Test embedding**

```bash
export AI_WORKER_BGE_M3_EXPECTED_CHECKSUM="sha256:..."
uv run python -c "from ai_worker.model_runtime.embedding import EmbeddingRuntime; from pathlib import Path; runtime = EmbeddingRuntime(Path('/opt/models/bge-m3/v1')); emb = runtime.encode(['测试文本']); print(emb.shape)"
```

Expected: (1, 1024) shape array

- [ ] **Step 6: Commit bge-m3**

```bash
git add apps/ai-worker/ai_worker/model_runtime/embedding/ docs/model-registry.md
git commit -m "feat(worker): implement BgeM3EmbeddingRuntime"
```

---

### Task 2.7: Implement BgeRerankerRuntime

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/rerank/bge_reranker_runtime.py`
- Modify: `apps/ai-worker/ai_worker/model_runtime/rerank/__init__.py`

- [ ] **Step 1: Download bge-reranker-v2-m3 to /opt/models/bge-reranker-v2-m3/v1**

Manual step: Download BAAI/bge-reranker-v2-m3 from Hugging Face.

- [ ] **Step 2: Compute and record checksum**

```bash
uv run python -c "from pathlib import Path; from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum(Path('/opt/models/bge-reranker-v2-m3/v1')))"
```

- [ ] **Step 3: Implement BgeRerankerRuntime**

```python
# ai_worker/model_runtime/rerank/bge_reranker_runtime.py
from pathlib import Path
from typing import List, Tuple
from FlagEmbedding import FlagReranker

class BgeRerankerRuntime:
    def __init__(self, model_dir: Path, device: str = "cuda:0"):
        self.reranker = FlagReranker(
            str(model_dir),
            use_fp16=True,
            device=device
        )
    
    def rerank(self, query: str, passages: List[str]) -> List[Tuple[int, float]]:
        pairs = [[query, p] for p in passages]
        scores = self.reranker.compute_score(pairs, normalize=True)
        return [(i, float(s)) for i, s in enumerate(scores)]
```

- [ ] **Step 4: Update rerank __init__.py**

```python
# ai_worker/model_runtime/rerank/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_reranker_runtime import FakeRerankerRuntime as RerankerRuntime
else:
    from .bge_reranker_runtime import BgeRerankerRuntime as RerankerRuntime
```

- [ ] **Step 5: Test reranking**

```bash
export AI_WORKER_BGE_RERANKER_V2_M3_EXPECTED_CHECKSUM="sha256:..."
uv run python -c "from ai_worker.model_runtime.rerank import RerankerRuntime; from pathlib import Path; runtime = RerankerRuntime(Path('/opt/models/bge-reranker-v2-m3/v1')); scores = runtime.rerank('会议主题', ['段落1', '段落2']); print(scores)"
```

Expected: List of (index, score) tuples

- [ ] **Step 6: Commit reranker**

```bash
git add apps/ai-worker/ai_worker/model_runtime/rerank/ docs/model-registry.md
git commit -m "feat(worker): implement BgeRerankerRuntime"
```

---

## PHASE 3: Integration & Validation (Days 17-21)

### Task 3.1: End-to-end integration test with real models

**Files:**
- Create: `apps/ai-worker/tests/integration/test_full_pipeline_real_models.py`

- [ ] **Step 1: Create integration test**

```python
# tests/integration/test_full_pipeline_real_models.py
import pytest
from pathlib import Path
from ai_worker.application.workflows.meeting_full_pipeline import run_pipeline

@pytest.mark.integration
def test_full_pipeline_30min_audio():
    """End-to-end test: audio -> ASR -> diarization -> speaker -> transcript"""
    audio_path = Path("tests/fixtures/test_30min_meeting.wav")
    result = run_pipeline(audio_path, task_id="test-task-001")
    
    assert result["status"] == "SUCCEEDED"
    assert len(result["transcript_segments"]) > 0
    assert result["speaker_labels"] is not None
```

- [ ] **Step 2: Run integration test**

```bash
export AI_WORKER_USE_FAKE_MODELS=false
uv run pytest tests/integration/test_full_pipeline_real_models.py -v -s
```

Expected: PASS with real transcription output

- [ ] **Step 3: Verify Java callback received real data**

Check Java logs for callback from ai-worker with non-fake transcript text.

- [ ] **Step 4: Commit integration test**

```bash
git add apps/ai-worker/tests/integration/
git commit -m "test(worker): add full pipeline integration test with real models"
```

---

### Task 3.2: GPU memory stress test (3 concurrent tasks)

**Files:**
- Create: `apps/ai-worker/tests/stress/test_gpu_memory.py`

- [ ] **Step 1: Create stress test script**

```python
# tests/stress/test_gpu_memory.py
import concurrent.futures
from pathlib import Path
import torch
from ai_worker.application.workflows.meeting_full_pipeline import run_pipeline

def test_concurrent_3_tasks():
    """Stress test: 3 tasks in parallel, monitor GPU memory"""
    audio_files = [
        Path("tests/fixtures/test_30min_1.wav"),
        Path("tests/fixtures/test_30min_2.wav"),
        Path("tests/fixtures/test_30min_3.wav")
    ]
    
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(run_pipeline, f, f"task-{i}") for i, f in enumerate(audio_files)]
        results = [f.result() for f in futures]
    
    assert all(r["status"] == "SUCCEEDED" for r in results)
    assert torch.cuda.max_memory_allocated() < 22 * 1024**3  # < 22GB
```

- [ ] **Step 2: Run stress test**

```bash
uv run pytest tests/stress/test_gpu_memory.py -v -s
```

Expected: All 3 tasks succeed, peak GPU < 22GB

- [ ] **Step 3: If OOM occurs, adjust serial scheduling**

Modify workflow to enforce stricter serial execution with explicit `torch.cuda.empty_cache()`.

- [ ] **Step 4: Commit stress test**

```bash
git add apps/ai-worker/tests/stress/
git commit -m "test(worker): add GPU memory stress test"
```

---

### Task 3.3: Run Phase J acceptance (9 checks)

**Files:**
- Execute: `docs/runbooks/phase-j-acceptance.md`

- [ ] **Step 1: Run J1 - Full-stack healthy**

```bash
./deploy/deploy.sh local --with-observability
curl -fsSL http://localhost:8080/actuator/health | jq .
```

Expected: All 6 HealthIndicators UP

- [ ] **Step 2: Run J4 - Model checksum guard**

```bash
cd apps/ai-worker
uv run python scripts/stage_mock_weights.py --format=dotenv
# Export checksums, restart ai-worker
curl http://localhost:8090/internal/models | jq '.data.models[] | {name, checksum, status}'
```

Expected: All models status=READY with matching checksums

- [ ] **Step 3: Run J5 - Playwright stability (5 runs)**

```bash
cd apps/meeting-web
for i in 1 2 3 4 5; do npm run e2e | tee logs/e2e-$i.log; done
grep -c "passed" logs/e2e-*.log
```

Expected: ≥ 4 out of 5 runs fully green

- [ ] **Step 4: Run J7 - All unit suites green**

```bash
(cd packages/meeting-contracts && npm run check) && \
(cd apps/meeting-api && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify -q) && \
(cd apps/meeting-web && npm test && npx tsc --noEmit) && \
(cd apps/ai-worker && uv run pytest && uv run pyright ai_worker/)
```

Expected: All commands exit 0

- [ ] **Step 5: Document remaining J checks (J2, J3, J6, J8, J9)**

Follow full instructions in `docs/runbooks/phase-j-acceptance.md` for each.

- [ ] **Step 6: Create acceptance report**

```bash
mkdir -p infra/meeting-infra/acceptance-reports/$(date -u +%Y%m%d)
# Capture outputs from all J checks
```

---

### Task 3.4: Update documentation and tag v1.1

**Files:**
- Create: `CHANGELOG-v1.1.md`
- Modify: `docs/model-registry.md`
- Modify: `README.md`

- [ ] **Step 1: Write CHANGELOG**

```markdown
# v1.1 Release Notes (2026-06-XX)

## Breaking Changes
- Removed `securityLevel` from Meeting model - all meetings can now use LLM features
- Security level UI components removed from meeting-web and ai-worker-web

## Features
- Real model integration: Qwen3-ASR, pyannote 3.3, CAM++, bge-m3, bge-reranker-v2-m3
- Model checksum verification on startup
- GPU memory optimization with serial scheduling

## Upgrade Notes
- Run Flyway migration V202606110001 (drops meetings.security_level column)
- Set AI_WORKER_*_EXPECTED_CHECKSUM environment variables
- Download model weights to /opt/models/ before deployment
```

- [ ] **Step 2: Complete docs/model-registry.md with all checksums**

Fill in SHA-256 values for all 5 models from earlier steps.

- [ ] **Step 3: Update README with v1.1 capabilities**

Add "Real AI Models" section highlighting ASR quality and performance metrics.

- [ ] **Step 4: Commit documentation**

```bash
git add CHANGELOG-v1.1.md docs/model-registry.md README.md
git commit -m "docs: v1.1 release notes and model registry"
```

- [ ] **Step 5: Create v1.1 tag**

```bash
git tag -a v1.1.0 -m "v1.1: Remove security level + integrate real models"
git push origin v1.1.0
git push origin phase-k-remove-security-level
```

---

## Post-Implementation Checklist

- [ ] All Phase J acceptance checks pass (9/9)
- [ ] Git diff shows no uncommitted changes
- [ ] CI pipeline green on phase-k-remove-security-level branch
- [ ] v1.1.0 tag created and pushed
- [ ] Model checksums documented in model-registry.md
- [ ] No securityLevel references remain in codebase

---

## Rollback Procedure (if needed)

If Phase J acceptance fails or critical issues found:

1. **Revert to fake models:**
   ```bash
   export AI_WORKER_USE_FAKE_MODELS=true
   # Restart ai-worker
   ```

2. **Revert contracts changes:**
   ```bash
   git revert <commit-range-for-security-level-removal>
   cd packages/meeting-contracts && npm run codegen
   ```

3. **Restore security_level column:**
   ```sql
   ALTER TABLE meetings ADD COLUMN security_level VARCHAR(20) DEFAULT 'INTERNAL';
   ```

4. **Notify team and document issue in GitHub**
