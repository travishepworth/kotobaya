import { AuthApi, Configuration, EventsApi, ResponseError, FetchError } from './generated';
import type { Middleware, Problem } from './generated';
import { t } from '../i18n';
import { pushToast } from '../toasts.svelte';
import { reportApiFailure } from '../errors';
import { isShellMode, shellFetch } from './shell';

/** Same-origin API root; the ingress (or the vite dev proxy) routes /api to the server. */
export const API_BASE = '/api/v1';

/** Session-cookie writes must carry this header (contract section 2, CSRF). */
const CSRF_HEADERS = { 'X-Kotobaya-Csrf': '1' };

/** Shell mode is fixed for the page's life: `window.KotobayaShell` exists before any script runs (SHELL.md 2.1). */
const SHELL = isShellMode();

/** Shell problems that mean "needs the network": one calm toast, never reported (SHELL.md 5). */
const SHELL_OFFLINE_CODES: readonly string[] = ['shell_offline', 'shell_dict_missing'];
const MAX_RETRY_AFTER_S = 10;

/** Parse an RFC 9457 problem body; null when the body is not one. */
export async function readProblem(response: Response): Promise<Problem | null> {
	try {
		const body: unknown = await response.clone().json();
		if (typeof body === 'object' && body !== null && 'code' in body && 'status' in body) {
			return body as Problem;
		}
	} catch {
		/* not JSON */
	}
	return null;
}

export function problemMessage(p: Problem | null, status: number): string {
	if (status === 503) return t('error.unavailable');
	if (!p) return t('error.generic', { code: status });
	if (status === 403) return t('error.forbidden', { code: p.code });
	if (status === 422 && p.detail) return t('error.validation', { detail: p.detail });
	return t('error.generic', { code: p.code });
}

/** Callbacks the session layer registers so the client can react to a lost session. */
const hooks: { onUnauthenticated: () => void } = { onUnauthenticated: () => {} };
export function setUnauthenticatedHandler(fn: () => void): void {
	hooks.onUnauthenticated = fn;
}

function retryAfterMs(response: Response): number {
	const s = Number(response.headers.get('Retry-After') ?? '');
	const seconds = response.headers.has('Retry-After') && Number.isFinite(s) && s >= 0 ? s : 2;
	return Math.min(seconds, MAX_RETRY_AFTER_S) * 1000;
}

function delay(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Error toasts for every failed call, except those the caller handles itself:
 * login failures (shown inline) and 401 (turns into a redirect to /login).
 * Shell mode (SHELL.md 5): `shell_offline` / `shell_dict_missing` give one calm toast and are not reported;
 * `shell_rebuilding` is retried once after its Retry-After.
 */
function errorMiddleware(toast: boolean): Middleware {
	const handle = async (
		response: Response,
		url: string,
		init: RequestInit,
		mayRetry: boolean
	): Promise<Response | undefined> => {
		if (response.ok || url.endsWith('/auth/login')) return undefined;
		if (response.status === 401) {
			if (!url.endsWith('/auth/session')) hooks.onUnauthenticated();
			return undefined;
		}
		const problem = await readProblem(response);
		if (SHELL && response.status === 503 && problem) {
			if (problem.code === 'shell_rebuilding') {
				if (mayRetry) {
					await delay(retryAfterMs(response));
					const again = await shellFetch(url, init);
					await handle(again.clone(), url, init, false);
					return again;
				}
				if (toast) pushToast(t('error.rebuilding'), 'info');
				return undefined;
			}
			if (SHELL_OFFLINE_CODES.includes(problem.code)) {
				if (toast) pushToast(t('error.offline'), 'info');
				return undefined;
			}
		}
		// 5xx only: 4xx are validation and state answers the UI handles.
		if (response.status >= 500) {
			reportApiFailure({
				method: init?.method,
				url,
				status: response.status,
				code: problem?.code,
				requestId: response.headers.get('X-Request-Id') ?? problem?.requestId
			});
		}
		if (toast) pushToast(problemMessage(problem, response.status), 'error');
		return undefined;
	};
	return {
		async post({ response, url, init }) {
			return handle(response, url, init, true);
		},
		async onError({ error, url, init }) {
			if (error instanceof FetchError || error instanceof TypeError) {
				reportApiFailure({ method: init?.method, url });
				if (toast) pushToast(t('error.network'), 'error');
			}
		}
	};
}

/**
 * Browser: same-origin cookie session plus the CSRF header (contract section 2). Shell mode: every call goes over
 * the bridge with no credentials and no CSRF header; the shell holds the device token (SHELL.md 9.3).
 */
function configuration(toast: boolean): Configuration {
	const middleware = [errorMiddleware(toast)];
	return SHELL
		? new Configuration({ basePath: API_BASE, fetchApi: shellFetch, middleware })
		: new Configuration({ basePath: API_BASE, credentials: 'same-origin', headers: CSRF_HEADERS, middleware });
}

export const apiConfig = configuration(true);

/**
 * RC-WEB: for background widgets that show their own inline "couldn't load" + retry (stats panels). Failures are
 * still reported and a 401 still signs out, but no global error toast: a slow or 504 stats call is not an error
 * of the whole app.
 */
export const quietApiConfig = configuration(false);

export const authApi = new AuthApi(apiConfig);
export const eventsApi = new EventsApi(apiConfig);

export { ResponseError };
