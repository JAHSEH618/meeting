import type { ProcessingTaskPhase } from "@shared/api/types";

const SEGMENTS = [
  { key: "WORKER_DAG", label: "worker DAG" },
  { key: "JAVA_LLM", label: "Java LLM" },
  { key: "TERMINAL", label: "完成" },
] as const;

type SegKey = (typeof SEGMENTS)[number]["key"];

function stateFor(seg: SegKey, phase: ProcessingTaskPhase | null | undefined): "pending" | "active" | "done" {
  if (!phase) return "pending";
  const phaseOrder: Record<ProcessingTaskPhase, number> = {
    WORKER_DAG_RUNNING: 0,
    WORKER_DAG_DONE: 0,
    JAVA_LLM_RUNNING: 1,
    TERMINAL: 2,
  };
  const segIdx = SEGMENTS.findIndex((s) => s.key === seg);
  const phaseIdx = phaseOrder[phase];
  if (phaseIdx === undefined) return "pending";
  if (phaseIdx === segIdx) {
    if (phase === "TERMINAL") return "done";
    if (phase === "WORKER_DAG_DONE" && seg === "WORKER_DAG") return "done";
    return "active";
  }
  if (phaseIdx > segIdx) return "done";
  return "pending";
}

export function PhaseStrip({ phase }: { phase: ProcessingTaskPhase | null | undefined }) {
  return (
    <div>
      <div className="phase-strip" role="progressbar" aria-label="任务阶段进度">
        {SEGMENTS.map((s) => (
          <div key={s.key} className="phase-strip__seg" data-state={stateFor(s.key, phase)} />
        ))}
      </div>
      <div className="toolbar" style={{ marginTop: 4, gap: 16 }}>
        {SEGMENTS.map((s) => (
          <span key={s.key} className="phase-strip__label">{s.label}</span>
        ))}
      </div>
    </div>
  );
}
