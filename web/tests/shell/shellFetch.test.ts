// SHELL-W1: the SPA side of the bridge (contracts/api/shell/SHELL.md section 2).
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parseNativeMessage, validateWebMessage } from '../../src/lib/api/shell/protocol';
import type { ReqMessage } from '../../src/lib/api/shell/protocol';
import { ShellTransport } from '../../src/lib/api/shell/transport';
import type { ShellPort } from '../../src/lib/api/shell/transport';

const EXAMPLES = decodeURIComponent(new URL('../../../contracts/api/shell/examples/', import.meta.url).pathname);

function examples(dir: string): { name: string; text: string }[] {
	return readdirSync(join(EXAMPLES, dir))
		.filter((f) => f.endsWith('.json'))
		.sort()
		.map((name) => ({ name, text: readFileSync(join(EXAMPLES, dir, name), 'utf8') }));
}

/** A fake `window.KotobayaShell`: records what the SPA posts, lets the test answer. */
class FakePort implements ShellPort {
	onmessage: ((ev: { data: unknown }) => void) | null = null;
	sent: Record<string, unknown>[] = [];
	postMessage(text: string): void {
		this.sent.push(JSON.parse(text) as Record<string, unknown>);
	}
	reqs(): ReqMessage[] {
		return this.sent.filter((m) => m.t === 'req') as unknown as ReqMessage[];
	}
	deliver(msg: unknown): void {
		this.onmessage?.({ data: typeof msg === 'string' ? msg : JSON.stringify(msg) });
	}
	answer(id: string, status: number, body: string | null, headers: Record<string, string> = {}): void {
		this.deliver({ v: 1, t: 'res', id, status, headers, body });
	}
}

let seq = 0;
const newId = (): string => `00000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`;

let port: FakePort;
let transport: ShellTransport;

beforeEach(() => {
	port = new FakePort();
	transport = new ShellTransport(port, { newId });
});

afterEach(() => {
	vi.useRealTimers();
});

describe('shellFetch round trip', () => {
	it('encodes a req and resolves with status, headers and body of the matching res', async () => {
		const p = transport.fetch('/api/v1/review/queue?limit=20&keep_going=true', {
			method: 'GET',
			headers: { Accept: 'application/json' }
		});
		const [req] = port.reqs();
		expect(req).toMatchObject({
			v: 1,
			t: 'req',
			method: 'GET',
			path: '/api/v1/review/queue',
			query: 'limit=20&keep_going=true',
			headers: { accept: 'application/json' },
			body: null
		});
		expect(validateWebMessage(req)).toBe(true);
		port.answer(req.id, 200, '{"items":[]}', { 'content-type': 'application/json', 'kotobaya-local': '1' });
		const res = await p;
		expect(res).toBeInstanceOf(Response);
		expect(res.status).toBe(200);
		expect(res.ok).toBe(true);
		expect(res.headers.get('Content-Type')).toBe('application/json');
		expect(res.headers.get('kotobaya-local')).toBe('1');
		expect(await res.json()).toEqual({ items: [] });
	});

	it('sends a string body with its content type and no credential headers', async () => {
		const p = transport.fetch('/api/v1/review/answer', {
			method: 'post',
			headers: { 'Content-Type': 'application/json', 'X-Kotobaya-Csrf': '1', Authorization: 'Bearer x' },
			body: '{"rating":3}'
		});
		const [req] = port.reqs();
		expect(req.method).toBe('POST');
		expect(req.query).toBe('');
		expect(req.headers).toEqual({ 'content-type': 'application/json' });
		expect(req.body).toBe('{"rating":3}');
		port.answer(req.id, 422, '{"code":"validation_failed","status":422}', {
			'content-type': 'application/problem+json'
		});
		const res = await p;
		expect(res.status).toBe(422);
		expect(res.ok).toBe(false);
	});

	it('a 204 res becomes an empty Response', async () => {
		const p = transport.fetch('/api/v1/decks/01931f40-2000-7000-8000-000000000d01/items/e%3A1358280', {
			method: 'DELETE'
		});
		port.answer(port.reqs()[0].id, 204, null, { 'kotobaya-local': '1' });
		const res = await p;
		expect(res.status).toBe(204);
		expect(await res.text()).toBe('');
	});

	it('accepts an absolute URL and strips origin and fragment', async () => {
		void transport.fetch('https://appassets.androidplatform.net/shell/v1/info#x');
		expect(port.reqs()[0]).toMatchObject({ path: '/shell/v1/info', query: '' });
	});

	it('uses a fresh id per request', () => {
		void transport.fetch('/api/v1/meta');
		void transport.fetch('/api/v1/meta');
		const [a, b] = port.reqs();
		expect(a.id).not.toBe(b.id);
	});
});

