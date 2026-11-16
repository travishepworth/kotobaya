// SHELL-W1: the generated-client configuration in browser mode (unchanged) and in shell mode (SHELL.md 9.3, 5).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const reportApiFailure = vi.fn();
vi.mock('../../src/lib/errors', () => ({
	reportApiFailure: (...a: unknown[]) => reportApiFailure(...a),
	resumeErrorReports: () => {}
}));

class FakePort {
	onmessage: ((ev: { data: unknown }) => void) | null = null;
	sent: Record<string, unknown>[] = [];
	postMessage(text: string): void {
		this.sent.push(JSON.parse(text) as Record<string, unknown>);
	}
	reqs(): { id: string; path: string; method: string }[] {
		return this.sent.filter((m) => m.t === 'req') as unknown as { id: string; path: string; method: string }[];
	}
	answer(id: string, status: number, body: unknown, headers: Record<string, string> = {}): void {
		this.onmessage?.({
			data: JSON.stringify({ v: 1, t: 'res', id, status, headers, body: body === null ? null : JSON.stringify(body) })
		});
	}
}

function problem(code: string, status: number): Record<string, unknown> {
	return { type: `urn:kotobaya:problem:${code}`, title: code, status, code, request_id: 'r1' };
}
const PROBLEM = { 'content-type': 'application/problem+json' };

/** Wait until the port has seen `n` requests (the generated client sends after a few awaits). */
async function untilReqs(port: FakePort, n: number): Promise<void> {
	for (let i = 0; i < 50 && port.reqs().length < n; i++) await Promise.resolve();
	expect(port.reqs().length).toBeGreaterThanOrEqual(n);
}

beforeEach(() => {
	vi.resetModules();
	reportApiFailure.mockReset();
});

afterEach(() => {
	vi.unstubAllGlobals();
	vi.useRealTimers();
});

describe('browser mode (no KotobayaShell)', () => {
	it('configuration equals today: basePath, same-origin credentials, CSRF header, global fetch', async () => {
		const { apiConfig, quietApiConfig } = await import('../../src/lib/api/client');
		for (const c of [apiConfig, quietApiConfig]) {
			expect(c.basePath).toBe('/api/v1');
			expect(c.credentials).toBe('same-origin');
			expect(c.headers).toEqual({ 'X-Kotobaya-Csrf': '1' });
			expect(c.fetchApi).toBeUndefined();
			expect(c.middleware).toHaveLength(1);
		}
		const shell = await import('../../src/lib/api/shell');
		expect(shell.isShellMode()).toBe(false);
	});
});

