import type { ReactNode } from "react";
import { WizardRail } from "./WizardRail";
import type { WizardStep } from "@/features/wizard/useWizard";
import type { ProcessingTaskPhase } from "@/shared/api/types";

interface Props {
  order: WizardStep[];
  current: WizardStep;
  meetingId: string | null;
  workerPhase: ProcessingTaskPhase | null;
  children: ReactNode;
}

export function WorkstationShell({ order, current, meetingId, workerPhase, children }: Props) {
  return (
    <div className="workstation">
      <WizardRail
        order={order}
        current={current}
        meetingId={meetingId}
        workerPhase={workerPhase}
      />
      <div className="workstation__canvas">{children}</div>
    </div>
  );
}
