// What the SPA offers a principal and a connection state (SHELL.md 9.4): scope rule and mode rule. Pure functions;
// the scope rule applies on the plain web too (a personal token without `import` has no Import screen).
import { opClass } from '../api/shell/ops';
import type { NavItem } from './kinds';

export type ScopeName = 'read' | 'write' | 'review' | 'import' | 'admin';

/** The scope a nav destination needs; destinations not listed need none beyond sign-in. */
export const NAV_SCOPE: Readonly<Record<string, ScopeName>> = { '/import': 'import' };

/** The scope a settings section needs (ids of `routes/settings/+page.svelte`). */
export const SECTION_SCOPE: Readonly<Record<string, ScopeName>> = { devices: 'admin', tokens: 'admin' };

/** Nav destinations the principal may use. */
export function visibleNav(items: readonly NavItem[], scopes: readonly string[]): NavItem[] {
	return items.filter((i) => {
		const need = NAV_SCOPE[i.href];
		return need === undefined || scopes.includes(need);
	});
}

/** Settings sections the principal may use. */
export function visibleSections<T extends { id: string }>(sections: readonly T[], scopes: readonly string[]): T[] {
	return sections.filter((s) => {
		const need = SECTION_SCOPE[s.id];
		return need === undefined || scopes.includes(need);
	});
}

/**
 * The operations each route calls (operationIds of openapi.yaml), by path prefix. A route is gated behind
 * "needs connection" only when it has no operation the phone can answer: every operation is `proxy`.
 */
export const ROUTE_OPS: Readonly<Record<string, readonly string[]>> = {
	'/stats': ['getStatsSummary', 'getStatsHeatmap', 'getStatsRetention', 'getStatsForecast', 'getStatsKnownWords', 'getStatsCalibration'],
	'/media': ['listMedia', 'getMedia', 'getMediaCoverage', 'listMediaSentences', 'updateMedia', 'deleteMedia'],
	'/import/mapping': ['listJpdbVocab', 'listMediaSentences'],
	'/note-types': ['listNoteTypes', 'getNoteType', 'createNoteType', 'updateNoteType', 'deleteNoteType'],
	'/struggle': ['listHotWords', 'listLeeches', 'listConfusables', 'listWordConfusables'],
	'/decks': ['listDecks', 'getDeck', 'listDeckItems', 'listDeckLists', 'getDeckCoverage'],
	'/dictionary': ['searchDictionary', 'getDictEntry', 'listEntryExamples', 'getKanji']
};

/** True when no operation of the route can be answered without the server. */
export function isProxyOnly(operationIds: readonly string[]): boolean {
	return operationIds.length > 0 && operationIds.every((id) => opClass(id) === 'proxy');
}

/** The gated route prefixes, derived from ROUTE_OPS and ops.json. */
export function gatedRoutes(): string[] {
	return Object.entries(ROUTE_OPS)
		.filter(([, ops]) => isProxyOnly(ops))
		.map(([route]) => route);
}

/** Whether `path` is a gated route (the longest matching prefix decides). */
export function isGatedPath(path: string): boolean {
	const match = Object.keys(ROUTE_OPS)
		.filter((r) => path === r || path.startsWith(r + '/'))
		.sort((a, b) => b.length - a.length)[0];
	return match !== undefined && isProxyOnly(ROUTE_OPS[match] ?? []);
}

/** Show `NeedsConnection` instead of the route: offline and a route that is proxy-only. */
export function needsConnection(path: string, online: boolean): boolean {
	return !online && isGatedPath(path);
}
