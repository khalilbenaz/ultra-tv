// Tiny global error sink so caught render errors are visible on a TV with no
// adb / devtools. The ErrorBoundary writes here and RemoteDiagPanel reads it.

export interface DiagError {
  t: number;
  message: string;
  stack?: string;
}

declare global {
  interface Window {
    __ultratv_diagErrors?: DiagError[];
  }
}

export function pushDiagError(error: unknown, info?: string): void {
  const err = error instanceof Error ? error : new Error(String(error));
  const entry: DiagError = {
    t: Date.now(),
    message: info ? `${info}: ${err.message}` : err.message,
    stack: err.stack,
  };
  const buf = (window.__ultratv_diagErrors ??= []);
  buf.unshift(entry);
  // Keep the buffer small — only the most recent errors matter on-screen.
  if (buf.length > 10) buf.length = 10;
}

export function getDiagErrors(): DiagError[] {
  return window.__ultratv_diagErrors ?? [];
}
