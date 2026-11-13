// UI-FOUND-WEB: views (.svelte files) only render state. All fetching, generated-client use and shared-core
// calls live in src/lib/state/ (one module per screen), so the views can be replaced wholesale.
import { describe, expect, it } from 'vitest';

// Every view's source text, and the set of all source files (for resolving relative imports).
// Keys look like "../../src/routes/cards/+page.svelte"; they are normalised to "src/routes/...".
const viewSources = import.meta.glob('../../src/**/*.svelte', { query: '?raw', import: 'default', eager: true }) as Record<
	string,
	string
>;
const allFiles = new Set(Object.keys(import.meta.glob('../../src/**/*')).map((k) => k.replace(/^(\.\.\/)+/, '')));
const views = Object.fromEntries(Object.entries(viewSources).map(([k, v]) => [k.replace(/^(\.\.\/)+/, ''), v]));

/** posix dirname + join + normalise, enough for relative specifiers. */
function resolvePath(fromFile: string, spec: string): string {
	const parts = fromFile.split('/').slice(0, -1);
	for (const seg of spec.split('/')) {
		if (seg === '' || seg === '.') continue;
		if (seg === '..') parts.pop();
		else parts.push(seg);
	}
	return parts.join('/');
}

/** Module specifiers of static imports, re-exports and dynamic imports in script code. */
export function importSpecifiers(code: string): string[] {
	const specs: string[] = [];
	const patterns = [
		/^\s*import\s+(?:type\s+)?[^'"`;]*?\bfrom\s*['"]([^'"]+)['"]/gm,
		/^\s*import\s*['"]([^'"]+)['"]/gm,
		/^\s*export\s+[^'"`;]*?\bfrom\s*['"]([^'"]+)['"]/gm,
		/\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/g
	];
	for (const re of patterns) for (const m of code.matchAll(re)) specs.push(m[1]);
	return specs;
}

/** The file a relative specifier points at (a .svelte component, a .ts/.svelte.ts module, or a stylesheet). */
function resolveRelative(fromFile: string, spec: string): string | null {
	const base = resolvePath(fromFile, spec);
	for (const c of [base, `${base}.ts`, `${base}.js`, `${base}/index.ts`]) if (allFiles.has(c)) return c;
	return null;
}

const PACKAGE_ALLOWED = (spec: string): boolean =>
	spec === 'svelte' || spec.startsWith('svelte/') || spec.startsWith('$app/');

/** Why a view may not import `spec`, or null when it may. `file` is relative to the web root ("src/..."). */
export function violation(file: string, spec: string): string | null {
	if (!spec.startsWith('.')) return PACKAGE_ALLOWED(spec) ? null : `package "${spec}" (use a state module)`;
	const target = resolveRelative(file, spec);
	// Outside src/ (e.g. the shared card stylesheet under assets/) only stylesheets are allowed.
	if (!target) return spec.endsWith('.css') ? null : `unresolved "${spec}"`;
	if (target.endsWith('.svelte') || target.endsWith('.css')) return null;
	if (target.startsWith('src/lib/state/') || target.startsWith('src/lib/ui/')) return null;
	// Presentation-only helpers: message catalogues (i18n) and the toast list.
	if (/\/messages\.ts$/.test(target) || target === 'src/lib/i18n.ts' || target === 'src/lib/toasts.svelte.ts') return null;
	return `"${spec}" (${target}) is not under lib/state/ or lib/ui/`;
}

/** Direct network or IO primitives in a view. */
export function ioUse(source: string): string | null {
	const m =
		/\b(fetch|XMLHttpRequest|EventSource|WebSocket)\s*\(/.exec(source) ??
		/\bnew\s+(XMLHttpRequest|EventSource|WebSocket)\b/.exec(source);
	return m ? m[0] : null;
}

/** Script blocks only: markup text may contain the word "import". */
function scripts(source: string): string {
	return [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n');
}

describe('view import rule', () => {
	it('finds the views', () => {
		expect(Object.keys(views).length).toBeGreaterThan(30);
	});

	it('no .svelte file imports fetch, the generated client, api modules or the shared core', () => {
		const problems: string[] = [];
		for (const [file, source] of Object.entries(views)) {
			for (const spec of importSpecifiers(scripts(source))) {
				const why = violation(file, spec);
				if (why) problems.push(`${file}: ${why}`);
			}
			const io = ioUse(source);
			if (io) problems.push(`${file}: uses ${io}`);
		}
		expect(problems).toEqual([]);
	});

	it('the checker itself rejects the forbidden imports', () => {
		const f = 'src/routes/cards/+page.svelte';
		expect(violation(f, '../../lib/api/generated')).not.toBeNull();
		expect(violation(f, '../../lib/api/client')).not.toBeNull();
		expect(violation(f, '../../lib/core/index')).not.toBeNull();
		expect(violation(f, '../../lib/cards/api')).not.toBeNull();
		expect(violation(f, '../../lib/cards/model')).not.toBeNull();
		expect(violation(f, '../../lib/events')).not.toBeNull();
		expect(violation(f, '@kotobaya/core')).not.toBeNull();
		expect(violation(f, 'qrcode-generator')).not.toBeNull();
		expect(violation(f, '../../lib/state/cards.svelte')).toBeNull();
		expect(violation(f, '../../lib/cards/messages')).toBeNull();
		expect(violation(f, '../../lib/ui/Button.svelte')).toBeNull();
		expect(importSpecifiers("import type { X } from '../a';\nimport '../b.css';\nconst m = import('../c');")).toEqual([
			'../a',
			'../b.css',
			'../c'
		]);
		expect(ioUse('const r = await fetch(url);')).not.toBeNull();
		expect(ioUse('new EventSource(u)')).not.toBeNull();
		expect(ioUse('prefetch: true')).toBeNull();
	});
});
