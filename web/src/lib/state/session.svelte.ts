import type { Principal } from '../api/generated';
import { ResponseError, authApi, readProblem, setUnauthenticatedHandler } from '../api/client';
import { resumeErrorReports } from '../errors';
import { isShellMode } from '../api/shell';
import { openEventStream } from '../events';
import { initShell } from './shell.svelte';
import { clearOutbox } from './review.svelte';
import { t } from '../i18n';
import { pushToast } from '../toasts.svelte';

export type LoginResult =
	| { ok: true }
	| { ok: false; reason: 'invalid' | 'totp_required' | 'rate_limited' | 'other'; retryAfter?: number };

/** Who is logged in. `status` is 'unknown' until the first /auth/session answer. */
export const session = $state<{ status: 'unknown' | 'in' | 'out'; principal: Principal | null }>({
	status: 'unknown',
	principal: null
});

function setOut(): void {
	session.status = 'out';
	session.principal = null;
}

setUnauthenticatedHandler(setOut);

/**
 * Ask the server whether the cookie is still a valid session. In the Android shell the same call is answered
 * locally (`getPrincipal`); a 401 there means "not paired / re-pair" and leads to /login as on the web.
 */
export async function refreshSession(): Promise<void> {
	void initShell(); // shell mode: send `ready` and load ShellInfo once per page life; no-op in a browser
	try {
		session.principal = await authApi.getPrincipal();
		session.status = 'in';
		resumeErrorReports();
	} catch (e) {
		// Network errors already raised a toast; anything else means "not logged in".
		if (e instanceof ResponseError) setOut();
		else if (session.status === 'unknown') setOut();
	}
}

export async function login(password: string, totpCode?: string): Promise<LoginResult> {
	try {
		session.principal = await authApi.login({
			loginRequest: { password, ...(totpCode ? { totpCode } : {}) }
		});
		session.status = 'in';
		resumeErrorReports();
		return { ok: true };
	} catch (e) {
		if (e instanceof ResponseError) {
			const p = await readProblem(e.response);
			if (p?.code === 'totp_required') return { ok: false, reason: 'totp_required' };
			if (e.response.status === 401) return { ok: false, reason: 'invalid' };
			if (e.response.status === 429) {
				const ra = Number(e.response.headers.get('Retry-After'));
				return { ok: false, reason: 'rate_limited', ...(ra > 0 ? { retryAfter: ra } : {}) };
			}
		}
		return { ok: false, reason: 'other' };
	}
}

/** Whether "Log out" is offered: not in the Android shell, where the device unpairs instead (SHELL.md 9.1). */
export const canLogout = !isShellMode();

export async function logout(): Promise<void> {
	if (!canLogout) return; // `logout` is a hidden operation in the shell (403); nothing to do
	try {
		await authApi.logout();
	} finally {
		// Unsent answers belong to the user who logged out (LF-W1): gone here and in IndexedDB.
		await clearOutbox().catch(() => {});
		setOut();
	}
}

/**
 * The app-wide live-update stream while logged in (screens subscribe on their own for specific events).
 * Shows one "reconnecting" toast per outage. Returns the close function.
 */
export function watchConnection(): () => void {
	let shown = false;
	return openEventStream({
		types: ['hello'],
		onEvent: () => {},
		onStatus: (ok: boolean) => {
			if (ok) shown = false;
			else if (!shown) {
				shown = true;
				pushToast(t('events.reconnecting'), 'info');
			}
		}
	});
}
