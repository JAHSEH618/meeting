import { useMutation, useQuery } from "@tanstack/react-query";
import { listDocuments, listMeetings, ragQuery } from "@shared/api/client";

export function useRagScopeQuery() {
  return useQuery({
    queryKey: ["rag", "scope"],
    queryFn: async () => {
      const [meetings, documents] = await Promise.all([listMeetings(), listDocuments()]);
      return { meetings: meetings.items, documents: documents.items };
    },
  });
}

export function useRagAsk() {
  return useMutation({ mutationFn: ragQuery });
}
