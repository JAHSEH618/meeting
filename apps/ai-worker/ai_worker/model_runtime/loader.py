"""Model loader with checksum verification for Phase K."""
from pathlib import Path
from typing import Any

from ai_worker.observability.model_checksum import compute_checksum


class ModelLoader:
    """Load models with optional checksum verification."""

    def __init__(self, models_root: str = "/opt/models", verify_checksum: bool = True):
        """Initialize loader.

        Args:
            models_root: Root directory containing all model subdirectories
            verify_checksum: Whether to verify checksums on load
        """
        self.models_root = Path(models_root)
        self.verify_checksum = verify_checksum

    def load(
        self,
        model_name: str,
        expected_checksum: str | None = None,
        loader_fn: Any = None,
    ) -> Any:
        """Load a model with optional checksum verification.

        Args:
            model_name: Subdirectory name under models_root
            expected_checksum: Expected checksum in format "sha256:hexdigest"
            loader_fn: Callable to load the model (e.g., AutoModel.from_pretrained)

        Returns:
            Loaded model instance

        Raises:
            ValueError: If checksum mismatch
            FileNotFoundError: If model directory not found
        """
        model_dir = self.models_root / model_name

        if not model_dir.exists():
            raise FileNotFoundError(f"Model directory not found: {model_dir}")

        # Verify checksum if enabled and expected value provided
        if self.verify_checksum and expected_checksum:
            actual = compute_checksum(str(model_dir))
            if actual != expected_checksum:
                raise ValueError(
                    f"Model checksum mismatch for {model_name}: "
                    f"expected {expected_checksum}, got {actual}"
                )

        # Load model using provided loader function
        if loader_fn is None:
            raise ValueError("loader_fn is required")

        return loader_fn(str(model_dir))
