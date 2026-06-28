// ネイティブ(OverlayWebHost)との橋渡し。
// JS → Android.*  /  Android → window.__onDelta/__onDone/__onError

export type Role = "user" | "assistant" | "system";
export interface Msg { role: Role; content: string }

interface AndroidBridge {
  getModels(): string;
  collapse(): void;
  closeOverlay?(): void;
  sendMessage(payloadJson: string): void;
  requestKeyboard?(): void;
  dismissKeyboard?(): void;
  setScreenShare?(on: boolean): void;
  setDragAllowed?(allowed: boolean): void;
}

declare global {
  interface Window {
    Android?: AndroidBridge;
    __onDelta?: (t: string) => void;
    __onDone?: () => void;
    __onError?: (m: string) => void;
  }
}

const android = (): AndroidBridge | undefined => window.Android;

export function getModels(): string[] {
  try { return JSON.parse(android()?.getModels() ?? "[]"); } catch { return []; }
}

export function collapse() { android()?.collapse(); }
export function closeOverlay() { android()?.closeOverlay?.(); }

// 入力欄フォーカス時のみ窓をフォーカス可能化してIMEを出す（普段は非フォーカス）
export function requestKeyboard() { android()?.requestKeyboard?.(); }
export function dismissKeyboard() { android()?.dismissKeyboard?.(); }

// 「画面送信」トグル：ONで画面キャプチャ許可を先に要求しておく
export function setScreenShare(on: boolean) { android()?.setScreenShare?.(on); }

// パネルのドラッグ可否をネイティブへ通知
export function setDragAllowed(allowed: boolean) { android()?.setDragAllowed?.(allowed); }

/**
 * 触れた要素を見て「ドラッグして窓を動かしてよい領域か」を判定し、ネイティブへ通知する。
 * 操作対象（ボタン/リンク/入力/セレクト等）や、実際にスクロールできる領域は除外する。
 * 実際の窓移動はネイティブ側が rawX/rawY で行う（ここは可否の通知だけ）。
 * 返り値: 後始末用の解除関数。
 */
export function installPanelDrag(): () => void {
  const INTERACTIVE =
    'button, a, input, textarea, select, label, ' +
    '[role="button"], [role="combobox"], [role="option"], [role="switch"], ' +
    '[role="slider"], [contenteditable], [data-no-drag]';

  const canScroll = (start: Element | null): boolean => {
    let n: Element | null = start;
    while (n && n !== document.documentElement) {
      const s = getComputedStyle(n);
      if ((/(auto|scroll)/.test(s.overflowY) && n.scrollHeight > n.clientHeight) ||
          (/(auto|scroll)/.test(s.overflowX) && n.scrollWidth > n.clientWidth)) return true;
      n = n.parentElement;
    }
    return false;
  };

  const onDown = (e: PointerEvent) => {
    const t = e.target as Element | null;
    const blocked = !!t?.closest(INTERACTIVE) || canScroll(t);
    setDragAllowed(!blocked);
  };
  const clear = () => setDragAllowed(false);

  document.addEventListener("pointerdown", onDown, true);
  document.addEventListener("pointerup", clear, true);
  document.addEventListener("pointercancel", clear, true);
  return () => {
    document.removeEventListener("pointerdown", onDown, true);
    document.removeEventListener("pointerup", clear, true);
    document.removeEventListener("pointercancel", clear, true);
  };
}

interface StreamHandlers { onDelta: (t: string) => void; onDone: () => void; onError: (m: string) => void }

export function sendMessage(
  messages: Msg[], model: string, effort: string, attachScreen: boolean, h: StreamHandlers,
) {
  window.__onDelta = h.onDelta;
  window.__onDone = h.onDone;
  window.__onError = h.onError;
  const payload = JSON.stringify({ messages, model, effort, attachScreen });
  if (android()) android()!.sendMessage(payload);
  else {
    // ブラウザでの開発用フォールバック（ネイティブ無し）
    setTimeout(() => { h.onDelta("(dev) Androidブリッジ未接続"); h.onDone(); }, 300);
  }
}
