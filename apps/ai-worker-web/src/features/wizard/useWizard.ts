import { useCallback, useState } from "react";

export interface WizardState {
  meetingId: string | null;
  startedProcessing: boolean;
  finalized: boolean;
  exportId: string | null;
  downloadUrl: string | null;
}

export type WizardStep =
  | "META"
  | "AUDIO"
  | "GLOSSARY"
  | "DOCUMENTS"
  | "PROCESS"
  | "SPEAKERS"
  | "FINALIZE"
  | "EXPORT";

const ORDER: WizardStep[] = [
  "META",
  "AUDIO",
  "GLOSSARY",
  "DOCUMENTS",
  "PROCESS",
  "SPEAKERS",
  "FINALIZE",
  "EXPORT",
];

export function useWizard(initial?: Partial<WizardState>) {
  const [state, setState] = useState<WizardState>({
    meetingId: null,
    startedProcessing: false,
    finalized: false,
    exportId: null,
    downloadUrl: null,
    ...initial,
  });
  const [step, setStep] = useState<WizardStep>(initial?.meetingId ? "AUDIO" : "META");

  const patch = useCallback((p: Partial<WizardState>) => setState((s) => ({ ...s, ...p })), []);

  const goNext = useCallback(() => {
    setStep((s) => {
      const idx = ORDER.indexOf(s);
      return idx < ORDER.length - 1 ? ORDER[idx + 1]! : s;
    });
  }, []);

  const goTo = useCallback((target: WizardStep) => setStep(target), []);

  return { state, step, patch, goNext, goTo, order: ORDER };
}
