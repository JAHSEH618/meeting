#!/usr/bin/env python3
"""Verify hand-written ExportJobMessage DTO fields match generated model."""
import re
import sys
from pathlib import Path


def fields_from_handwritten_record(java_path: Path, record_name: str) -> set[str]:
    text = java_path.read_text()
    pattern = rf"public record {record_name}\((.*?)\)"
    m = re.search(pattern, text, re.DOTALL)
    if not m:
        return set()
    names = []
    for line in m.group(1).split(","):
        line = line.strip()
        parts = line.split()
        if parts:
            names.append(parts[-1])
    return set(names)


def fields_from_generated(java_path: Path) -> set[str]:
    text = java_path.read_text()
    # Generated models use constants: @SerializedName(SERIALIZED_NAME_TENANT_ID)
    # where the constant is defined as: public static final String SERIALIZED_NAME_TENANT_ID = "tenantId";
    names = re.findall(r'public static final String SERIALIZED_NAME_\w+ = "(.*?)";', text)
    return set(names)


def main() -> int:
    errors = 0
    project_root = Path(__file__).parent.parent.parent.parent

    hand = (
        project_root
        / "apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/exportjob/ExportJobMessage.java"
    )
    gen = (
        project_root
        / "apps/meeting-api/meeting-api-client/generated/export-job/src/main/java/com/meeting/api/client/exportjob/model/ExportJobMessage.java"
    )
    gen_eiv = (
        project_root
        / "apps/meeting-api/meeting-api-client/generated/export-job/src/main/java/com/meeting/api/client/exportjob/model/ExportJobMessageExpectedInputVersion.java"
    )

    if not hand.exists():
        print(f"  FAIL handwritten DTO not found: {hand}")
        return 1
    if not gen.exists():
        print(f"  FAIL generated DTO not found: {gen}")
        return 1

    hand_fields = fields_from_handwritten_record(hand, "ExportJobMessage")
    gen_fields = fields_from_generated(gen)

    missing = hand_fields - gen_fields
    extra = gen_fields - hand_fields

    if missing:
        print(f"  FAIL handwritten fields missing in generated: {sorted(missing)}")
        errors += 1
    if extra:
        print(f"  FAIL generated fields missing in handwritten: {sorted(extra)}")
        errors += 1

    # ExpectedInputVersion
    hand_eiv = fields_from_handwritten_record(hand, "ExpectedInputVersion")
    gen_eiv_fields = fields_from_generated(gen_eiv) if gen_eiv.exists() else set()

    missing_eiv = hand_eiv - gen_eiv_fields
    extra_eiv = gen_eiv_fields - hand_eiv
    if missing_eiv:
        print(f"  FAIL ExpectedInputVersion handwritten fields missing in generated: {sorted(missing_eiv)}")
        errors += 1
    if extra_eiv:
        print(f"  FAIL ExpectedInputVersion generated fields missing in handwritten: {sorted(extra_eiv)}")
        errors += 1

    if errors:
        print(f"  {errors} DTO consistency error(s) found")
        return 1

    print(f"  OK   ExportJobMessage fields match generated model ({len(hand_fields)} fields)")
    print(f"  OK   ExpectedInputVersion fields match generated model ({len(hand_eiv)} fields)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
