import { describe, expect, it } from 'vitest';
import {
	MAX_BACKOFF_MS,
	MAX_MESSAGE,
	MAX_PENDING,
	MIN_BACKOFF_MS,
	createReporter,
	scrub,
	toWireItem
} from '../src/lib/errors/reporter';
import type { Send, SendResult, WireReport } from '../src/lib/errors/reporter';

function harness(results: SendResult[] | ((r: WireReport) => SendResult) = [{ status: 202 }]) {
	const sent: WireReport[] = [];
	const timers: { ms: number; fn: () => void }[] = [];
	let i = 0;
	const send: Send = async (r) => {
		sent.push(structuredClone(r));
		if (typeof results === 'function') return results(r);
		return results[Math.min(i++, results.length - 1)];
	};
	const reporter = createReporter({
		send,
		version: '1.2.3',
		route: () => '/review?q=secret#x',
		now: () => new Date('2026-11-14T08:00:00.000Z'),
		schedule: (fn, ms) => {
			const t = { ms, fn };
			timers.push(t);
			return () => {
				timers.splice(timers.indexOf(t), 1);
			};
		}
	});
	return { reporter, sent, timers };
}

const err = (n: number) => ({ kind: 'exception' as const, message: `boom ${n}` });

describe('toWireItem', () => {
	it('caps message and stack at code points, ending with an ellipsis', () => {
		const item = toWireItem(
			{ kind: 'exception', message: '😀'.repeat(5000), stack: 'a'.repeat(20000) },
			'1.0',
			new Date()
		);
		expect(Array.from(item.message)).toHaveLength(MAX_MESSAGE);
		expect(item.message.endsWith('…')).toBe(true);
		expect(Array.from(item.stack ?? '')).toHaveLength(16384);
	});

	it('strips query and fragment from route and path, and drops invalid optionals', () => {
		const item = toWireItem(
			{
				kind: 'api_failure',
				message: 'x',
				route: '/cards?q=食べる#top',
				httpPath: '/api/v1/cards?q=secret',
				httpMethod: 'TRACE',
				httpStatus: 9999,
				requestId: 'has space'
			},
			'1.0',
			new Date()
		);
		expect(item.route).toBe('/cards');
		expect(item.http_path).toBe('/api/v1/cards');
		expect(item.http_method).toBeUndefined();
		expect(item.http_status).toBeUndefined();
		expect(item.request_id).toBeUndefined();
	});

	it('never emits an item the server would 422', () => {
		const item = toWireItem({ kind: 'exception', message: '   ' }, 'bad\nversion', new Date());
		expect(item.message).toBe('unknown error');
		expect(item.app_version).toBe('unknown');
	});
});

describe('scrub (privacy)', () => {
	it('removes quoted user text, tokens and query strings but keeps identifiers', () => {
		expect(scrub("Cannot read properties of undefined (reading 'fields')", 2048)).toContain(
			"'fields'"
		);
		expect(scrub('bad note "食べる 猫 content" failed', 2048)).not.toContain('食べる');
		expect(scrub('auth kbp_' + 'A'.repeat(40), 2048)).not.toContain('AAAA');
		expect(scrub('GET /api/v1/cards?q=secret failed', 2048)).not.toContain('secret');
		expect(scrub('at http://x/app.js?v=1:2:3', 2048)).not.toContain('v=1');
	});
});

