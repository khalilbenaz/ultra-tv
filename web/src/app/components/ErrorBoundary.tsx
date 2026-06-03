// Top-level React error boundary with a TV-friendly fallback: dark background,
// large text readable from across a room, and a single focusable "Recharger"
// button that reloads the app. Caught errors are also routed to the on-screen
// diag buffer (RemoteDiagPanel) so they're visible without adb/devtools.

import { Component, type ErrorInfo, type ReactNode } from "react";
import { pushDiagError } from "@app/diag/errorSink";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Route to the diag buffer so it shows up on the TV overlay, plus console.
    pushDiagError(error, info.componentStack ? "render" : undefined);
    console.error("App crashed:", error, info.componentStack);
  }

  private readonly reload = (): void => {
    location.reload();
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div
        role="alert"
        style={{
          position: "fixed",
          inset: 0,
          zIndex: 200000,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: "2rem",
          padding: "4vw",
          textAlign: "center",
          background: "#0a0d18",
          color: "#e6e9f2",
          fontFamily: "system-ui, sans-serif",
        }}
      >
        <h1 style={{ fontSize: "3rem", margin: 0, color: "#ff6b6b" }}>
          Une erreur est survenue
        </h1>
        <p style={{ fontSize: "1.75rem", maxWidth: "60ch", margin: 0, color: "#b6bdd6" }}>
          L&apos;application a rencontré un problème inattendu. Vous pouvez la
          recharger pour continuer.
        </p>
        <pre
          style={{
            fontSize: "1.1rem",
            maxWidth: "80ch",
            maxHeight: "20vh",
            overflow: "auto",
            color: "#8a93ac",
            background: "rgba(255,255,255,.04)",
            padding: "1rem 1.25rem",
            borderRadius: 10,
            whiteSpace: "pre-wrap",
          }}
        >
          {error.message}
        </pre>
        <button
          autoFocus
          onClick={this.reload}
          style={{
            fontSize: "2rem",
            padding: "1rem 3rem",
            borderRadius: 14,
            border: "3px solid transparent",
            background: "#6ea8ff",
            color: "#0a0d18",
            fontWeight: 700,
            cursor: "pointer",
            outline: "none",
          }}
          onFocus={(e) => (e.currentTarget.style.border = "3px solid #fff")}
          onBlur={(e) => (e.currentTarget.style.border = "3px solid transparent")}
        >
          Recharger
        </button>
      </div>
    );
  }
}