describe('concurrency and ordering', () => {
	it('concurrent requests answered out of order resolve to their own response', async () => {
		const urls = ['/api/v1/notes', '/api/v1/cards', '/api/v1/decks'];
		const ps = urls.map((u) => transport.fetch(u));
		const reqs = port.reqs();
		// answer in reverse order
		for (const r of [...reqs].reverse()) port.answer(r.id, 200, JSON.stringify({ path: r.path }));
		const bodies = await Promise.all(ps.map(async (p) => ((await p).json() as Promise<{ path: string }>)));
		expect(bodies.map((b) => b.path)).toEqual(urls);
	});

	it('a duplicate res for an answered id is ignored', async () => {
		const p1 = transport.fetch('/api/v1/meta');
		const id1 = port.reqs()[0].id;
		port.answer(id1, 200, '"first"');
		port.answer(id1, 500, '"second"');
		expect(await (await p1).json()).toBe('first');
		// a later request is not resolved by the stale duplicate
		const p2 = transport.fetch('/api/v1/meta');
		port.answer(id1, 200, '"stale"');
		port.answer(port.reqs()[1].id, 200, '"own"');
		expect(await (await p2).json()).toBe('own');
	});

	it('a res for an unknown id is ignored', async () => {
		const p = transport.fetch('/api/v1/meta');
		port.answer('11111111-1111-4111-8111-111111111111', 200, '"other"');
		port.answer(port.reqs()[0].id, 200, '"mine"');
		expect(await (await p).json()).toBe('mine');
	});

	it('invalid and foreign messages are dropped without breaking pending requests', async () => {
		const p = transport.fetch('/api/v1/meta');
		const id = port.reqs()[0].id;
		port.deliver('not json');
		port.deliver({ v: 2, t: 'res', id, status: 200, headers: {}, body: '"v2"' });
		port.deliver({ v: 1, t: 'res', id, status: '200', headers: {}, body: '"bad"' });
		port.deliver({ v: 1, t: 'req', id, method: 'GET', path: '/api/v1/meta', query: '', headers: {}, body: null });
		port.deliver({ v: 1, t: 'clipboard.write' });
		port.answer(id, 200, '"good"');
		expect(await (await p).json()).toBe('good');
	});
});

describe('timeout and abort', () => {
	it('rejects with TypeError after 30 s and forgets the id', async () => {
		vi.useFakeTimers();
		const p = transport.fetch('/api/v1/meta');
		const id = port.reqs()[0].id;
		const caught = p.catch((e: unknown) => e);
		vi.advanceTimersByTime(29_999);
		expect(transport.pendingCount).toBe(1);
		vi.advanceTimersByTime(1);
		expect(await caught).toBeInstanceOf(TypeError);
		expect(transport.pendingCount).toBe(0);
		port.answer(id, 200, '"late"'); // ignored, no throw
	});

	it('rejects on abort like fetch (AbortError) and ignores the late res', async () => {
		const ctl = new AbortController();
		const p = transport.fetch('/api/v1/meta', { signal: ctl.signal });
		const id = port.reqs()[0].id;
		ctl.abort();
		await expect(p).rejects.toMatchObject({ name: 'AbortError' });
		expect(transport.pendingCount).toBe(0);
		port.answer(id, 200, '"late"');
	});

	it('an already aborted signal rejects without sending', async () => {
		const ctl = new AbortController();
		ctl.abort();
		await expect(transport.fetch('/api/v1/meta', { signal: ctl.signal })).rejects.toMatchObject({
			name: 'AbortError'
		});
		expect(port.reqs()).toHaveLength(0);
	});
});

