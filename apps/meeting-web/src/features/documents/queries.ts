import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createDocument,
  deleteDocument,
  listDocuments,
  reindexDocument,
} from "@shared/api/client";
import type { Document } from "@shared/api/types";

export function useDocumentsQuery() {
  return useQuery<{ items: Document[] }>({
    queryKey: ["documents"],
    queryFn: () => listDocuments(),
  });
}

export function useCreateDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createDocument,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["documents"] }),
  });
}

export function useDeleteDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (documentId: string) => deleteDocument(documentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["documents"] }),
  });
}

export function useReindexDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (documentId: string) => reindexDocument(documentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["documents"] }),
  });
}
