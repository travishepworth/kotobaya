// How a `ShellSyncStatus` reads as a chip: one message and a tone. Pure; `now` is injected.
import type { ShellSyncStatus } from '../api/shell/generated';
import type { ShellMessageKey } from './shellMessages';

export type SyncTone = 'ok' | 'busy' | 'warn' | 'error';
export interface SyncView {
	key: ShellMessageKey;
	params: Record<string, number>;
	tone: SyncTone;
	/** Tapping opens the pair screen instead of syncing. */
	needsPairing: boolean;
}

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

export function syncView(s: ShellSyncStatus, now: number): SyncView {
	const view = (key: ShellMessageKey, tone: SyncTone, params: Record<string, number> = {}, needsPairing = false): SyncView => ({
		key,
		params,
		tone,
		needsPairing
	});
	switch (s.phase) {
		case 'syncing':
			return view('sync.syncing', 'busy');
		case 'auth_required':
			return view('sync.authRequired', 'error', {}, true);
		case 'upgrade_required':
			return view('sync.upgrade', 'error');
		case 'clock_wrong':
			return view('sync.clockWrong', 'error');
		case 'rebuilding':
			return view('sync.rebuilding', 'busy');
		case 'error':
			return view('sync.error', 'warn');
		case 'unreachable':
			return view('sync.unreachable', 'warn');
		case 'offline':
			return view('sync.offline', 'warn');
		default:
			break;
	}
	if (s.lastSyncAt === null) return view('sync.never', 'warn');
	const age = Math.max(0, now - s.lastSyncAt.getTime());
	// The 48 h warning (stale) comes from the shell; the client side agrees with it by age as well.
	if (s.stale || age >= 2 * DAY) return view('sync.stale', 'warn', { n: Math.max(2, Math.floor(age / DAY)) });
	if (age < MIN) return view('sync.justNow', 'ok');
	if (age < HOUR) return view('sync.minutes', 'ok', { n: Math.floor(age / MIN) });
	if (age < DAY) return view('sync.hours', 'ok', { n: Math.floor(age / HOUR) });
	return view('sync.days', 'ok', { n: Math.floor(age / DAY) });
}

/** Human size in binary units. */
export function formatBytes(n: number): string {
	if (n < 1024) return `${n} B`;
	if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
	if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)} MB`;
	return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
