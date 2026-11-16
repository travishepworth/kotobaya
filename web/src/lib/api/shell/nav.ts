// Native <-> SPA navigation (SHELL.md 6): `navigate`, the back order with ADR-030, haptics.
import { getShellTransport } from './index';
import { has } from '../../state/shell.svelte';

const PATH_RE = /^\/[A-Za-z0-9\-._~/?&=%+]*$/;
const MAX_PATH = 2048;

/** The path rule of SHELL.md 6.1: applied again on receipt; a path that fails it is ignored. */
export function isValidNavigatePath(path: string): boolean {
	return path.length <= MAX_PATH && PATH_RE.test(path) && !path.includes('//');
}

/** `encodeURIComponent` plus the characters it leaves bare but the path rule rejects. */
function enc(s: string): string {
	return encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

/** The intake path `/search?q=…[&sentence=…]` (what native sends; also used by in-app links). */
export function buildIntakePath(q: string, sentence?: string | null): string {
	const s = sentence?.trim();
	return `/search?q=${enc(q)}${s ? `&sentence=${enc(s)}` : ''}`;
}

export interface Intake {
	q: string | null;
	sentence: string | null;
}

/** `q` and `sentence` of a `/search` or `/dictionary` URL; null when absent or blank. */
export function intakeFromSearch(search: string): Intake {
	const p = new URLSearchParams(search);
	const q = p.get('q')?.trim() ?? '';
	const sentence = p.get('sentence')?.trim() ?? '';
	return { q: q === '' ? null : q, sentence: sentence === '' ? null : sentence };
}

/* ---------- back ---------- */

export interface BackEnv {
	/** Open dialogs/sheets. */
	overlays: { count(): number; closeTop(): boolean };
	/** The review screen's undo-by-back; null off /review. */
	review: { canUndo(): boolean; undo(): boolean } | null;
	path: string;
	/** In-app history allows going back (`history.length > 1`). */
	canGoBack(): boolean;
	historyBack(): void;
	goHome(): void;
}

function isHome(path: string): boolean {
	return path === '/' || path === '';
}

/** Step 4 applies on a top-level tab other than Home (never the login/pair screen, which would bounce back). */
function homeStepApplies(path: string): boolean {
	return !isHome(path) && !path.startsWith('/login');
}

/** Would steps 1-4 match? No side effects; the source of `back.state.can_handle`. */
export function wouldHandleBack(env: BackEnv): boolean {
	if (env.overlays.count() > 0) return true;
	if (env.review?.canUndo()) return true;
	if (env.canGoBack()) return true;
	return homeStepApplies(env.path);
}

/** Run the first matching step (SHELL.md 6.2, ADR-030). Returns `handled`. */
export function runBack(env: BackEnv): boolean {
	if (env.overlays.closeTop()) return true;
	if (env.review?.canUndo() && env.review.undo()) return true;
	if (env.canGoBack()) {
		env.historyBack();
		return true;
	}
	if (homeStepApplies(env.path)) {
		env.goHome();
		return true;
	}
	return false;
}

/* ---------- bridge ---------- */

export interface NavDeps {
	goto: (path: string) => unknown;
	env: () => BackEnv;
}

let installed = false;
let lastCanHandle: boolean | null = null;

/** Follow native `navigate` and `back`. Once per page life; inert outside the shell. */
export function installNav(deps: NavDeps): void {
	const t = getShellTransport();
	if (!t || installed) return;
	installed = true;
	t.subscribe((m) => {
		if (m.t === 'navigate') {
			if (isValidNavigatePath(m.path)) void deps.goto(m.path);
		} else if (m.t === 'back') {
			let handled = false;
			try {
				handled = runBack(deps.env());
			} finally {
				t.send({ v: 1, t: 'back.result', id: m.id, handled });
			}
		} else if (m.t === 'event' && m.event.type === 'hello') {
			lastCanHandle = null; // native has a new page life: it knows no back.state yet
			reportBackState(deps.env());
		}
	});
}

/** Send `back.state` when the answer changed (call from a reactive effect). */
export function reportBackState(env: BackEnv): void {
	const t = getShellTransport();
	if (!t) return;
	const can = wouldHandleBack(env);
	if (can === lastCanHandle) return;
	lastCanHandle = can;
	t.send({ v: 1, t: 'back.state', can_handle: can });
}

/* ---------- haptics ---------- */

/** Haptic effect; a no-op without the `haptics` capability (and outside the shell). */
export function haptic(kind: 'tick' | 'confirm' | 'reject'): void {
	if (!has('haptics')) return;
	try {
		getShellTransport()?.send({ v: 1, t: 'haptic', kind });
	} catch {
		/* feedback only */
	}
}