describe('reporter', () => {
	it('folds identical reports into one item with count and the first occurrence time', async () => {
		const { reporter, sent } = harness();
		reporter.report(err(1));
		reporter.report(err(1));
		reporter.report(err(1));
		reporter.report(err(2));
		await reporter.flush();
		expect(sent).toHaveLength(1);
		expect(sent[0].items.map((i) => [i.message, i.count, i.route])).toEqual([
			['boom 1', 3, '/review'],
			['boom 2', 1, '/review']
		]);
		expect(sent[0].items[0].client).toBe('web');
		expect(sent[0].items[0].app_version).toBe('1.2.3');
	});

	it('sends at most 20 items per request', async () => {
		const { reporter, sent } = harness();
		for (let n = 0; n < 45; n++) reporter.report(err(n));
		await reporter.flush();
		expect(sent.map((r) => r.items.length)).toEqual([20, 20, 5]);
		expect(reporter.state().pending).toBe(0);
	});

	it('keeps at most 100 pending, drops the oldest and reports dropped once on 202', async () => {
		const { reporter, sent } = harness();
		for (let n = 0; n < MAX_PENDING + 7; n++) reporter.report(err(n));
		expect(reporter.state()).toMatchObject({ pending: MAX_PENDING, dropped: 7 });
		await reporter.flush();
		expect(sent[0].dropped).toBe(7);
		expect(sent[0].items[0].message).toBe('boom 7'); // the 7 oldest are gone
		expect(sent[1].dropped).toBeUndefined();
		expect(reporter.state().dropped).toBe(0);
	});

	it('keeps dropped when the batch that carried it is not answered 202', async () => {
		const { reporter, sent } = harness([{ status: 503 }, { status: 202 }]);
		for (let n = 0; n < MAX_PENDING + 2; n++) reporter.report(err(n));
		await reporter.flush();
		expect(reporter.state().dropped).toBe(2);
		await reporter.flush();
		expect(sent[1].dropped).toBe(2);
		expect(reporter.state().dropped).toBe(0);
	});

	it('splits a batch on 413 and resends the halves', async () => {
		const { reporter, sent } = harness((r) =>
			r.items.length > 5 ? { status: 413 } : { status: 202 }
		);
		for (let n = 0; n < 12; n++) reporter.report(err(n));
		await reporter.flush();
		expect(sent.map((r) => r.items.length)).toEqual([12, 6, 3, 3, 3, 3]);
		expect(reporter.state()).toMatchObject({ pending: 0, dropped: 0 });
	});

	it('drops a single item answered 413 and counts it as dropped', async () => {
		const { reporter } = harness([{ status: 413 }]);
		reporter.report(err(1));
		await reporter.flush();
		expect(reporter.state()).toMatchObject({ pending: 0, dropped: 1 });
	});

	it('drops a batch refused with an unretryable status and adds it to dropped', async () => {
		const { reporter, sent } = harness([{ status: 422 }, { status: 202 }]);
		reporter.report(err(1));
		reporter.report(err(2));
		await reporter.flush();
		expect(reporter.state()).toMatchObject({ pending: 0, dropped: 2 });
		reporter.report(err(3));
		await reporter.flush();
		expect(sent[1].dropped).toBe(2);
	});

	it('waits Retry-After on 429 and keeps the items', async () => {
		const { reporter, sent, timers } = harness([{ status: 429, retryAfterS: 42 }, { status: 202 }]);
		reporter.report(err(1));
		await reporter.flush();
		expect(reporter.state().pending).toBe(1);
		expect(timers.at(-1)?.ms).toBe(42_000);
		await reporter.flush();
		expect(sent).toHaveLength(2);
		expect(reporter.state().pending).toBe(0);
	});

	it('backs off exponentially from 30 s up to 15 min on network failure', async () => {
		const { reporter, timers } = harness(['network']);
		reporter.report(err(1));
		const waits: number[] = [];
		for (let n = 0; n < 8; n++) {
			await reporter.flush();
			waits.push(timers.at(-1)?.ms ?? -1);
		}
		expect(waits[0]).toBe(MIN_BACKOFF_MS);
		expect(waits[1]).toBe(MIN_BACKOFF_MS * 2);
		expect(waits.at(-1)).toBe(MAX_BACKOFF_MS);
		expect(reporter.state().pending).toBe(1);
	});

	it('pauses on 401 until resume()', async () => {
		const { reporter, sent } = harness([{ status: 401 }, { status: 202 }]);
		reporter.report(err(1));
		await reporter.flush();
		expect(reporter.state()).toMatchObject({ paused: true, pending: 1 });
		await reporter.flush();
		expect(sent).toHaveLength(1);
		reporter.resume();
		await reporter.flush();
		expect(sent).toHaveLength(2);
		expect(reporter.state().pending).toBe(0);
	});

	it('never throws and never reports its own failures (no recursion)', async () => {
		let calls = 0;
		const send: Send = async () => {
			calls++;
			throw new Error('transport exploded');
		};
		const reporter = createReporter({ send, version: '1', schedule: () => () => {} });
		expect(() =>
			reporter.report({ kind: 'exception', message: undefined as unknown as string })
		).not.toThrow();
		reporter.report(err(1));
		await expect(reporter.flush()).resolves.toBeUndefined();
		// A failed send keeps exactly the one real item; no "report failed" item appears.
		expect(reporter.state().pending).toBe(1);
		expect(calls).toBe(1);
	});
});
