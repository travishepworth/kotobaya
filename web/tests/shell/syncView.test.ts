// SHELL-W2: how a sync status reads as a chip (48 h warning, pending, phases).
import { describe, expect, it } from 'vitest';
import type { ShellSyncStatus } from '../../src/lib/api/shell/generated';
import { formatBytes, syncView } from '../../src/lib/ui/syncView';

const NOW = Date.parse('2026-11-20T12:00:00Z');
const base: ShellSyncStatus = {
	phase: 'idle',
	paired: true,
	online: true,
	pendingOps: 0,
	rejectedOps: 0,
	lastSyncAt: new Date(NOW - 5 * 60_000),
	stale: false,
	pullSeq: 0,
	clockWarning: false,
	mintingBlocked: false,
	progress: null,
	lastError: null,
	consecutiveFailures: 0
};

describe('syncView', () => {
	it('recent sync reads as minutes, ok', () => {
		expect(syncView(base, NOW)).toMatchObject({ key: 'sync.minutes', params: { n: 5 }, tone: 'ok' });
	});
	it('48 h or the shell stale flag warns', () => {
		expect(syncView({ ...base, lastSyncAt: new Date(NOW - 3 * 86_400_000) }, NOW)).toMatchObject({ key: 'sync.stale', params: { n: 3 }, tone: 'warn' });
		expect(syncView({ ...base, stale: true }, NOW).key).toBe('sync.stale');
	});
	it('never synced warns', () => {
		expect(syncView({ ...base, lastSyncAt: null }, NOW).key).toBe('sync.never');
	});
	it('phases win over age; auth_required sends to pairing', () => {
		expect(syncView({ ...base, phase: 'offline' }, NOW).key).toBe('sync.offline');
		expect(syncView({ ...base, phase: 'syncing' }, NOW).tone).toBe('busy');
		expect(syncView({ ...base, phase: 'auth_required' }, NOW)).toMatchObject({ key: 'sync.authRequired', needsPairing: true });
	});
	it('formats bytes', () => {
		expect(formatBytes(512)).toBe('512 B');
		expect(formatBytes(52_428_800)).toBe('50 MB');
		expect(formatBytes(96_468_992)).toBe('92 MB');
	});
});
