// On-screen diagnostic panel for remote-control issues — no adb required.
//
// Renders only in TV mode. Shows:
//   • whether the native bridge function (__ultratv_remote) is installed
//   • last raw keycode received from MainActivity (Mecool / Fire TV / etc may
//     use non-standard codes — this is how we discover them)
//   • last JS-side keydown event (key + keyCode)
//   • last bridge action ("up"/"down"/…) and its time delta
//   • number of focusable elements on the current page
//   • the currently focused element (tag + accessible text)
//
// Toggle visibility with Shift+R on a USB keyboard, or by passing ?diag=0 to
// hide. Default: visible whenever the URL has ?diag=1 OR until 60 s after the
// first remote action is received (then it minimises automatically).

import { useEffect, useRef, useState } from "react";
import { getDiagErrors, type DiagError } from "@app/diag/errorSink";

declare global {
  interface Window {
    __ultratv_rawkey?: (code: number) => void;
  }
}

// Injected at build time by vite.config define — see also __BUILD_STAMP__ usage.
declare const __BUILD_STAMP__: string;
const BUILD_STAMP = typeof __BUILD_STAMP__ !== "undefined" ? __BUILD_STAMP__ : "dev";

type LogLine = { t: number; text: string };

function shortLabel(el: Element | null): string {
  if (!el || el === document.body) return "(none)";
  const tag = el.tagName.toLowerCase();
  const text = (el.textContent || "").trim().replace(/\s+/g, " ").slice(0, 40);
  const cls = (el.getAttribute("class") || "").split(" ")[0];
  return `${tag}${cls ? "." + cls : ""}${text ? ` "${text}"` : ""}`;
}

function countFocusables(): number {
  const sel =
    "a[href],button:not([disabled]),input:not([disabled]):not([type='hidden']),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex='-1']),[role='button']";
  return document.querySelectorAll(sel).length;
}

