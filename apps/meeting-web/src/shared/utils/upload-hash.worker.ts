// Web Worker entry for upload hashing — see upload-hasher.ts.
// Runs the single-pass file+part SHA-256 off the main thread and streams
// byte progress back so the UI can show a real "preparing" progress bar.

import {
  hashFileForUploadInline,
  type UploadHashWorkerMessage,
  type UploadHashWorkerRequest,
} from "./upload-hasher";

const post = (message: UploadHashWorkerMessage) => {
  (self as unknown as Worker).postMessage(message);
};

self.onmessage = async (event: MessageEvent<UploadHashWorkerRequest>) => {
  const { file, partSizeBytes } = event.data;
  try {
    const result = await hashFileForUploadInline(file, partSizeBytes, (bytesHashed, totalBytes) => {
      post({ type: "progress", bytesHashed, totalBytes });
    });
    post({ type: "done", result });
  } catch (cause) {
    post({ type: "error", message: cause instanceof Error ? cause.message : String(cause) });
  }
};
