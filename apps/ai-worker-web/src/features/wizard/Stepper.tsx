import type { WizardStep } from "@/features/wizard/useWizard";

interface Props {
  step: WizardStep;
  order: WizardStep[];
}

const LABELS: Record<WizardStep, string> = {
  META: "1. 建会议",
  AUDIO: "2. 上传录音",
  GLOSSARY: "3a. 术语",
  DOCUMENTS: "3b. 关联文档",
  PROCESS: "4. 开始处理",
  SPEAKERS: "5. 认人",
  FINALIZE: "6a. 生成纪要",
  EXPORT: "6c. 下载",
};

export function Stepper({ step, order }: Props) {
  const idx = order.indexOf(step);
  return (
    <div className="stepper" role="navigation" aria-label="wizard steps">
      {order.map((s, i) => {
        const cls = i === idx ? "stepper__item--active" : i < idx ? "stepper__item--done" : "";
        return (
          <div key={s} className={`stepper__item ${cls}`} data-testid={`step-${s}`}>
            {LABELS[s]}
          </div>
        );
      })}
    </div>
  );
}
