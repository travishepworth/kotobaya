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
	'review.suspend': 'Suspend',
	'review.suspended': 'Card suspended.',
	'review.neverForget': 'Never forget',
	'review.neverForgetSet': 'Word marked never-forget.',
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
	'review.interval.years': '{n} y'
} as const;

export type ReviewMessageKey = keyof typeof en;

export function tr(key: ReviewMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
