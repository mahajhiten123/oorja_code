// Lightweight "continue where you left off" tracker. Not tied to any one
// flow — anything the customer starts but doesn't finish (an AMC quote
// request, picking a service + plant, etc.) can save a single entry here,
// and the Dashboard surfaces it as a "Continue where you left off" card so
// they don't have to start over from scratch.

const KEY = "oorja_continuity";
const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000; // 14 days

export type ContinuityEntry = {
  /** Which flow this belongs to, so a newer entry from a different flow can replace it. */
  kind: "amc" | "service";
  /** e.g. "1 Year AMC" or "Solar Plant Cleaning" */
  title: string;
  /** e.g. "for Home Solar Plant" */
  subtitle: string;
  /** Where "Continue" should take them. */
  href: string;
  savedAt: number;
};

export function saveContinuity(entry: Omit<ContinuityEntry, "savedAt">) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...entry, savedAt: Date.now() }));
  } catch {
    // ignore storage errors (e.g. private browsing quota)
  }
}

export function getContinuity(): ContinuityEntry | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const entry = JSON.parse(raw) as ContinuityEntry;
    if (!entry || !entry.savedAt || Date.now() - entry.savedAt > MAX_AGE_MS) {
      localStorage.removeItem(KEY);
      return null;
    }
    return entry;
  } catch {
    return null;
  }
}

export function clearContinuity() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