describe('shell mode', () => {
	let port: FakePort;
	beforeEach(() => {
		port = new FakePort();
		vi.stubGlobal('window', { KotobayaShell: port });
	});

	it('both configurations use shellFetch with no credentials and no CSRF header', async () => {
		const { apiConfig, quietApiConfig } = await import('../../src/lib/api/client');
		const { shellFetch } = await import('../../src/lib/api/shell');
		const { shellApiConfig } = await import('../../src/lib/api/shell/client');
		for (const c of [apiConfig, quietApiConfig]) {
			expect(c.basePath).toBe('/api/v1');
			expect(c.fetchApi).toBe(shellFetch);
			expect(c.credentials).toBeUndefined();
			expect(c.headers).toBeUndefined();
		}
		expect(shellApiConfig.basePath).toBe('/shell/v1');
		expect(shellApiConfig.fetchApi).toBe(shellFetch);
	});

	it('a generated call travels as a req and resolves from the res', async () => {
		const { apiConfig } = await import('../../src/lib/api/client');
		const { MetaApi } = await import('../../src/lib/api/generated');
		const p = new MetaApi(apiConfig).getHealth();
		await untilReqs(port, 1);
		const [req] = port.reqs();
		expect(req).toMatchObject({ method: 'GET', path: '/api/v1/health' });
		port.answer(req.id, 200, { status: 'ok' }, { 'content-type': 'application/json' });
		await expect(p).resolves.toMatchObject({ status: 'ok' });
	});

	it('shell_offline and shell_dict_missing: one calm toast, not reported', async () => {
		const { apiConfig } = await import('../../src/lib/api/client');
		const { MetaApi } = await import('../../src/lib/api/generated');
		const { toasts } = await import('../../src/lib/toasts.svelte');
		const { t } = await import('../../src/lib/i18n');
		const api = new MetaApi(apiConfig);
		const a = api.getHealth().catch((e: unknown) => e);
		const b = api.getHealth().catch((e: unknown) => e);
		await untilReqs(port, 2);
		const [r1, r2] = port.reqs();
		port.answer(r1.id, 503, problem('shell_offline', 503), PROBLEM);
		port.answer(r2.id, 503, problem('shell_dict_missing', 503), PROBLEM);
		await a;
		await b;
		const offline = toasts.items.filter((x) => x.message === t('error.offline'));
		expect(offline).toHaveLength(1);
		expect(offline[0].kind).toBe('info');
		expect(toasts.items.some((x) => x.kind === 'error')).toBe(false);
		expect(reportApiFailure).not.toHaveBeenCalled();
	});

	it('shell_rebuilding: retried once after Retry-After', async () => {
		vi.useFakeTimers();
		const { apiConfig } = await import('../../src/lib/api/client');
		const { MetaApi } = await import('../../src/lib/api/generated');
		const p = new MetaApi(apiConfig).getHealth();
		await untilReqs(port, 1);
		port.answer(port.reqs()[0].id, 503, problem('shell_rebuilding', 503), { ...PROBLEM, 'retry-after': '2' });
		await vi.advanceTimersByTimeAsync(1_999);
		expect(port.reqs()).toHaveLength(1);
		await vi.advanceTimersByTimeAsync(1);
		await untilReqs(port, 2);
		port.answer(port.reqs()[1].id, 200, { status: 'ok' }, { 'content-type': 'application/json' });
		await expect(p).resolves.toMatchObject({ status: 'ok' });
		expect(reportApiFailure).not.toHaveBeenCalled();
	});

	it('shell_rebuilding twice: no third attempt, the call fails', async () => {
		vi.useFakeTimers();
		const { apiConfig } = await import('../../src/lib/api/client');
		const { MetaApi } = await import('../../src/lib/api/generated');
		const p = new MetaApi(apiConfig).getHealth().catch((e: unknown) => e);
		await untilReqs(port, 1);
		port.answer(port.reqs()[0].id, 503, problem('shell_rebuilding', 503), { ...PROBLEM, 'retry-after': '2' });
		await vi.advanceTimersByTimeAsync(2_000);
		await untilReqs(port, 2);
		port.answer(port.reqs()[1].id, 503, problem('shell_rebuilding', 503), { ...PROBLEM, 'retry-after': '2' });
		const err = (await p) as { name: string; response: Response };
		expect(err.name).toBe('ResponseError');
		expect(err.response.status).toBe(503);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(port.reqs()).toHaveLength(2);
		expect(reportApiFailure).not.toHaveBeenCalled();
	});

	it('a local 401 signs out (leads to /login as on the web); logout() sends nothing', async () => {
		const { session, refreshSession, logout } = await import('../../src/lib/state/session.svelte');
		const p = refreshSession();
		await untilReqs(port, 1);
		const req = port.reqs().find((r) => r.path === '/api/v1/auth/session');
		expect(req).toBeDefined();
		port.answer(req!.id, 401, problem('unauthenticated', 401), PROBLEM);
		await p;
		expect(session.status).toBe('out');
		const before = port.reqs().length;
		await logout();
		expect(port.reqs().filter((r) => r.path.startsWith('/api/v1/auth/logout'))).toHaveLength(0);
		expect(port.reqs().length).toBe(before);
	});
});
