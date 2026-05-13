#!/usr/bin/env python3
"""Validate HTTP API fixtures against OpenAPI response and request schemas."""
import json
import jsonschema
import sys
import yaml
from pathlib import Path


# Paths relative to this script (which lives in packages/meeting-contracts/scripts/)
_SCRIPT_DIR = Path(__file__).parent
FIXTURES_DIR = _SCRIPT_DIR.parent / "fixtures"
OPENAPI_DIR = _SCRIPT_DIR.parent / "openapi"

# Fixture registry: exact mapping from fixture filename to OpenAPI spec, path,
# method and status.  New HTTP API fixtures MUST be registered here.
FIXTURE_API_MAP = {
    "valid/public-api-login-200.json": {
        "spec": "public-api.yaml", "path": "/auth/login", "method": "post", "status": 200,
    },
    "valid/public-api-create-legal-hold-201.json": {
        "spec": "public-api.yaml", "path": "/legal-holds", "method": "post", "status": 201,
    },
    "valid/public-api-delete-meeting-200.json": {
        "spec": "public-api.yaml", "path": "/meetings/{meetingId}", "method": "delete", "status": 200,
    },
    "valid/callback-step-update-200.json": {
        "spec": "internal-callback-api.yaml", "path": "/processing-tasks/{taskId}/steps/{stepName}", "method": "patch", "status": 200,
    },
    "valid/callback-complete-worker-phase-200.json": {
        "spec": "internal-callback-api.yaml", "path": "/processing-tasks/{taskId}/complete", "method": "post", "status": 200,
    },
    "valid/ai-worker-rerank-200.json": {
        "spec": "ai-worker-internal-api.yaml", "path": "/rerank", "method": "post", "status": 200,
    },
    "invalid/public-api-login-missing-username.json": {
        "spec": "public-api.yaml", "path": "/auth/login", "method": "post", "status": 400,
        "expect_error": True,
    },
    "invalid/public-api-409-conflict.json": {
        "spec": "public-api.yaml", "path": "/meetings", "method": "post", "status": 409,
        "expect_error": True,
    },
    "invalid/public-api-500-error.json": {
        "spec": "public-api.yaml", "path": "/meetings", "method": "get", "status": 500,
        "expect_error": True,
    },
    "invalid/callback-missing-hmac.json": {
        "spec": "internal-callback-api.yaml", "path": "/processing-tasks/{taskId}/steps/{stepName}", "method": "patch", "status": 401,
        "expect_error": True,
    },
    "invalid/ai-worker-rerank-empty-query.json": {
        "spec": "ai-worker-internal-api.yaml", "path": "/rerank", "method": "post", "status": 400,
        "expect_error": True,
    },
}

API_RESPONSE_SCHEMA = {
    "type": "object",
    "required": ["success", "data", "error", "requestId", "traceId"],
    "properties": {
        "success": {"type": "boolean"},
        "data": {},
        "error": {
            "anyOf": [
                {
                    "type": "object",
                    "required": ["code", "message", "retryable"],
                    "properties": {
                        "code": {"type": "string"},
                        "message": {"type": "string"},
                        "retryable": {"type": "boolean"},
                    },
                },
                {"type": "null"},
            ],
        },
        "requestId": {"type": "string"},
        "traceId": {"type": "string"},
    },
}


def resolve_ref(spec, ref, cache):
    if ref in cache:
        return cache[ref]
    if ref.startswith("#/components/schemas/"):
        name = ref.split("/")[-1]
        obj = spec.get("components", {}).get("schemas", {}).get(name, {})
    elif ref.startswith("#/components/responses/"):
        name = ref.split("/")[-1]
        obj = spec.get("components", {}).get("responses", {}).get(name, {})
    else:
        return None
    cache[ref] = resolve_schema(spec, obj, cache)
    return cache[ref]


def resolve_schema(spec, schema, cache):
    if isinstance(schema, dict):
        if "$ref" in schema and len(schema) == 1:
            return resolve_ref(spec, schema["$ref"], cache)
        return {k: resolve_schema(spec, v, cache) for k, v in schema.items()}
    elif isinstance(schema, list):
        return [resolve_schema(spec, item, cache) for item in schema]
    return schema


def get_response_schema(spec, path, method, status):
    paths = spec.get("paths", {})
    path_item = paths.get(path)
    if not path_item:
        return None
    op = path_item.get(method.lower())
    if not op:
        return None
    resp = op.get("responses", {}).get(str(status), {})
    if isinstance(resp, dict) and "$ref" in resp and len(resp) == 1:
        cache = {}
        resp = resolve_ref(spec, resp["$ref"], cache)
        if resp is None:
            return None
    content = resp.get("content", {})
    ct = content.get("application/json", {})
    schema = ct.get("schema", {})
    cache = {}
    return resolve_schema(spec, schema, cache)


