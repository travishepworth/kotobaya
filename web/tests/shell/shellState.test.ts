// SHELL-W1: shell state (present, info, online, has, modeOf) and the `ready` handshake.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const INFO = {
	shell_version: '0.1.0',
	shell_version_code: 1,
	local_api_version: 1,
	paired: true,
	auth_required: false,
	server_url: 'https://k.example/api/v1',
	device_id: '01931f3e-0000-7000-8000-0000000000d4',
	device_name: 'Pixel',
	online: true,
	capabilities: ['sync', 'pairing'],
	web_version: '0.1.26',
	web_source: 'bundled'
};

class FakePort {
	onmessage: ((ev: { data: unknown }) => void) | null = null;
	sent: Record<string, unknown>[] = [];
	postMessage(text: string): void {
		const m = JSON.parse(text) as Record<string, unknown>;
		this.sent.push(m);
		if (m.t === 'req' && m.path === '/shell/v1/info') {
			queueMicrotask(() =>
				this.deliver({ v: 1, t: 'res', id: m.id, status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(INFO) })
			);
		}
	}
	deliver(m: unknown): void {
		this.onmessage?.({ data: JSON.stringify(m) });
	}
}

beforeEach(() => vi.resetModules());
afterEach(() => vi.unstubAllGlobals());

describe('browser', () => {
	it('not present, online, no capabilities; modeOf still reads ops.json', async () => {
		const s = await import('../../src/lib/state/shell.svelte');
		await s.initShell();
		expect(s.shell.present).toBe(false);
		expect(s.shell.online).toBe(true);
		expect(s.has('sync')).toBe(false);
		expect(s.modeOf('answerReview')).toBe('local');
		expect(s.modeOf('getStatsSummary')).toBe('proxy');
		expect(s.modeOf('logout')).toBe('hidden');
		expect(s.modeOf('searchDictionary')).toBe('local_or_proxy');
		expect(s.modeOf('noSuchOperation')).toBeUndefined();
		expect(s.modeOf('toString')).toBeUndefined();
	});
});

describe('shell', () => {
	it('sends ready once, loads ShellInfo, follows shell.connectivity', async () => {
		const port = new FakePort();
		vi.stubGlobal('window', { KotobayaShell: port });
		const s = await import('../../src/lib/state/shell.svelte');
		expect(s.shell.present).toBe(true);
		await s.initShell();
		await s.initShell();
		expect(port.sent.filter((m) => m.t === 'ready')).toHaveLength(1);
		expect(s.shell.info?.deviceName).toBe('Pixel');
		expect(s.has('sync')).toBe(true);
		expect(s.has('ota')).toBe(false);
		expect(s.shell.incompatible).toBe(false);
		port.deliver({ v: 1, t: 'event', event: { type: 'shell.connectivity', at: '2026-11-20T10:01:00.000Z', data: { online: false } } });
		expect(s.shell.online).toBe(false);
	});
});
