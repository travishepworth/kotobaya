// Review-screen strings. Same contract as ../i18n.ts (English only).
const en = {
	'review.title': 'Review',
	'review.loading': 'Loading…',
	'review.done': 'All done for today.',
	'review.doneHint': 'Come back when more cards are due.',
	'review.reload': 'Check again',
	'review.counts': 'New {new} · Learning {learning} · Review {review}',
	'review.flip': 'Show answer',
	'review.undo': 'Undo',
	'review.nothingToUndo': 'Nothing to undo.',
	'review.undone': 'Last answer undone.',
	'review.bury': 'Bury',
	'review.buried': 'Card buried until tomorrow.',
	'review.answerDropped': 'That card can no longer be reviewed (deleted or suspended elsewhere). Moved on.',
	'review.suspend': 'Suspend',
	'review.suspended': 'Card suspended.',
	'review.neverForget': 'Never forget',
	'review.neverForgetSet': 'Word marked never-forget.',
	'review.blacklistSet': 'Word blacklisted.',
	'review.noEntry': 'This card has no dictionary entry.',
	'review.edit': 'Edit note',
	'review.furigana': 'Furigana',
	'review.keyHelp':
		'Keys: space flip · {grades} grade · u undo · e edit · b bury · s suspend · n never forget · f furigana',
	'review.grade.again': 'Again',
	'review.grade.hard': 'Hard',
	'review.grade.good': 'Good',
	'review.grade.easy': 'Easy',
	'review.grade.nothing': 'Nothing',
	'review.grade.something': 'Something',
	'review.grade.okay': 'Okay',
	'review.gradeGroup': 'Grade this card',
	'review.type.drill': 'Drill (schedule unchanged)',
	'review.type.cram': 'Cram (schedule unchanged)',
	'review.drillUnavailable': 'This drill source is not available yet.',
	'review.missingNoteType': 'The note type of this card is missing.',
	'review.noExampleSentence':
		'No example sentence for this grammar point yet. Mine one from your media, or mark a span in the reader.',
	'review.renderFailed': 'This card could not be rendered: {detail}',
	'review.edit.title': 'Edit note',
	'review.edit.mnemonic': 'Mnemonic',
	'review.edit.save': 'Save',
	'review.edit.cancel': 'Cancel',
	'review.edit.saving': 'Saving…',
	'review.edit.saved': 'Note saved.',
	'review.interval.now': '<1 min',
	'review.interval.minutes': '{n} min',
	'review.interval.hours': '{n} h',
	'review.interval.days': '{n} d',
	'review.interval.months': '{n} mo',
	'review.interval.years': '{n} y',
	// ADR-037 review loop: Home hero and the review end screens (numbers from `loop` only).
	'loop.readyNow': 'Ready now',
	'loop.card': 'card',
	'loop.cards': 'cards',
	'loop.ready.learning': '{n} learning',
	'loop.ready.review': '{n} to review',
	'loop.later': '{n} learning later today · next in {min} min',
	'loop.laterAt': '{n} learning later today · next at {time}',
	'loop.newBar': 'New cards',
	'loop.newOf': '{today}/{goal}',
	'loop.newToday': '{n} new today',
	'loop.backlog': 'Backlog: {n} reviews due · {limit}/day limit',
	'loop.backlogMore': '{n} more reviews in backlog',
	'loop.quota.capped': '{rToday}/{rLimit} reviews · {nToday}/{nGoal} new',
	'loop.quota.unlimited': '{nToday}/{nGoal} new',
	'loop.quota.goalReached': '{nToday}/{nGoal} new · New-card goal reached',
	'loop.part.learning': '{n} learning',
	'loop.part.later': '{n} later',
	'loop.part.review': '{n} to review',
	'loop.part.new': '{n} new',
	'loop.start': 'Start review',
	'loop.keepGoing': 'Keep going',
	'loop.goalReached.title': 'New-card goal reached',
	'loop.goalReached.body': 'Reviews are done for now. Keep going adds more new cards.',
	'loop.doneForToday.title': 'Done for today',
	'loop.doneForToday.body': 'Today’s limits are reached. Keep going clears the backlog, then adds new cards.',
	'loop.waiting.title': 'Next card in {time}',
	'loop.waiting.next': 'Next card in',
	'loop.waiting.homeTitle': 'Waiting for learning cards',
	'loop.waiting.body': 'Your learning cards come back when their step is over. The next one appears by itself.',
	'loop.empty.title': 'All done for today',
	'loop.empty.body': 'Nothing else is due today.',
	'loop.empty.keepGoing': 'Nothing more is available right now.',
	'loop.legacy': 'The server is older than this app; some numbers are not shown.'
} as const;

export type ReviewMessageKey = keyof typeof en;

export function tr(key: ReviewMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}

/**
 * ADR-037 done-for-today quota line (Home and the review end screen), from `loop` only. Capped mode shows the
 * reviews against the limit too; unlimited shows the new cards only; goal reached appends its title.
 */
export function quotaLine(
	loop: { reviews: { today: number; limit: number | null }; _new: { today: number; goal: number } },
	goalReached: boolean
): string {
	const n = { nToday: loop._new.today, nGoal: loop._new.goal };
	if (goalReached) return tr('loop.quota.goalReached', n);
	if (loop.reviews.limit === null) return tr('loop.quota.unlimited', n);
	return tr('loop.quota.capped', { ...n, rToday: loop.reviews.today, rLimit: loop.reviews.limit });
}