export function RemoteDiagPanel() {
  const [open, setOpen] = useState(true);
  const [bridgeReady, setBridgeReady] = useState(false);
  const [lastRaw, setLastRaw] = useState<{ code: number; t: number } | null>(null);
  const [lastAction, setLastAction] = useState<{ action: string; t: number } | null>(null);
  const [lastKey, setLastKey] = useState<{ key: string; keyCode: number; t: number } | null>(null);
  const [log, setLog] = useState<LogLine[]>([]);
  const [focusables, setFocusables] = useState(0);
  const [activeLabel, setActiveLabel] = useState("(none)");
  const [errors, setErrors] = useState<DiagError[]>([]);
  const initRef = useRef(false);

  useEffect(() => {
    if (initRef.current) return;
    initRef.current = true;

    // Bridge readiness — checked once + watched. The native side dispatches
    // here only once spatialNav has installed itself.
    const checkBridge = () => setBridgeReady(typeof window.__ultratv_remote === "function");
    checkBridge();
    const id = window.setInterval(checkBridge, 1000);

    // Raw keycode from MainActivity (every key, even unmapped).
    window.__ultratv_rawkey = (code: number) => {
      const t = Date.now();
      setLastRaw({ code, t });
      setLog((prev) => [{ t, text: `raw keycode=${code}` }, ...prev].slice(0, 8));
    };

    // Wrap __ultratv_remote so we can see when an action gets through.
    const wrapBridge = () => {
      const existing = window.__ultratv_remote;
      if (!existing || (existing as { __wrapped?: boolean }).__wrapped) return;
      const wrapped = ((action: string) => {
        const t = Date.now();
        setLastAction({ action, t });
        setLog((prev) => [{ t, text: `bridge → ${action}` }, ...prev].slice(0, 8));
        existing(action as never);
      }) as typeof existing & { __wrapped?: boolean };
      wrapped.__wrapped = true;
      window.__ultratv_remote = wrapped;
    };
    wrapBridge();
    const wrapId = window.setInterval(wrapBridge, 500);

    const onKey = (e: KeyboardEvent) => {
      const t = Date.now();
      setLastKey({ key: e.key, keyCode: e.keyCode, t });
      setLog((prev) => [{ t, text: `keydown key="${e.key}" code=${e.keyCode}` }, ...prev].slice(0, 8));
    };
    window.addEventListener("keydown", onKey, true);

    const tick = window.setInterval(() => {
      setFocusables(countFocusables());
      setActiveLabel(shortLabel(document.activeElement));
      setErrors(getDiagErrors().slice(0, 3));
    }, 500);

    return () => {
      window.clearInterval(id);
      window.clearInterval(wrapId);
      window.clearInterval(tick);
      window.removeEventListener("keydown", onKey, true);
    };
  }, []);

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        style={{
          position: "fixed", bottom: 8, right: 8, zIndex: 100000,
          background: "rgba(0,0,0,.7)", color: "#6ea8ff", border: "1px solid #6ea8ff",
          padding: "4px 8px", borderRadius: 6, fontSize: 12, fontFamily: "ui-monospace,monospace",
        }}
      >diag</button>
    );
  }

  const now = Date.now();
  const fmt = (t: number) => `${((now - t) / 1000).toFixed(1)}s ago`;

  return (
    <div
      style={{
        position: "fixed", top: 8, right: 8, zIndex: 100000,
        background: "rgba(0,0,0,0.85)", color: "#e6e9f2",
        border: "1px solid #6ea8ff", borderRadius: 10,
        padding: "10px 12px", maxWidth: 380, fontSize: 13, lineHeight: 1.4,
        fontFamily: "ui-monospace, SFMono-Regular, monospace",
        pointerEvents: "auto",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
        <strong style={{ color: "#6ea8ff", fontSize: 15 }}>📡 Remote diag — build {BUILD_STAMP}</strong>
        <button
          onClick={() => setOpen(false)}
          style={{ background: "transparent", color: "#8a93ac", border: 0, fontSize: 16, padding: 0, cursor: "pointer" }}
          tabIndex={-1}
        >×</button>
      </div>
      <div>Build: <b style={{ color: "#ffd866" }}>{BUILD_STAMP}</b> {(window as { __ultratv_device?: { model: string } }).__ultratv_device?.model && <span style={{ color: "#8a93ac" }}>· {(window as { __ultratv_device?: { model: string; manufacturer: string } }).__ultratv_device!.manufacturer} {(window as { __ultratv_device?: { model: string } }).__ultratv_device!.model}</span>}</div>
      <div>isTv (native): <b style={{ color: (window as { __ultratv_isTv?: boolean }).__ultratv_isTv ? "#7fd47f" : "#ff6b6b" }}>{String((window as { __ultratv_isTv?: boolean }).__ultratv_isTv ?? "(not set)")}</b></div>
      <div>Native bridge: <span style={{ color: bridgeReady ? "#7fd47f" : "#ff6b6b" }}>
        {bridgeReady ? "READY" : "MISSING"}
      </span></div>
      <div>Last raw keycode: {lastRaw ? <><b style={{ color: "#ffd866" }}>{lastRaw.code}</b> ({fmt(lastRaw.t)})</> : <span style={{ color: "#ff6b6b" }}>never received</span>}</div>
      <div>Last bridge action: {lastAction ? <><b style={{ color: "#7fd47f" }}>{lastAction.action}</b> ({fmt(lastAction.t)})</> : <span style={{ color: "#ff6b6b" }}>never received</span>}</div>
      <div>Last JS keydown: {lastKey ? <>{lastKey.key} (code {lastKey.keyCode}, {fmt(lastKey.t)})</> : "(none)"}</div>
      <div>Focusable elements: <b>{focusables}</b></div>
      <div>Focused: <span style={{ color: "#6ea8ff" }}>{activeLabel}</span></div>
      <div style={{ marginTop: 6, paddingTop: 6, borderTop: "1px solid #2a3050", fontSize: 11, color: "#8a93ac" }}>
        Recent (newest first):
        {log.length === 0 && <div>—</div>}
        {log.map((l, i) => (<div key={i}>· {l.text}</div>))}
      </div>
      {errors.length > 0 && (
        <div style={{ marginTop: 6, paddingTop: 6, borderTop: "1px solid #5a2030", fontSize: 11, color: "#ff9a9a" }}>
          Caught errors (newest first):
          {errors.map((e, i) => (<div key={i}>· {fmt(e.t)}: {e.message}</div>))}
        </div>
      )}
    </div>
  );
}
