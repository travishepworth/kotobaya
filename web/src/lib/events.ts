import { API_BASE } from './api/client';

export type EventName =
	| 'hello'
	| 'sync.changed'
	| 'note.created'
	| 'job.updated'
	| 'device.paired'
	| 'device.revoked';

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
 */
export function openEventStream(opts: EventStreamOptions): () => void {
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
