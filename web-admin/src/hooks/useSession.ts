import { useState } from "react";
import type { Session } from "../types";

export function useSession() {
  const [session, setSessionState] = useState<Session | null>(() => {
    const raw = localStorage.getItem("voice-hall-session");
    return raw ? (JSON.parse(raw) as Session) : null;
  });

  const setSession = (value: Session | null) => {
    setSessionState(value);
    if (value) localStorage.setItem("voice-hall-session", JSON.stringify(value));
    else localStorage.removeItem("voice-hall-session");
  };

  return [session, setSession] as const;
}
