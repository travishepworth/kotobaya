// Client error reporter (C-23 / ADR-024): batched, folded, bounded, rate-respecting.
// Pure: the transport and the timers are injected. Nothing here ever throws, and a failed
// report is never itself reported (the reporter has no path back into `report`).

export type ErrorKind = 'exception' | 'api_failure' | 'unhandled_rejection';
export type HttpMethod = 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** What a caller supplies; everything else is filled in and validated by the reporter. */
export interface ErrorInput {
	kind: ErrorKind;
	level?: 'error' | 'warn';
	message: string;
	stack?: string;
	route?: string;
	requestId?: string;
	httpMethod?: string;
	httpPath?: string;
	httpStatus?: number;
}

/** A validated item, in the wire (snake_case) shape of `ClientErrorItem`. */
export interface WireItem {
	client: 'web';
	app_version: string;
	occurred_at: string;
	level: 'error' | 'warn';
	kind: ErrorKind;
	message: string;
	stack?: string;
	route?: string;
	request_id?: string;
	http_method?: HttpMethod;
	http_path?: string;
	http_status?: number;
	count: number;
}

export interface WireReport {
	items: WireItem[];
	dropped?: number;
}

/** Transport outcome: an HTTP status (with Retry-After seconds for 429) or `network` for no answer. */
export type SendResult = { status: number; retryAfterS?: number } | 'network';
export type Send = (report: WireReport) => Promise<SendResult>;

export const MAX_BATCH = 20;
export const MAX_PENDING = 100;
export const MAX_MESSAGE = 2048;
export const MAX_STACK = 16384;
export const MAX_ROUTE = 512;
export const MIN_BACKOFF_MS = 30_000;
export const MAX_BACKOFF_MS = 15 * 60_000;
const FLUSH_DELAY_MS = 5_000;
const INT_MAX = 2147483647;
const METHODS: readonly string[] = ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'];

/** Truncate to `max` Unicode code points, ending with an ellipsis when cut. */
export function capCodePoints(text: string, max: number): string {
	const points = Array.from(text);
	if (points.length <= max) return text;
	return points.slice(0, max - 1).join('') + '…';
}

