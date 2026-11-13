// Theme preference: follow the system (default) or force light/dark. The choice is per browser (localStorage) and
// is applied as data-theme on <html>; tokens.css and card.css switch on it. An inline script in app.html applies
// the stored value (key below; keep them in sync) before first paint, so there is no flash (docs/DESIGN.md › Theme).
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

/** Reads the stored preference and applies it (call once on mount). */
export function initTheme(): void {
	theme.preference = stored();
	apply(theme.preference);
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
}
