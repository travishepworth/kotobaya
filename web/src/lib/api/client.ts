import { AuthApi, Configuration, EventsApi, ResponseError, FetchError } from './generated';
import type { Middleware, Problem } from './generated';
import { t } from '../i18n';
import { pushToast } from '../toasts.svelte';
import { reportApiFailure } from '../errors';

/** Same-origin API root; the ingress (or the vite dev proxy) routes /api to the server. */
export const API_BASE = '/api/v1';

/** Session-cookie writes must carry this header (contract section 2, CSRF). */
const CSRF_HEADERS = { 'X-Kotobaya-Csrf': '1' };

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

/**
 * Error toasts for every failed call, except those the caller handles itself:
 * login failures (shown inline) and 401 (turns into a redirect to /login).
 */
const errorToasts: Middleware = {
	async post({ response, url, init }) {
		if (response.ok || url.endsWith('/auth/login')) return;
		if (response.status === 401) {
			if (!url.endsWith('/auth/session')) hooks.onUnauthenticated();
			return;
		}
		const problem = await readProblem(response);
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
		pushToast(problemMessage(problem, response.status), 'error');
	},
	async onError({ error, url, init }) {
		if (error instanceof FetchError || error instanceof TypeError) {
			reportApiFailure({ method: init?.method, url });
			pushToast(t('error.network'), 'error');
		}
	}
};

export const apiConfig = new Configuration({
	basePath: API_BASE,
	credentials: 'same-origin',
	headers: CSRF_HEADERS,
	middleware: [errorToasts]
});

export const authApi = new AuthApi(apiConfig);
export const eventsApi = new EventsApi(apiConfig);

export { ResponseError };
