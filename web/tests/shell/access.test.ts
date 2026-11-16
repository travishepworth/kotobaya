// SHELL-W2: scope rule (nav, settings sections) and mode rule (route gating derived from ops.json).
import { describe, expect, it } from 'vitest';
import ops from '../../../contracts/api/shell/ops.json';
import { gatedRoutes, isGatedPath, isProxyOnly, needsConnection, ROUTE_OPS, visibleNav, visibleSections } from '../../src/lib/ui/access';
import type { NavItem } from '../../src/lib/ui/kinds';

const NAV: NavItem[] = [
	{ href: '/', label: 'Home', icon: 'home' },
	{ href: '/stats', label: 'Stats', icon: 'stats' },
	{ href: '/import', label: 'Import', icon: 'import', secondary: true },
	{ href: '/settings', label: 'Settings', icon: 'settings', secondary: true }
];
const SECTIONS = [{ id: 'study' }, { id: 'devices' }, { id: 'tokens' }, { id: 'server' }];
const CLASSES = ops as Record<string, string>;

describe('scope rule', () => {
	it('read/write/review omits Import; Tokens and Devices sections', () => {
		const scopes = ['read', 'write', 'review'];
		expect(visibleNav(NAV, scopes).map((i) => i.href)).toEqual(['/', '/stats', '/settings']);
		expect(visibleSections(SECTIONS, scopes).map((s) => s.id)).toEqual(['study', 'server']);
	});

	it('all scopes leaves everything', () => {
		const all = ['read', 'write', 'review', 'import', 'admin'];
		expect(visibleNav(NAV, all)).toEqual(NAV);
		expect(visibleSections(SECTIONS, all)).toEqual(SECTIONS);
	});

	it('import without admin shows Import but not Tokens', () => {
		expect(visibleNav(NAV, ['read', 'import']).some((i) => i.href === '/import')).toBe(true);
		expect(visibleSections(SECTIONS, ['read', 'import']).some((s) => s.id === 'tokens')).toBe(false);
	});
});

describe('route gating table', () => {
	it('every operation in the table is known to ops.json', () => {
		for (const [route, list] of Object.entries(ROUTE_OPS)) for (const id of list) expect(CLASSES[id], `${route}: ${id}`).toBeDefined();
	});

	it('a route with any local operation is never gated', () => {
		for (const [route, list] of Object.entries(ROUTE_OPS)) {
			const anyLocal = list.some((id) => CLASSES[id] === 'local' || CLASSES[id] === 'local_or_proxy');
			if (anyLocal) expect(gatedRoutes(), route).not.toContain(route);
		}
	});

	it('a gated route has only proxy operations', () => {
		for (const route of gatedRoutes()) for (const id of ROUTE_OPS[route] ?? []) expect(CLASSES[id]).toBe('proxy');
	});

	it('stats, media and the import mapping are gated; note-types, home and review are not', () => {
		expect(gatedRoutes()).toEqual(expect.arrayContaining(['/stats', '/media', '/import/mapping']));
		expect(isGatedPath('/stats')).toBe(true);
		expect(isGatedPath('/media/abc/def')).toBe(true);
		expect(isGatedPath('/import/mapping')).toBe(true);
		expect(isGatedPath('/note-types')).toBe(false);
		expect(isGatedPath('/')).toBe(false);
		expect(isGatedPath('/review')).toBe(false);
		expect(isProxyOnly([])).toBe(false);
	});

	it('needs-connection only while offline', () => {
		expect(needsConnection('/stats', false)).toBe(true);
		expect(needsConnection('/stats', true)).toBe(false);
		expect(needsConnection('/cards', false)).toBe(false);
	});
});