/** Cut a URL or path at the first `?` or `#`. */
export function stripQuery(path: string): string {
	const cut = path.search(/[?#]/);
	return cut < 0 ? path : path.slice(0, cut);
}

// Quoted segments can carry interpolated user text (note content, search strings): anything
// quoted that holds non-ASCII or whitespace, or is long, is replaced. Short ASCII identifiers
// such as `reading 'fields'` stay; they are what makes an error message useful.
const QUOTED = /(["'`])((?:(?!\1)[^\n\\]|\\.){0,2000}?)\1/g;
const TOKEN = /\bkb[sdp]_[A-Za-z0-9]{8,}/g;
const URL_QUERY = /(https?:\/\/[^\s)'"`?#]*|\/[\w./~%-]+)[?#][^\s)'"`]*/g;

function redactQuoted(text: string): string {
	return text.replace(QUOTED, (whole, q: string, inner: string) => {
		const risky = inner.length > 40 || /[^\x21-\x7E]/.test(inner);
		return risky ? `${q}…${q}` : whole;
	});
}

/**
 * Remove what must not leave the browser from an error message or stack: quoted text that may be
 * user content, token strings, query strings and fragments, and control characters; then cap.
 */
// Regex literals (`/^\d+-(.*)$/`) can embed user text; a literal needs a metacharacter so plain
// paths like /api/v1/x are not touched.
const REGEX_LITERAL = /(^|[\s(=:,[])\/(?![\s*/])((?:[^/\n\\]|\\.){2,300})\/[dgimsuvy]*/g;
// Text after "position" or ":" that starts with a non-ASCII character (JSON.parse and
// parser errors quote the offending input there): replaced up to the end of the line.
const AFTER_MARKER = /(\bposition\b[^\n]*?|:\s*)([^\x00-\x7F\u2026][^\n]*)/g;

function redactMarkers(text: string): string {
	let out = text.replace(REGEX_LITERAL, (whole, pre: string, body: string) =>
		/[\\^$*+?()[\]{}|]/.test(body) ? `${pre}/…/` : whole
	);
	out = out.replace(AFTER_MARKER, '$1…');
	return out;
}

export function scrub(text: string, max: number): string {
	// eslint-disable-next-line no-control-regex
	let out = text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ' ');
	out = out.replace(TOKEN, 'kb?_…');
	out = out.replace(URL_QUERY, '$1');
	out = redactQuoted(out);
	out = redactMarkers(out);
	return capCodePoints(out, max);
}

/**
 * Validate one input against the C-23 limits so a single bad item can never 422 a batch:
 * truncate, drop optional properties that would be invalid, never return an invalid item.
 */
export function toWireItem(input: ErrorInput, version: string, now: Date): WireItem {
	const message = scrub(input.message, MAX_MESSAGE).trim();
	const item: WireItem = {
		client: 'web',
		app_version: /^[\x20-\x7E]{1,64}$/.test(version) ? version : 'unknown',
		occurred_at: now.toISOString(),
		level: input.level === 'warn' ? 'warn' : 'error',
		kind: input.kind,
		message: message === '' ? 'unknown error' : message,
		count: 1
	};
	if (input.stack) {
		const stack = scrub(input.stack, MAX_STACK);
		if (stack !== '') item.stack = stack;
	}
	if (input.route) {
		const route = capCodePoints(stripQuery(input.route), MAX_ROUTE);
		if (route !== '') item.route = route;
	}
	if (input.requestId && /^[\x21-\x7E]{1,128}$/.test(input.requestId)) {
		item.request_id = input.requestId;
	}
	const method = input.httpMethod?.toUpperCase();
	if (method && METHODS.includes(method)) item.http_method = method as HttpMethod;
	if (input.httpPath) {
		const path = capCodePoints(stripQuery(input.httpPath), MAX_ROUTE);
		if (path !== '') item.http_path = path;
	}
	const status = input.httpStatus;
	if (status !== undefined && Number.isInteger(status) && status >= 100 && status <= 599) {
		item.http_status = status;
	}
	return item;
}

function foldKey(i: WireItem): string {
	return JSON.stringify([
		i.client,
		i.kind,
		i.level,
		i.message,
		i.route ?? null,
		i.http_method ?? null,
		i.http_path ?? null,
		i.http_status ?? null
	]);
}

/** Above this many serialized bytes a request may not use `keepalive` (browsers cap it near 64 KB). */
export const KEEPALIVE_LIMIT = 60_000;

export function bodyBytes(report: WireReport): number {
	return new TextEncoder().encode(JSON.stringify(report)).length;
}

/**
 * Outcome for a failed fetch that threw (no HTTP answer). A TypeError on a body too large for
 * keepalive is treated as 413 so the batch is split instead of blocking the queue forever.
 */
export function failedSendResult(report: WireReport): SendResult {
	return report.items.length > 1 && bodyBytes(report) >= KEEPALIVE_LIMIT
		? { status: 413 }
		: 'network';
}

export interface ReporterDeps {
	send: Send;
	version: string;
	/** Current route path; read at report time. */
	route?: () => string | undefined;
	now?: () => Date;
	/** Schedules `fn` after `ms`; returns a cancel function. */
	schedule?: (fn: () => void, ms: number) => () => void;
}

export interface Reporter {
	report(input: ErrorInput): void;
	/** Send now. Resolves when the queue is drained or the sender must wait. */
	flush(): Promise<void>;
	/** A credential is available again after a 401. */
	resume(): void;
	/** Cancel timers (HMR dispose). */
	dispose(): void;
	/** Observable state, for tests and diagnostics. */
	state(): { pending: number; dropped: number; paused: boolean };
}

export function createReporter(deps: ReporterDeps): Reporter {
	const now = deps.now ?? (() => new Date());
	const schedule =
		deps.schedule ??
		((fn: () => void, ms: number) => {
			const h = setTimeout(fn, ms);
			return () => clearTimeout(h);
		});

	let queue: WireItem[] = [];
	let dropped = 0;
	let flushing = false;
	let paused = false;
	let failures = 0;
	let cancelTimer: (() => void) | null = null;

	function drop(n: number): void {
		dropped = Math.min(INT_MAX, dropped + n);
	}

	function trim(): void {
		while (queue.length > MAX_PENDING) {
			queue.shift();
			drop(1);
		}
	}

	function arm(ms: number): void {
		if (cancelTimer) cancelTimer();
		cancelTimer = schedule(() => {
			cancelTimer = null;
			void flush();
		}, ms);
	}

	function enqueue(item: WireItem): void {
		const key = foldKey(item);
		const existing = queue.find((q) => foldKey(q) === key);
		if (existing) {
			existing.count = Math.min(INT_MAX, existing.count + item.count);
			return;
		}
		queue.push(item);
		trim();
	}

	/** Put unsent items back at the front, folding into newer identical ones. */
	function requeue(items: WireItem[]): void {
		const merged = [...items];
		for (const q of queue) {
			const key = foldKey(q);
			const same = merged.find((m) => foldKey(m) === key);
			if (same) same.count = Math.min(INT_MAX, same.count + q.count);
			else merged.push(q);
		}
		queue = merged;
		trim();
	}

	async function flush(): Promise<void> {
		if (flushing || paused) return;
		flushing = true;
		try {
			let chunk = MAX_BATCH;
			while (queue.length > 0 && !paused) {
				const batch = queue.splice(0, chunk);
				const sentDropped = dropped;
				const body: WireReport = { items: batch };
				if (sentDropped > 0) body.dropped = sentDropped;
				let result: SendResult;
				try {
					result = await deps.send(body);
				} catch {
					result = 'network';
				}
				const status = result === 'network' ? 0 : result.status;
				if (status === 202) {
					dropped = Math.max(0, dropped - sentDropped);
					failures = 0;
				} else if (status === 413) {
					if (batch.length > 1) {
						requeue(batch);
						chunk = Math.max(1, Math.floor(batch.length / 2));
					} else {
						drop(1);
					}
				} else if (status === 429) {
					requeue(batch);
					const wait = result !== 'network' && result.retryAfterS ? result.retryAfterS : 60;
					arm(Math.max(1, wait) * 1000);
					return;
				} else if (status === 401) {
					requeue(batch);
					paused = true;
					return;
				} else if (status === 0 || status === 500 || status === 503) {
					requeue(batch);
					const wait = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * 2 ** Math.min(failures, 10));
					failures++;
					arm(wait);
					return;
				} else {
					// Can never succeed (other 4xx, 502, ...): drop it and say so with the next batch.
					drop(batch.length);
				}
			}
		} finally {
			flushing = false;
		}
	}

	return {
		report(input) {
			try {
				const route = input.route ?? deps.route?.();
				enqueue(toWireItem({ ...input, route }, deps.version, now()));
				if (!paused && !cancelTimer && !flushing) arm(FLUSH_DELAY_MS);
			} catch {
				/* reporting must never throw */
			}
		},
		flush,
		dispose() {
			if (cancelTimer) cancelTimer();
			cancelTimer = null;
		},
		resume() {
			if (!paused) return;
			paused = false;
			if (queue.length > 0) arm(0);
		},
		state: () => ({ pending: queue.length, dropped, paused })
	};
}
