// The SPA end of the bridge (SHELL.md 2): `fetch` over `req`/`res` messages, plus delivery of the other native messages.
import { parseNativeMessage, validateWebMessage } from './protocol';
import type { HttpMethod, NativeMessage, ReqMessage, ResMessage, WebMessage } from './protocol';

/** `window.KotobayaShell`: the WebMessageListener object injected by the shell (SHELL.md 2.1). */
export interface ShellPort {
	postMessage(text: string): void;
	onmessage: ((ev: { data: unknown }) => void) | null;
}

export interface TransportOptions {
	/** No `res` within this many ms → TypeError (SHELL.md 2.5: 30 s). */
	timeoutMs?: number;
	/** Correlation ids; lower-case UUID text. */
	newId?: () => string;
}

export const REQUEST_TIMEOUT_MS = 30_000;

/** Request headers that never cross the bridge (SHELL.md 3.3, 9.3): the shell holds the credential. */
const DROPPED_HEADERS: readonly string[] = ['authorization', 'cookie', 'x-kotobaya-csrf'];
/** Statuses whose Response must have a null body (the Response constructor throws otherwise). */
const NULL_BODY_STATUS: readonly number[] = [204, 205, 304];
const METHODS: readonly string[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

interface Pending {
	resolve: (r: Response) => void;
	reject: (e: unknown) => void;
	done: () => void;
}

export function randomUuid(): string {
	const c = globalThis.crypto;
	if (typeof c.randomUUID === 'function') return c.randomUUID();
	// Insecure contexts (dev over a LAN address) lack randomUUID; getRandomValues is always there.
	const b = c.getRandomValues(new Uint8Array(16));
	b[6] = (b[6] & 0x0f) | 0x40;
	b[8] = (b[8] & 0x3f) | 0x80;
	const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function abortReason(signal: AbortSignal): unknown {
	return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError');
}

/** Split a fetch URL into the bridge `path` and `query`: origin and fragment dropped, nothing re-encoded. */
function splitUrl(input: string): { path: string; query: string } {
	let rest = input;
	if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(rest)) {
		const u = new URL(rest);
		rest = u.pathname + u.search;
	}
	const hash = rest.indexOf('#');
	if (hash >= 0) rest = rest.slice(0, hash);
	const q = rest.indexOf('?');
	return q < 0 ? { path: rest, query: '' } : { path: rest.slice(0, q), query: rest.slice(q + 1) };
}

function toResponse(res: ResMessage): Response {
	const body = NULL_BODY_STATUS.includes(res.status) ? null : res.body;
	return new Response(body, { status: res.status, headers: res.headers });
}

export class ShellTransport {
	readonly #port: ShellPort;
	readonly #timeoutMs: number;
	readonly #newId: () => string;
	readonly #pending = new Map<string, Pending>();
	readonly #listeners = new Set<(m: NativeMessage) => void>();

	constructor(port: ShellPort, opts: TransportOptions = {}) {
		this.#port = port;
		this.#timeoutMs = opts.timeoutMs ?? REQUEST_TIMEOUT_MS;
		this.#newId = opts.newId ?? randomUuid;
		port.onmessage = (ev) => this.receive(ev.data);
	}

	/** Requests still waiting for their `res`. */
	get pendingCount(): number {
		return this.#pending.size;
	}

	/**
	 * `fetch` over the bridge. Resolves with a real Response built from the matching `res`; rejects with TypeError
	 * when the request cannot be carried (non-string body, path outside /api/v1 and /shell/v1, …) or no `res`
	 * arrived within 30 s, and like `fetch` (AbortError) when the signal aborts.
	 */
	fetch = (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
		try {
			const signal = init.signal ?? undefined;
			if (signal?.aborted) return Promise.reject(abortReason(signal));
			const req = this.#encode(input, init);
			return this.#send(req, signal);
		} catch (e) {
			return Promise.reject(e instanceof TypeError ? e : new TypeError(String(e)));
		}
	};

	#encode(input: RequestInfo | URL, init: RequestInit): ReqMessage {
		if (typeof input !== 'string' && !(input instanceof URL)) {
			throw new TypeError('shellFetch: Request objects are not supported; pass a URL and init');
		}
		const { path, query } = splitUrl(typeof input === 'string' ? input : input.href);
		const method = (init.method ?? 'GET').toUpperCase();
		if (!METHODS.includes(method)) throw new TypeError(`shellFetch: method ${method} cannot cross the bridge`);
		const raw = init.body;
		if (raw !== undefined && raw !== null && typeof raw !== 'string') {
			throw new TypeError('shellFetch: only string bodies cross the bridge');
		}
		const body = raw ?? null;
		if (method === 'GET' && body !== null) throw new TypeError('shellFetch: a GET request cannot have a body');
		const headers: Record<string, string> = {};
		new Headers(init.headers).forEach((value, name) => {
			if (!DROPPED_HEADERS.includes(name)) headers[name] = value;
		});
		const req: ReqMessage = { v: 1, t: 'req', id: this.#newId(), method: method as HttpMethod, path, query, headers, body };
		if (!validateWebMessage(req)) throw new TypeError(`shellFetch: ${method} ${path} cannot cross the bridge`);
		return req;
	}

	#send(req: ReqMessage, signal: AbortSignal | undefined): Promise<Response> {
		return new Promise<Response>((resolve, reject) => {
			const timer = setTimeout(() => {
				if (!this.#pending.has(req.id)) return;
				p.done();
				reject(new TypeError(`shellFetch: no answer to ${req.method} ${req.path} within ${this.#timeoutMs / 1000} s`));
			}, this.#timeoutMs);
			const onAbort = (): void => {
				p.done();
				reject(abortReason(signal as AbortSignal));
			};
			const p: Pending = {
				resolve,
				reject,
				done: () => {
					clearTimeout(timer);
					signal?.removeEventListener('abort', onAbort);
					this.#pending.delete(req.id);
				}
			};
			this.#pending.set(req.id, p);
			signal?.addEventListener('abort', onAbort);
			try {
				this.#port.postMessage(JSON.stringify(req));
			} catch (e) {
				p.done();
				reject(new TypeError(`shellFetch: postMessage failed: ${String(e)}`));
			}
		});
	}

	/** Post a non-request message (ready, back.result, …). Throws TypeError when it is not valid to send. */
	send(msg: WebMessage): void {
		if (!validateWebMessage(msg)) throw new TypeError(`shell bridge: refusing to send an invalid ${String((msg as { t?: unknown }).t)}`);
		this.#port.postMessage(JSON.stringify(msg));
	}

	/** Receive every native message other than `res`. Returns the unsubscribe function. */
	subscribe(fn: (m: NativeMessage) => void): () => void {
		this.#listeners.add(fn);
		return () => {
			this.#listeners.delete(fn);
		};
	}

	/** One message from native. Invalid, unknown and unmatched messages are dropped silently (SHELL.md 2.3, 2.5). */
	receive(data: unknown): void {
		const parsed = parseNativeMessage(data);
		if (parsed.kind !== 'ok') return;
		const msg = parsed.msg;
		if (msg.t === 'res') {
			const p = this.#pending.get(msg.id);
			if (!p) return; // unknown, timed out, aborted or duplicate
			p.done();
			try {
				p.resolve(toResponse(msg));
			} catch (e) {
				p.reject(new TypeError(`shellFetch: unusable response: ${String(e)}`));
			}
			return;
		}
		for (const fn of [...this.#listeners]) {
			try {
				fn(msg);
			} catch {
				/* one listener's bug must not starve the others */
			}
		}
	}
}
