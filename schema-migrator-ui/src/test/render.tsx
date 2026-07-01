import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderOptions } from "@testing-library/react";
import { HelmetProvider } from "react-helmet-async";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement, ReactNode } from "react";
import { SelectedTargetProvider } from "../hooks/useSelectedTarget";

interface RenderAppOptions extends RenderOptions {
  route?: string;
}

export const renderApp = (ui: ReactElement, { route = "/", ...options }: RenderAppOptions = {}) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: Infinity,
        retry: false,
        staleTime: 0
      }
    }
  });

  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <HelmetProvider>
        <SelectedTargetProvider>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </SelectedTargetProvider>
      </HelmetProvider>
    </QueryClientProvider>
  );

  return render(ui, { wrapper: Wrapper, ...options });
};
