import type { WizardStep } from "@/features/wizard/useWizard";
import type { ProcessingTaskPhase } from "@/shared/api/types";

const GROUPS: Array<{ title: string; steps: WizardStep[] }> = [
  { title: "准备", steps: ["META", "AUDIO", "GLOSSARY", "DOCUMENTS"] },
  { title: "worker 处理", steps: ["PROCESS", "SPEAKERS"] },
  { title: "Java 收尾", steps: ["FINALIZE", "EXPORT"] },
];

const LABELS: Record<WizardStep, string> = {
  META: "1 建会议",
  AUDIO: "2 上传录音",
  GLOSSARY: "3a 术语",
  DOCUMENTS: "3b 文档",
  PROCESS: "4 处理",
  SPEAKERS: "5 认人",
  FINALIZE: "6a 纪要",
  EXPORT: "6c 导出",
};

interface Props {
  order: WizardStep[];
  current: WizardStep;
  meetingId: string | null;
  workerPhase: ProcessingTaskPhase | null;
}

function stateFor(
  step: WizardStep,
  current: WizardStep,
  order: WizardStep[],
  meetingId: string | null,
): "completed" | "current" | "pending" | "unreachable" {
  if (step === current) return "current";
  const idx = order.indexOf(step);
  const curIdx = order.indexOf(current);
  if (idx < curIdx) return "completed";
  if (step !== "META" && !meetingId) return "unreachable";
  return "pending";
}

function workerLabel(phase: ProcessingTaskPhase | null): string {
  if (!phase) return "等待中";
  if (phase === "WORKER_DAG_RUNNING") return "运行中";
  if (phase === "WORKER_DAG_DONE") return "已完成";
  if (phase === "JAVA_LLM_RUNNING") return "已完成";
  if (phase === "TERMINAL") return "已完成";
  return "等待中";
}

function javaLabel(phase: ProcessingTaskPhase | null): string {
  if (!phase) return "等待中";
  if (phase === "JAVA_LLM_RUNNING") return "运行中";
  if (phase === "TERMINAL") return "完成";
  return "等待中";
}

export function WizardRail({ order, current, meetingId, workerPhase }: Props) {
  return (
    <aside className="workstation__rail" aria-label="会议流程">
      {GROUPS.map((g) => (
        <div key={g.title} className="wizard__group">
          <h4>{g.title}</h4>
          {g.steps.map((s) => (
            <div
              key={s}
              className="wizard__step"
              data-state={stateFor(s, current, order, meetingId)}
              data-testid={`wizard-step-${s}`}
            >
              <span>{LABELS[s]}</span>
              {stateFor(s, current, order, meetingId) === "completed" ? <span>✓</span> : null}
            </div>
          ))}
        </div>
      ))}
      <div className="wizard__backend-summary" aria-label="backend-phase">
        <div>worker · {workerLabel(workerPhase)}</div>
        <div>Java · {javaLabel(workerPhase)}</div>
      </div>
    </aside>
  );
}
