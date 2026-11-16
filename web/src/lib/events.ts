import { API_BASE } from './api/client';
import { getShellTransport, lastHello, sendReady } from './api/shell';
import type { BridgeEvent } from './api/shell/protocol';
import type { ShellTransport } from './api/shell/transport';

export type EventName =
	| 'hello'
	| 'sync.changed'
	| 'note.created'
	| 'job.updated'
	| 'device.paired'
	| 'device.revoked'
	// shell mode only (SHELL.md 2.6)
	| 'shell.connectivity'
	| 'shell.sync'
	| 'shell.dict'
	| 'shell.state_changed';

const EVENT_NAMES: readonly EventName[] = [
	'hello',
	'sync.changed',
	'note.created',
	'job.updated',
	'device.paired',
	'device.revoked',
	'shell.connectivity',
	'shell.sync',
	'shell.dict',
	'shell.state_changed'
];

/** The `Event` envelope of GET /events (raw wire shape: `at` stays a string). */
export interface ServerEvent {
	type: EventName;
	at: string;
	data: Record<string, unknown>;
}

export interface EventStreamOptions {
	/** Only these types (hello is always delivered). Default: all. */
	types?: EventName[];
	onEvent: (e: ServerEvent) => void;
	/** Fired on every (re)connect after the hello event: refetch what you show (no replay). */
	onReconnect?: (e: ServerEvent) => void;
	onStatus?: (connected: boolean) => void;
}

/**
 * Subscribe to GET /events. Returns a close function. EventSource reconnects by itself;
 * the server sends `hello` first on every connection, which is where callers refetch.
 * Cookie auth is same-origin, so no credentials plumbing is needed.
 * In the Android shell the events arrive as bridge `event` messages instead (SHELL.md 2.6), same contract.
 */
export function openEventStream(opts: EventStreamOptions): () => void {
	const shell = getShellTransport();
	if (shell) return openShellEventStream(shell, opts);
	const query = opts.types ? `?types=${encodeURIComponent(opts.types.join(','))}` : '';
	const es = new EventSource(`${API_BASE}/events${query}`);
	const names: EventName[] = [
		'hello',
		'sync.changed',
		'note.created',
		'job.updated',
		'device.paired',
		'device.revoked'
	];
	const handle = (msg: MessageEvent<string>): void => {
		let parsed: ServerEvent;
		try {
			parsed = JSON.parse(msg.data) as ServerEvent;
		} catch {
			return;
		}
		if (parsed.type === 'hello') opts.onReconnect?.(parsed);
		opts.onEvent(parsed);
	};
	for (const n of names) es.addEventListener(n, handle as EventListener);
	es.onopen = () => opts.onStatus?.(true);
	es.onerror = () => opts.onStatus?.(false);
	return () => es.close();
}

function toServerEvent(e: BridgeEvent): ServerEvent | null {
	const type = e.type as EventName;
	if (!EVENT_NAMES.includes(type)) return null; // receivers ignore unknown types
	return { type, at: e.at, data: e.data };
}

function openShellEventStream(shell: ShellTransport, opts: EventStreamOptions): () => void {
	let open = true;
	const deliver = (e: ServerEvent): void => {
		if (!open) return;
		if (e.type === 'hello') {
			opts.onStatus?.(true);
			opts.onReconnect?.(e);
		} else if (opts.types && !opts.types.includes(e.type)) {
			return;
		}
		opts.onEvent(e);
	};
	const off = shell.subscribe((m) => {
		const e = m.t === 'event' ? toServerEvent(m.event) : null;
		if (e) deliver(e);
	});
	// A stream opened after this page life's hello gets it too, as each SSE connection gets its own.
	const seen = lastHello();
	const replay = seen ? toServerEvent(seen) : null;
	if (replay) queueMicrotask(() => deliver(replay));
	sendReady();
	return () => {
		open = false;
		off();
	};
}
