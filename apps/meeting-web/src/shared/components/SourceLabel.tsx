const MAP: Record<string, string> = {
  AI_WORKER_CALLBACK: "worker 回调",
  JAVA_TASK_SERVICE: "Java 任务服务",
};

export function SourceLabel({ source }: { source: string | null | undefined }) {
  if (!source) return <span className="pill pill--neutral">未知</span>;
  return <span className="pill pill--neutral">{MAP[source] ?? source}</span>;
}
