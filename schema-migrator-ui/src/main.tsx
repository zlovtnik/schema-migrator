import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import "@fontsource-variable/inter";
import "@fontsource-variable/jetbrains-mono";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { installDesignTokens } from "./design/tokens";
import { SelectedTargetProvider } from "./hooks/useSelectedTarget";
import { router } from "./router";
import "./styles.css";

installDesignTokens();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: 1
    }
  }
});

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <SelectedTargetProvider>
          <RouterProvider router={router} />
        </SelectedTargetProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
