import type { ReactNode } from 'react';

interface StatsGridProps {
  children: ReactNode;
}

export function StatsGrid({ children }: StatsGridProps) {
  return (
    <div
      className="stats-grid"
      style={{ gridTemplateColumns: `repeat(auto-fit, minmax(240px, 1fr))` }}
    >
      {children}
    </div>
  );
}

interface StatCardProps {
  value: string | number;
  label: string;
  variant?: 'default' | 'accent';
}

export function StatCard({ value, label, variant = 'default' }: StatCardProps) {
  return (
    <div className="metric card">
      <div className={`metric__value ${variant === 'accent' ? 'metric__value--accent' : ''}`}>
        {value}
      </div>
      <div className="metric__label">{label}</div>
    </div>
  );
}
