// Theme preference: follow the system (default) or force light/dark. The choice is per browser (localStorage) and
// is applied as data-theme on <html>; tokens.css and card.css switch on it. An inline script in app.html applies
// the stored value (key below; keep them in sync) before first paint, so there is no flash (docs/DESIGN.md › Theme).
import { getShellTransport } from '../api/shell';

export type ThemePreference = 'system' | 'light' | 'dark';

const KEY = 'kb-theme';

export const theme = $state<{ preference: ThemePreference }>({ preference: 'system' });

function stored(): ThemePreference {
	try {
		const v = globalThis.localStorage?.getItem(KEY);
		return v === 'light' || v === 'dark' ? v : 'system';
	} catch {
		return 'system';
	}
}

function apply(p: ThemePreference): void {
	const root = globalThis.document?.documentElement;
	if (!root) return;
	if (p === 'system') delete root.dataset.theme;
	else root.dataset.theme = p;
}

/** Is the page dark right now (forced, or following the system)? */
function isDark(): boolean {
	if (theme.preference !== 'system') return theme.preference === 'dark';
	return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

/** `#rrggbb` of a computed `rgb()/rgba()` colour; null for transparent or an unknown format. */
function hexOf(css: string): string | null {
	const m = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+%?))?\s*\)$/.exec(css);
	if (!m || m[4] === '0') return null;
	return '#' + [m[1], m[2], m[3]].map((n) => Math.min(255, Number(n)).toString(16).padStart(2, '0')).join('');
}

/** Shell mode: tell native the system-bar colours (SHELL.md 6.3). No-op in a browser. */
export function reportTheme(): void {
	const t = getShellTransport();
	const doc = globalThis.document;
	if (!t || !doc) return;
	const dark = isDark();
	const surface =
		hexOf(getComputedStyle(doc.body).backgroundColor) ??
		hexOf(getComputedStyle(doc.documentElement).backgroundColor) ??
		(dark ? '#101418' : '#ffffff');
	try {
		t.send({ v: 1, t: 'theme', dark, surface });
	} catch {
		/* cosmetic only */
	}
}

/** Reads the stored preference and applies it (call once on mount). */
export function initTheme(): void {
	theme.preference = stored();
	apply(theme.preference);
	reportTheme();
	globalThis.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => reportTheme());
}

export function setTheme(p: ThemePreference): void {
	theme.preference = p;
	try {
		if (p === 'system') globalThis.localStorage?.removeItem(KEY);
		else globalThis.localStorage?.setItem(KEY, p);
	} catch {
		// storage blocked: the choice still applies to this page
	}
	apply(p);
	reportTheme();
}
