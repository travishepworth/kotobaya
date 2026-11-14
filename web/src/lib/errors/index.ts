// Wiring of the reporter to the browser and the API layer (see reporter.ts for the logic).
import { MetaApi, Configuration, ResponseError } from '../api/generated';
import type { ClientErrorItem } from '../api/generated';
import { createReporter } from './reporter';
import type { ErrorInput, Send, WireItem } from './reporter';

const APP_VERSION: string = import.meta.env?.VITE_APP_VERSION || '0.0.0-dev';

/** Path of the report endpoint; failures of this call are never reported. */
export const REPORT_PATH = '/client-errors';

// A client with no middleware: no toasts, no 401 redirect, no reporting of its own failures.
const reportApi = new MetaApi(
	new Configuration({
		basePath: '/api/v1',
		credentials: 'same-origin',
		headers: { 'X-Kotobaya-Csrf': '1' }
	})
);

function toClientItem(i: WireItem): ClientErrorItem {
	return {
		client: i.client,
		appVersion: i.app_version,
		occurredAt: new Date(i.occurred_at),
		level: i.level,
		kind: i.kind,
		message: i.message,
		stack: i.stack,
		route: i.route,
		requestId: i.request_id,
		httpMethod: i.http_method,
		httpPath: i.http_path,
		httpStatus: i.http_status,
		count: i.count
	};
}

const send: Send = async (report) => {
	try {
		await reportApi.reportClientErrors(
			{
				clientErrorReport: {
					items: report.items.map(toClientItem),
					...(report.dropped ? { dropped: report.dropped } : {})
				}
			},
			// keepalive so a report sent while the page is going away still leaves.
			{ keepalive: true }
		);
		return { status: 202 };
	} catch (e) {
		if (e instanceof ResponseError) {
			const ra = Number(e.response.headers.get('Retry-After'));
			return {
				status: e.response.status,
				retryAfterS: Number.isFinite(ra) && ra > 0 ? ra : undefined
			};
		}
		return 'network';
	}
};

const reporter = createReporter({
	send,
	version: APP_VERSION,
	route: () => globalThis.location?.pathname
});

/** Report an error; safe to call from anywhere, never throws. */
export function reportClientError(input: ErrorInput): void {
	reporter.report(input);
}

/** A credential is available again (logged in); releases a reporter paused by a 401. */
export function resumeErrorReports(): void {
	reporter.resume();
}

function describe(e: unknown): { message: string; stack?: string } {
	if (e instanceof Error) return { message: `${e.name}: ${e.message}`, stack: e.stack };
	if (typeof e === 'string') return { message: e };
	return { message: `non-Error value (${typeof e})` };
}

/** Report an exception caught by the framework (SvelteKit `handleError`). */
export function reportException(e: unknown): void {
	reportClientError({ kind: 'exception', ...describe(e) });
}

/** An API call that failed with a 5xx or at the network level (the api layer calls this). */
export function reportApiFailure(f: {
	method?: string;
	url: string;
	status?: number;
	code?: string;
	requestId?: string;
}): void {
	let path = f.url;
	try {
		path = new URL(f.url, globalThis.location?.origin ?? 'http://localhost').pathname;
	} catch {
		/* keep the raw url; the reporter strips query and fragment */
	}
	if (path.endsWith(REPORT_PATH)) return; // never report the reporter's own calls
	const method = (f.method ?? 'GET').toUpperCase();
	const outcome =
		f.status === undefined ? 'network error' : [f.status, f.code].filter(Boolean).join(' ');
	reportClientError({
		kind: 'api_failure',
		message: `${method} ${path} failed: ${outcome}`,
		httpMethod: method,
		httpPath: path,
		httpStatus: f.status,
		requestId: f.requestId
	});
}

let installed = false;

/** Register window.onerror and unhandledrejection handlers (idempotent). */
export function installGlobalErrorHandlers(target: Window = window): void {
	if (installed) return;
	installed = true;
	target.addEventListener('error', (ev: ErrorEvent) => {
		try {
			if (!ev.error && !ev.message) return; // resource load errors carry nothing useful
			reportClientError({
				kind: 'exception',
				...(ev.error ? describe(ev.error) : { message: ev.message })
			});
		} catch {
			/* never throw from a handler */
		}
	});
	target.addEventListener('unhandledrejection', (ev: PromiseRejectionEvent) => {
		try {
			reportClientError({ kind: 'unhandled_rejection', ...describe(ev.reason) });
		} catch {
			/* never throw from a handler */
		}
	});
}