describe('requests the bridge cannot carry', () => {
	it('a non-string body is rejected before sending', async () => {
		const form = new FormData();
		form.append('a', 'b');
		await expect(transport.fetch('/api/v1/media/import', { method: 'POST', body: form })).rejects.toBeInstanceOf(
			TypeError
		);
		await expect(
			transport.fetch('/api/v1/blobs/x', { method: 'PUT', body: new Uint8Array([1, 2]) })
		).rejects.toBeInstanceOf(TypeError);
		expect(port.reqs()).toHaveLength(0);
	});

	it('a body on GET, a path outside the API or a dot segment is rejected before sending', async () => {
		await expect(transport.fetch('/api/v1/meta', { method: 'GET', body: '{}' })).rejects.toBeInstanceOf(TypeError);
		await expect(transport.fetch('/assets/app.js')).rejects.toBeInstanceOf(TypeError);
		await expect(transport.fetch('/api/v1/notes/..')).rejects.toBeInstanceOf(TypeError);
		await expect(transport.fetch('/api/v1/meta', { method: 'HEAD' })).rejects.toBeInstanceOf(TypeError);
		expect(port.reqs()).toHaveLength(0);
	});
});

describe('non-res messages', () => {
	it('are passed to subscribers; unsubscribe stops delivery', () => {
		const seen: string[] = [];
		const off = transport.subscribe((m) => seen.push(m.t));
		port.deliver({ v: 1, t: 'navigate', path: '/review' });
		port.deliver({ v: 1, t: 'event', event: { type: 'sync.changed', at: '2026-11-20T10:00:05.120Z', data: {} } });
		off();
		port.deliver({ v: 1, t: 'navigate', path: '/review' });
		expect(seen).toEqual(['navigate', 'event']);
	});

	it('send() refuses a message that is invalid for its direction', () => {
		expect(() => transport.send({ v: 1, t: 'haptic', kind: 'buzz' } as never)).toThrow(TypeError);
		transport.send({ v: 1, t: 'ready', web_version: '0.1.26', min_local_api: 1 });
		expect(port.sent).toEqual([{ v: 1, t: 'ready', web_version: '0.1.26', min_local_api: 1 }]);
	});
});

describe('contract bridge examples', () => {
	const valid = examples('bridge');
	const invalid = examples('bridge-invalid');

	it('the example directories are not empty', () => {
		expect(valid.length).toBeGreaterThan(10);
		expect(invalid.length).toBeGreaterThan(10);
	});

	for (const { name, text } of valid) {
		it(`valid: ${name}`, () => {
			if (name.startsWith('native.')) {
				expect(parseNativeMessage(text).kind).toBe('ok');
			} else if (name.startsWith('web.')) {
				expect(validateWebMessage(JSON.parse(text))).toBe(true);
			} else {
				// any.*: a valid message of a type this version does not know: ignored, not invalid
				expect(parseNativeMessage(text).kind).toBe('unknown');
			}
		});
	}

	for (const { name, text } of invalid) {
		it(`invalid: ${name}`, () => {
			if (name.startsWith('native.') || name.startsWith('any.')) {
				expect(parseNativeMessage(text).kind).toBe('invalid');
			}
			if (name.startsWith('web.') || name.startsWith('any.')) {
				expect(validateWebMessage(JSON.parse(text))).toBe(false);
			}
		});
	}

	it('the worked example of SHELL.md 2.2 round-trips', async () => {
		const p = transport.fetch('/api/v1/review/answer', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: '{"rating":3}'
		});
		const ok = JSON.parse(readFileSync(join(EXAMPLES, 'bridge', 'native.res.ok.json'), 'utf8')) as Record<
			string,
			unknown
		>;
		port.deliver({ ...ok, id: port.reqs()[0].id });
		const res = await p;
		expect(res.status).toBe(200);
		expect(((await res.json()) as { more_available: boolean }).more_available).toBe(false);
	});
});
