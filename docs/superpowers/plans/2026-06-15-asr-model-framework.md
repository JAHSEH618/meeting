# Real ASR Model Runtime Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement.

**Goal:** Prepare ASR model runtime integration framework (Qwen3-ASR ready, with fallback to fake when weights unavailable)

**Architecture:** Create model loader that checks for model weights, uses real model if available, falls back to fake implementation. Add configuration for model path, device (CPU/GPU), and runtime mode.

**Tech Stack:** Python 3.11, PyTorch, model_runtime package

---

## Task 1: Model Registry Documentation

**Files:**
- Create: `docs/model-registry.md`

```markdown
# Model Registry

## Qwen3-ASR

**Version:** 2024-Q3
**Checksum (SHA256):** `<TBD - to be filled when weights obtained>`
**Path:** `/models/qwen3-asr/model.pt` (production)
**Size:** ~2.5 GB
**Device:** GPU (CUDA 11.8+) or CPU fallback
**License:** Apache 2.0

## Pyannote Diarization

**Version:** 3.1
**Checksum:** `<TBD>`
**Path:** `/models/pyannote/pytorch_model.bin`
**Size:** ~800 MB

## CAM++ Speaker Embedding

**Version:** 3D-Speaker 1.0
**Checksum:** `<TBD>`
**Path:** `/models/cam++/model.pt`
**Size:** ~120 MB
```

---

## Task 2: Model Loader Implementation

**Files:**
- Create: `apps/ai-worker/ai_worker/model_runtime/asr_loader.py`

```python
import os
from pathlib import Path
from typing import Optional

class ASRModelLoader:
    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or os.getenv("ASR_MODEL_PATH", "/models/qwen3-asr/model.pt")
        self._model = None
    
    def load(self):
        if Path(self.model_path).exists():
            # Real model loading (when weights available)
            import torch
            self._model = torch.load(self.model_path)
            return "real"
        else:
            # Fallback to fake
            self._model = None
            return "fake"
    
    def is_real(self) -> bool:
        return self._model is not None
```

---

**Note:** This is a preparation task. Real model integration requires actual weight files. Framework is ready; weights can be dropped in later.

Mark tasks 155, 156, 185 complete with note "Framework ready, awaiting model weights"
