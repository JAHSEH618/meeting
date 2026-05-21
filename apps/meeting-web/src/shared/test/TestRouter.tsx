import type { ReactNode } from "react";
import { MemoryRouter, type MemoryRouterProps } from "react-router-dom";

/**
 * MemoryRouter wrapper with the same React Router v7 future flags as the
 * production entry (`src/main.tsx`).
 *
 * Why this exists: bare `<MemoryRouter>` in tests prints
 * "React Router will begin wrapping state updates in `React.startTransition`"
 * and "Relative route resolution within Splat routes is changing" warnings
 * to the console on every render — these flood the test output and make
 * real warnings (act, key, etc.) easy to miss.
 *
 * Use this everywhere tests render a router. The shape matches
 * `<MemoryRouter>` exactly (initialEntries, initialIndex, basename, children).
 */
export function TestRouter({ children, ...rest }: MemoryRouterProps): JSX.Element {
  return (
    <MemoryRouter
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      {...rest}
    >
      {children}
    </MemoryRouter>
  );
}

/**
 * Convenience for the common single-string-route case:
 *   renderWithRoute(<MyPage />, "/foo/bar")
 */
export function withRoute(node: ReactNode, path: string): JSX.Element {
  return <TestRouter initialEntries={[path]}>{node}</TestRouter>;
}
