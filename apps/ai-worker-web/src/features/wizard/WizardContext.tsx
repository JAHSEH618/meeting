import { createContext, useContext, type ReactNode } from "react";
import type { WizardStep, WizardState } from "./useWizard";

interface WizardContextValue {
  state: WizardState;
  step: WizardStep;
  patch: (p: Partial<WizardState>) => void;
  goNext: () => void;
  goTo: (target: WizardStep) => void;
  order: WizardStep[];
}

const WizardContext = createContext<WizardContextValue | null>(null);

export function WizardProvider({
  value,
  children,
}: {
  value: WizardContextValue;
  children: ReactNode;
}) {
  return <WizardContext.Provider value={value}>{children}</WizardContext.Provider>;
}

export function useWizardContext(): WizardContextValue {
  const ctx = useContext(WizardContext);
  if (!ctx) throw new Error("useWizardContext must be used inside <WizardProvider>");
  return ctx;
}
