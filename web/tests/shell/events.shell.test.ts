// SHELL-W1: in shell mode openEventStream listens to bridge `event` messages (SHELL.md 2.6), same options contract.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerEvent } from '../../src/lib/events';

class FakePort {
	onmessage: ((ev: { data: unknown }) => void) | null = null;
	sent: Record<string, unknown>[] = [];
	postMessage(text: string): void {
		this.sent.push(JSON.parse(text) as Record<string, unknown>);
	}
	event(type: string, data: Record<string, unknown> = {}): void {
		this.onmessage?.({ data: JSON.stringify({ v: 1, t: 'event', event: { type, at: '2026-11-20T10:00:00.000Z', data } }) });
	}
}

let port: FakePort;
let events: typeof import('../../src/lib/events');

beforeEach(async () => {
	vi.resetModules();
	port = new FakePort();
	vi.stubGlobal('window', { KotobayaShell: port });
	events = await import('../../src/lib/events');
});

afterEach(() => {
	vi.unstubAllGlobals();
});

const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe('openEventStream in shell mode', () => {
	it('hello triggers onReconnect, onEvent and onStatus(true); no EventSource is used', () => {
		vi.stubGlobal('EventSource', () => {
			throw new Error('EventSource must not be used in shell mode');
		});
		const onEvent = vi.fn();
		const onReconnect = vi.fn();
		const onStatus = vi.fn();
		events.openEventStream({ onEvent, onReconnect, onStatus });
		port.event('hello', { high_water_seq: 0 });
		expect(onReconnect).toHaveBeenCalledTimes(1);
		expect((onReconnect.mock.calls[0][0] as ServerEvent).type).toBe('hello');
		expect(onEvent).toHaveBeenCalledTimes(1);
		expect(onStatus).toHaveBeenCalledWith(true);
	});

	it('sends ready once, however many streams are opened', () => {
		events.openEventStream({ onEvent: () => {} });
		events.openEventStream({ onEvent: () => {} });
		const ready = port.sent.filter((m) => m.t === 'ready');
		expect(ready).toHaveLength(1);
		expect(ready[0]).toMatchObject({ v: 1, min_local_api: 2 });
	});

	it('respects the types filter; hello always passes', () => {
		const seen: string[] = [];
		const onReconnect = vi.fn();
		events.openEventStream({ types: ['sync.changed'], onEvent: (e) => seen.push(e.type), onReconnect });
		port.event('note.created', { note_id: 'x' });
		port.event('hello');
		port.event('sync.changed', { high_water_seq: 1, pending_ops: 0 });
		port.event('shell.connectivity', { online: false });
		expect(seen).toEqual(['hello', 'sync.changed']);
		expect(onReconnect).toHaveBeenCalledTimes(1);
	});

	it('without a filter delivers every known type and drops unknown ones', () => {
		const seen: string[] = [];
		events.openEventStream({ onEvent: (e) => seen.push(e.type) });
		port.event('sync.changed');
		port.event('note.created');
		port.event('shell.connectivity', { online: true });
		port.event('some.future_type');
		expect(seen).toEqual(['sync.changed', 'note.created', 'shell.connectivity']);
	});

	it('a stream opened after the hello still gets it (as each SSE connection would)', async () => {
		events.openEventStream({ onEvent: () => {} });
		port.event('hello', { high_water_seq: 7 });
		const onReconnect = vi.fn();
		events.openEventStream({ onEvent: () => {}, onReconnect });
		await flush();
		expect(onReconnect).toHaveBeenCalledTimes(1);
		expect((onReconnect.mock.calls[0][0] as ServerEvent).data).toEqual({ high_water_seq: 7 });
	});

	it('close stops delivery', async () => {
		const onEvent = vi.fn();
		const close = events.openEventStream({ onEvent });
		port.event('sync.changed');
		close();
		port.event('sync.changed');
		port.event('hello');
		await flush();
		expect(onEvent).toHaveBeenCalledTimes(1);
	});
});
