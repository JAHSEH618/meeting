import type { ReactNode } from 'react';

interface HeroSectionProps {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  label?: string;
}

export function HeroSection({ title, subtitle, actions, label }: HeroSectionProps) {
  return (
    <header className="hero-section">
      {label && <div className="hero-label">{label}</div>}
      <h1 className="page-title">{title}</h1>
      {subtitle && <p className="hero-subtitle">{subtitle}</p>}
      {actions && <div className="hero-actions">{actions}</div>}
    </header>
  );
}
