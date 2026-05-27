import type { ReactNode } from "react";
import { MemoryRouter, type MemoryRouterProps } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

/**
 * MemoryRouter wrapper with the same React Router v7 future flags as the
 * production entry (`src/main.tsx`) plus a fresh QueryClientProvider so
 * page components using useQuery / useMutation work without each test
 * having to set up its own client.
 *
 * Why this exists: bare `<MemoryRouter>` in tests prints
 * "React Router will begin wrapping state updates in `React.startTransition`"
 * and "Relative route resolution within Splat routes is changing" warnings
 * to the console on every render — these flood the test output and make
 * real warnings (act, key, etc.) easy to miss.
 */
export function TestRouter({ children, ...rest }: MemoryRouterProps): JSX.Element {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
        {...rest}
      >
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  );
}

/**
 * Convenience for the common single-string-route case:
 *   renderWithRoute(<MyPage />, "/foo/bar")
 */
export function withRoute(node: ReactNode, path: string): JSX.Element {
  return <TestRouter initialEntries={[path]}>{node}</TestRouter>;
}
