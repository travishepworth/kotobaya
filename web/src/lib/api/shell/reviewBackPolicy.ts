// ADR-030 in the shell (SHELL.md 6.2 step 2): what Back does on the review screen. TypeScript port of Android's
// `ReviewBackPolicy`: after an answer of this visit, back undoes it once; a second back within the window, or back with
// nothing to undo, falls through to the next step.
export const BACK_WINDOW_MS = 3_000;

export class ReviewBackPolicy {
	private lastUndoAt: number | null = null;

	constructor(
		private readonly windowMs: number = BACK_WINDOW_MS,
		private readonly now: () => number = () => Date.now()
	) {}

	/** Would a back press undo right now? No side effects (feeds `back.state`). */
	wouldUndo(showing: boolean, canUndo: boolean): boolean {
		if (!showing || !canUndo) return false;
		const last = this.lastUndoAt;
		return last === null || this.now() - last > this.windowMs;
	}

	/** True when this back press is to undo (and is consumed); false when it should fall through. */
	shouldUndo(showing: boolean, canUndo: boolean): boolean {
		if (!this.wouldUndo(showing, canUndo)) return false;
		this.lastUndoAt = this.now();
		return true;
	}

	/** A new answer starts a new burst: the next back undoes again. */
	answered(): void {
		this.lastUndoAt = null;
	}
}
