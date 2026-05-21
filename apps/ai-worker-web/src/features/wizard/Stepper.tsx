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

/**
 * Visual progress indicator. The items are not interactive — clicking does
 * nothing — so we expose them as an ordered list (``<ol>``) rather than
 * ``role="navigation"`` which screen readers announce as a link target.
 * ``aria-current="step"`` marks the active row per WAI-ARIA wizard pattern.
 */
export function Stepper({ step, order }: Props) {
  const idx = order.indexOf(step);
  return (
    <ol className="stepper" aria-label="向导进度">
      {order.map((s, i) => {
        const cls = i === idx ? "stepper__item--active" : i < idx ? "stepper__item--done" : "";
        const status = i === idx ? "进行中" : i < idx ? "已完成" : "待处理";
        return (
          <li
            key={s}
            className={`stepper__item ${cls}`}
            data-testid={`step-${s}`}
            aria-current={i === idx ? "step" : undefined}
          >
            <span className="visually-hidden">{status}: </span>
            {LABELS[s]}
          </li>
        );
      })}
    </ol>
  );
}
