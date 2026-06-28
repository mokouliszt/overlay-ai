import { useEffect, useRef, useState } from "react";
import { ArrowUp, Image as ImageIcon, Minus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { collapse, closeOverlay, getModels, sendMessage, requestKeyboard, dismissKeyboard, setScreenShare, installPanelDrag, type Msg } from "@/lib/bridge";
import { cn } from "@/lib/utils";

const EFFORTS = [
  { wire: "minimal", label: "最小" },
  { wire: "low", label: "低" },
  { wire: "medium", label: "中" },
  { wire: "high", label: "高" },
];

export default function App() {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [models, setModels] = useState<string[]>([]);
  const [model, setModel] = useState("");
  const [effort, setEffort] = useState("medium");
  const [attachScreen, setAttachScreen] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const m = getModels();
    setModels(m);
    if (m.length) setModel(m[0]);
  }, []);

  // 非操作領域に触れたら即ドラッグ移動できるよう、ドラッグ可否をネイティブへ通知
  useEffect(() => installPanelDrag(), []);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages]);

  const send = () => {
    const text = input.trim();
    if (!text || streaming) return;
    const convo: Msg[] = [...messages, { role: "user", content: text }];
    setMessages([...convo, { role: "assistant", content: "" }]);
    setInput("");
    setStreaming(true);

    const appendLast = (fn: (c: string) => string) =>
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        next[next.length - 1] = { ...last, content: fn(last.content) };
        return next;
      });

    sendMessage(convo, model, effort, attachScreen, {
      onDelta: (t) => appendLast((c) => c + t),
      onDone: () => setStreaming(false),
      onError: (m) => { appendLast(() => `⚠ ${m}`); setStreaming(false); },
    });
  };

  return (
    <div className="flex h-full flex-col bg-card/95 backdrop-blur-sm rounded-2xl border p-2.5 gap-2">
      {/* ヘッダ */}
      <div className="flex items-center px-1">
        <span className="text-xs font-medium text-muted-foreground flex-1">Overlay AI</span>
        <Button variant="ghost" size="icon" className="h-6 w-6" onClick={collapse} title="最小化">
          <Minus className="h-4 w-4" />
        </Button>
        <Button variant="ghost" size="icon" className="h-6 w-6" onClick={closeOverlay} title="終了">
          <X className="h-4 w-4" />
        </Button>
      </div>

      {/* メッセージ（揮発・履歴なし） */}
      <ScrollArea className="flex-1 -mx-1 px-1">
        <div className="flex flex-col gap-2">
          {messages.map((m, i) => (
            <div key={i} className={cn("flex", m.role === "user" ? "justify-end" : "justify-start")}>
              <div
                className={cn(
                  "max-w-[85%] rounded-xl px-3 py-2 text-[13px] leading-relaxed whitespace-pre-wrap break-words",
                  m.role === "user" ? "bg-secondary" : "bg-muted"
                )}
              >
                {m.content || "…"}
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
      </ScrollArea>

      {/* モデル / 推論レベル / 画面添付 */}
      <div className="flex items-center gap-1.5">
        <Select value={model} onValueChange={setModel}>
          <SelectTrigger className="flex-1"><SelectValue placeholder="model" /></SelectTrigger>
          <SelectContent>
            {models.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={effort} onValueChange={setEffort}>
          <SelectTrigger className="w-[88px]"><SelectValue /></SelectTrigger>
          <SelectContent>
            {EFFORTS.map((e) => <SelectItem key={e.wire} value={e.wire}>推論:{e.label}</SelectItem>)}
          </SelectContent>
        </Select>
        <Button
          variant={attachScreen ? "secondary" : "outline"}
          size="sm"
          className="h-8 gap-1 px-2 shrink-0"
          onClick={() => { const v = !attachScreen; setAttachScreen(v); setScreenShare(v); }}
          title="画面送信"
        >
          <ImageIcon className="h-4 w-4" />
          <span className="text-[11px]">画面送信</span>
        </Button>
      </div>

      {/* 入力 + 送信 */}
      <div className="flex items-end gap-1.5">
        <Textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onFocus={() => requestKeyboard()}
          onBlur={() => dismissKeyboard()}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder="メッセージ"
          rows={1}
          className="flex-1 max-h-28"
        />
        <Button size="icon" className="h-9 w-9 shrink-0" disabled={streaming || !input.trim()} onClick={send}>
          <ArrowUp className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
