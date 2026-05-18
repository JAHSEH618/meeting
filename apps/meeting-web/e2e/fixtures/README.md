# Playwright E2E fixtures

This directory holds binary inputs the E2E suite needs to exercise the
full upload → SSE → RAG → PDF flow.

## Expected files

| File | Purpose | Source |
|---|---|---|
| `sample-30s.wav` | A ≤30-second mono 16 kHz WAV used by `main-flow.spec.ts` and `stale.spec.ts` to seed the ASR + diarization pipelines | Generate locally (see below) — **do not** commit large audio binaries to the repo |

## Generating `sample-30s.wav`

The fixture is a silent or single-speaker recording — content is not
asserted, only that the pipeline emits at least one transcript
segment. Use `ffmpeg`:

```bash
ffmpeg -f lavfi -i "anullsrc=channel_layout=mono:sample_rate=16000" \
    -t 30 e2e/fixtures/sample-30s.wav
```

Or, on macOS with a built-in TTS voice:

```bash
say -v "Tingting" -o e2e/fixtures/sample-30s.aiff \
    "本次会议讨论了路线图、待办与下次同步的时间。"
ffmpeg -i e2e/fixtures/sample-30s.aiff \
    -ar 16000 -ac 1 e2e/fixtures/sample-30s.wav
```

## Skipping when absent

The specs check `existsSync(AUDIO_FIXTURE)` and call `test.skip(...)`
when the file isn't there. So CI runs without this fixture will pass
the suite while flagging skipped tests in the report; once the fixture
is staged the full pipeline is exercised end-to-end.
