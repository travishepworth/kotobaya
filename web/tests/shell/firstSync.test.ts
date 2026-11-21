// LF-W3: the first-sync setup state (SHELL.md 10.18). Shown in shell mode only while `first_sync.done` is false,
// driven by `shell.sync` events, gone on the event that reports done, never shown again.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const INFO = {
	shell_version: '0.1.0',
	shell_version_code: 1,
	local_api_version: 3,
	paired: true,
	auth_required: false,
	server_url: 'https://k.example/api/v1',
	device_id: '01931f3e-0000-7000-8000-0000000000d4',
	device_name: 'Pixel',
	online: true,
	capabilities: ['sync'],
	web_version: '0.1.26',
	web_source: 'bundled'
};

function syncStatus(firstSync: { done: boolean; applied: number; total: number | null }): Record<string, unknown> {
	return {
		phase: 'syncing',
		paired: true,
		online: true,
		pending_ops: 0,
		rejected_ops: 0,
		last_sync_at: null,
		stale: false,
		pull_seq: firstSync.applied,
		clock_warning: false,
		minting_blocked: false,
		progress: null,
		last_error: null,
		consecutive_failures: 0,
		first_sync: firstSync
	};
}

class FakePort {
	onmessage: ((ev: { data: unknown }) => void) | null = null;
	postMessage(text: string): void {
		const m = JSON.parse(text) as Record<string, unknown>;
		if (m.t === 'req' && m.path === '/shell/v1/info') {
			queueMicrotask(() =>
				this.deliver({ v: 1, t: 'res', id: m.id, status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(INFO) })
			);
		}
	}
	deliver(m: unknown): void {
		this.onmessage?.({ data: JSON.stringify(m) });
	}
	event(data: Record<string, unknown>): void {
		this.deliver({ v: 1, t: 'event', event: { type: 'shell.sync', at: '2026-11-21T10:00:00.000Z', data } });
	}
}

beforeEach(() => vi.resetModules());
afterEach(() => vi.unstubAllGlobals());

describe('first sync setup state', () => {
	it('is never pending outside the shell, even with a not-done event', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		s.applySyncEvent(syncStatus({ done: false, applied: 0, total: null }));
		expect(s.shell.present).toBe(false);
		expect(s.isFirstSyncPending()).toBe(false);
	});

	it('is not pending before any value is read (no flash for a paired device)', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		s.shell.present = true;
		expect(s.isFirstSyncPending()).toBe(false);
	});

	it('shows while done is false, from shell.sync events, and disappears on the event that reports done', async () => {
		const port = new FakePort();
		vi.stubGlobal('window', { KotobayaShell: port });
		const s = await import('../../src/lib/state/shell.svelte');
		await s.initShell();
		port.event(syncStatus({ done: false, applied: 12000, total: 48213 }));
		expect(s.isFirstSyncPending()).toBe(true);
		expect(s.shell.firstSync).toEqual({ done: false, applied: 12000, total: 48213 });
		port.event(syncStatus({ done: true, applied: 48213, total: 48213 }));
		expect(s.isFirstSyncPending()).toBe(false);
	});

	it('is never shown again once done was seen', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		s.shell.present = true;
		s.applySyncEvent(syncStatus({ done: true, applied: 5, total: 5 }));
		s.applySyncEvent(syncStatus({ done: false, applied: 0, total: null }));
		expect(s.isFirstSyncPending()).toBe(false);
	});

	it('an indeterminate total (null) is still pending', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		s.shell.present = true;
		s.applySyncEvent(syncStatus({ done: false, applied: 0, total: null }));
		expect(s.isFirstSyncPending()).toBe(true);
		expect(s.shell.firstSync?.total).toBeNull();
	});

	it('a malformed shell.sync payload is ignored', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		s.shell.present = true;
		s.applySyncEvent({ phase: 'idle' });
		expect(s.shell.firstSync).toBeNull();
		expect(s.isFirstSyncPending()).toBe(false);
	});
});