def get_request_body_schema(spec, path, method):
    """Extract application/json request body schema from an OpenAPI operation."""
    paths = spec.get("paths", {})
    path_item = paths.get(path)
    if not path_item:
        return None
    op = path_item.get(method.lower())
    if not op:
        return None
    request_body = op.get("requestBody", {})
    if isinstance(request_body, dict) and "$ref" in request_body and len(request_body) == 1:
        cache = {}
        request_body = resolve_ref(spec, request_body["$ref"], cache)
        if request_body is None:
            return None
    content = request_body.get("content", {})
    ct = content.get("application/json", {})
    schema = ct.get("schema", {})
    cache = {}
    return resolve_schema(spec, schema, cache)


def main() -> int:
    errors = 0

    openapi_specs = {}
    for fname in ["public-api.yaml", "internal-callback-api.yaml", "ai-worker-internal-api.yaml"]:
        fpath = OPENAPI_DIR / fname
        if fpath.exists():
            with open(fpath) as f:
                openapi_specs[fname] = yaml.safe_load(f)

    # Auto-discovery guard
    for subdir in ["valid", "invalid"]:
        d = FIXTURES_DIR / subdir
        if not d.exists():
            continue
        for f in d.iterdir():
            if f.suffix != ".json":
                continue
            with open(f) as peek:
                data = json.load(peek)
            if "response" not in data:
                continue
            key = f"{subdir}/{f.name}"
            if key not in FIXTURE_API_MAP:
                print(f'  FAIL {key}: HTTP API fixture not registered in check-openapi-fixtures.py. Add its spec/path/method/status mapping.')
                errors += 1

    envelope_validator = jsonschema.Draft202012Validator(API_RESPONSE_SCHEMA)

    for fp, meta in FIXTURE_API_MAP.items():
        fixture_path = FIXTURES_DIR / fp
        if not fixture_path.exists():
            print(f"  FAIL fixture registered but not found: {fp}")
            errors += 1
            continue
        with open(fixture_path) as f:
            fixture = json.load(f)

        response = fixture.get("response", fixture)
        status = fixture.get("status")

        try:
            envelope_validator.validate(response)
        except jsonschema.ValidationError as e:
            print(f"  FAIL {fp}: envelope validation failed: {e.message}")
            errors += 1
            continue

        spec_name = meta["spec"]
        spec = openapi_specs.get(spec_name)
        if not spec:
            print(f"  FAIL {fp}: spec {spec_name} not loaded")
            errors += 1
            continue

        real_schema = get_response_schema(spec, meta["path"], meta["method"], meta["status"])
        if real_schema:
            try:
                jsonschema.Draft202012Validator(real_schema).validate(response)
            except jsonschema.ValidationError as e:
                print(f'  FAIL {fp}: does not match OpenAPI response schema ({spec_name} {meta["method"].upper()} {meta["path"]} {meta["status"]}): {e.message}')
                errors += 1
                continue
        else:
            print(f'  FAIL {fp}: no response schema found in OpenAPI for {meta["method"].upper()} {meta["path"]} {meta["status"]}')
            errors += 1

        if meta.get("expect_error"):
            data = response.get("data")
            error_obj = response.get("error")
            if data is not None:
                print(f"  FAIL {fp}: error response data must be null, got {type(data).__name__}")
                errors += 1
                continue
            if not isinstance(error_obj, dict) or "code" not in error_obj or "message" not in error_obj:
                print(f"  FAIL {fp}: error response must have error with code and message")
                errors += 1
                continue
        else:
            data = response.get("data")
            if data is None:
                print(f"  WARN {fp}: success response data is null")

        # ── Request body validation ────────────────────────────────────
        request_body = fixture.get("request")
        if request_body is not None:
            req_schema = get_request_body_schema(spec, meta["path"], meta["method"])
            if req_schema is None:
                print(f"  WARN {fp}: fixture has request field but no requestBody schema in OpenAPI ({spec_name} {meta['method'].upper()} {meta['path']})")
            else:
                req_validator = jsonschema.Draft202012Validator(req_schema)
                is_req_valid = req_validator.is_valid(request_body)
                if meta.get("expect_error"):
                    if is_req_valid:
                        print(f"  FAIL {fp}: invalid fixture request was accepted by OpenAPI requestBody schema (should be rejected)")
                        errors += 1
                        continue
                else:
                    if not is_req_valid:
                        errs = list(req_validator.iter_errors(request_body))
                        print(f"  FAIL {fp}: valid fixture request does not match OpenAPI requestBody schema")
                        for e in errs[:3]:
                            print(f"       {e.json_path}: {e.message}")
                        errors += 1
                        continue

        print(f"  OK   {fp}: status={status}, envelope+schema valid")

    if errors:
        print(f"  {errors} OpenAPI fixture validation error(s) found")
        return 1
    print("  All OpenAPI fixtures validated successfully")
    return 0


if __name__ == "__main__":
    sys.exit(main())
