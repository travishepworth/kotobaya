import { describe, expect, it } from 'vitest';
import { runBack, wouldHandleBack, type BackEnv } from '../../src/lib/api/shell/nav';
import { ReviewBackPolicy } from '../../src/lib/api/shell/reviewBackPolicy';

function env(over: Partial<BackEnv> & { dialogs?: number } = {}) {
	const calls = { close: 0, undo: 0, back: 0, home: 0 };
	let dialogs = over.dialogs ?? 0;
	const policy = new ReviewBackPolicy(3_000, () => 0);
	const e: BackEnv = {
		overlays: {
			count: () => dialogs,
			closeTop: () => {
				if (dialogs === 0) return false;
				dialogs--;
				calls.close++;
				return true;
			}
		},
		review: over.review ?? null,
		path: over.path ?? '/',
		canGoBack: over.canGoBack ?? (() => false),
		historyBack: () => void calls.back++,
		goHome: () => void calls.home++
	};
	return { e, calls, policy };
}

describe('back order (SHELL.md 6.2)', () => {
	it('an open dialog closes first, even on review', () => {
		const { e, calls } = env({ dialogs: 1, path: '/review', review: { canUndo: () => true, undo: () => true }, canGoBack: () => true });
		expect(runBack(e)).toBe(true);
		expect(calls).toEqual({ close: 1, undo: 0, back: 0, home: 0 });
	});

	it('review with an undoable answer undoes once, then falls through', () => {
		const { e, calls, policy } = env({ path: '/review', canGoBack: () => true });
		let answers = 1;
		e.review = {
			canUndo: () => policy.wouldUndo(true, answers > 0),
			undo: () => {
				if (!policy.shouldUndo(true, answers > 0)) return false;
				calls.undo++;
				answers--;
				return true;
			}
		};
		expect(runBack(e)).toBe(true);
		expect(calls.undo).toBe(1);
		expect(calls.back).toBe(0);
		expect(runBack(e)).toBe(true); // nothing left to undo: history.back
		expect(calls.undo).toBe(1);
		expect(calls.back).toBe(1);
	});

	it('a nested route goes history.back', () => {
		const { e, calls } = env({ path: '/cards/abc', canGoBack: () => true });
		expect(runBack(e)).toBe(true);
		expect(calls.back).toBe(1);
	});

	it('a non-Home tab without history goes Home', () => {
		const { e, calls } = env({ path: '/stats' });
		expect(runBack(e)).toBe(true);
		expect(calls.home).toBe(1);
	});

	it('Home is not handled', () => {
		const { e, calls } = env({ path: '/' });
		expect(wouldHandleBack(e)).toBe(false);
		expect(runBack(e)).toBe(false);
		expect(calls).toEqual({ close: 0, undo: 0, back: 0, home: 0 });
	});

	it('wouldHandleBack agrees with the steps', () => {
		expect(wouldHandleBack(env({ dialogs: 1 }).e)).toBe(true);
		expect(wouldHandleBack(env({ path: '/stats' }).e)).toBe(true);
		expect(wouldHandleBack(env({ canGoBack: () => true }).e)).toBe(true);
		expect(wouldHandleBack(env({ path: '/login' }).e)).toBe(false);
	});
});
