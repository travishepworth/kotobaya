import { describe, expect, it } from 'vitest';
import { ReviewBackPolicy } from '../../src/lib/api/shell/reviewBackPolicy';

// The cases of Android's ReviewBackPolicyTest: ADR-030, back undoes once.
describe('ReviewBackPolicy', () => {
	function make() {
		let t = 1_000;
		const p = new ReviewBackPolicy(3_000, () => t);
		return { p, advance: (ms: number) => (t += ms) };
	}

	it('undoes once, and a second back within 3 s leaves', () => {
		const { p, advance } = make();
		expect(p.shouldUndo(true, true)).toBe(true);
		advance(2_999);
		expect(p.shouldUndo(true, true)).toBe(false);
	});

	it('undoes again after the window', () => {
		const { p, advance } = make();
		expect(p.shouldUndo(true, true)).toBe(true);
		advance(3_001);
		expect(p.shouldUndo(true, true)).toBe(true);
	});

	it('leaves when there is nothing to undo, or no card showing', () => {
		const { p } = make();
		expect(p.shouldUndo(true, false)).toBe(false);
		expect(p.shouldUndo(false, true)).toBe(false);
		// a refused back does not start the window
		expect(p.shouldUndo(true, true)).toBe(true);
	});

	it('a new answer re-arms', () => {
		const { p, advance } = make();
		expect(p.shouldUndo(true, true)).toBe(true);
		advance(500);
		p.answered();
		expect(p.shouldUndo(true, true)).toBe(true);
	});

	it('wouldUndo has no side effects', () => {
		const { p } = make();
		expect(p.wouldUndo(true, true)).toBe(true);
		expect(p.wouldUndo(true, true)).toBe(true);
		expect(p.shouldUndo(true, true)).toBe(true);
		expect(p.wouldUndo(true, true)).toBe(false);
	});
});
