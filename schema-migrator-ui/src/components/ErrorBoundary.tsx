import { Component, type ErrorInfo, type ReactNode } from "react";

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  errorId: string | undefined;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { errorId: undefined };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { errorId: crypto.randomUUID() };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("Schema Migrator UI render failure", {
      errorId: this.state.errorId,
      error,
      componentStack: info.componentStack
    });
  }

  render() {
    if (this.state.errorId) {
      return (
        <main className="fatal-error" id="main-content">
          <section className="fatal-error__panel" role="alert">
            <span className="eyebrow">Fatal error</span>
            <h1>Something went wrong</h1>
            <p>Reload the interface. Error reference {this.state.errorId} was logged to the console.</p>
            <button className="button button--primary" type="button" onClick={() => window.location.reload()}>
              Reload
            </button>
          </section>
        </main>
      );
    }

    return this.props.children;
  }
}
