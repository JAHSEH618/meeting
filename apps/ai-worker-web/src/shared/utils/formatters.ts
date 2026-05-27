export const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium",
  timeStyle: "short",
});

export const dateShortFormatter = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "short",
});

export const numberFormatter = new Intl.NumberFormat("zh-CN");

export const percentFormatter = new Intl.NumberFormat("zh-CN", {
  style: "percent",
  maximumFractionDigits: 1,
});

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return dateFormatter.format(date);
}

export function formatNumber(n: number): string {
  return numberFormatter.format(n);
}

export function formatPercent(ratio: number): string {
  return percentFormatter.format(ratio);
}
