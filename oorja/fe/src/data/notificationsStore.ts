// Client-side notification feed. There's no live backend connection from
// this web app (see README), so this simulates the WebSocket-pushed
// notifications a connected backend would send — OTP sent, login success,
// booking confirmed — by writing them to localStorage as they happen, so
// the bell icon in the header actually reflects real activity instead of
// always showing a static dot.

export type NotificationType = "otp" | "login" | "booking" | "general";

export type AppNotification = {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  createdAt: string; // ISO timestamp
  read: boolean;
};

const KEY = "oorja_notifications";
const MAX_STORED = 30;

function read(): AppNotification[] {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as AppNotification[]) : [];
  } catch {
    return [];
  }
}

function write(list: AppNotification[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX_STORED)));
    // Notify all listeners in the same tab immediately (the native `storage`
    // event only fires in *other* tabs, so we dispatch a custom event too).
    window.dispatchEvent(new CustomEvent("oorja:notifications"));
  } catch {
    // ignore storage errors
  }
}

export function getNotifications(): AppNotification[] {
  return read().sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
}

export function getUnreadCount(): number {
  return read().filter(n => !n.read).length;
}

export function addNotification(type: NotificationType, title: string, message: string) {
  const list = read();
  list.unshift({
    id: `n_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
    type,
    title,
    message,
    createdAt: new Date().toISOString(),
    read: false
  });
  write(list);
}

export function markAllRead() {
  write(read().map(n => ({ ...n, read: true })));
}

export function relativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}
