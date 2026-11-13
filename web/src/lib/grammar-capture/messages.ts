const en = {
	'gc.open': 'Saw something weird',
	'gc.title': 'Grammar from a sentence',
	'gc.select': 'Highlight the grammar in the sentence, then choose what to do.',
	'gc.span': 'Selected: {span}',
	'gc.noSpan': 'No span selected yet.',
	'gc.mode.new': 'New grammar point',
	'gc.mode.existing': 'Attach to existing point',
	'gc.field.title': 'Title',
	'gc.field.pattern': 'Pattern',
	'gc.field.rule': 'Draft detection rule',
	'gc.search': 'Search grammar by title or pattern',
	'gc.noResults': 'No grammar points match.',
	'gc.test': 'Test rule',
	'gc.testing': 'Testing…',
	'gc.testResult': '{count} matching sentences (scanned {scanned}).',
	'gc.testTruncated': 'Stopped early; more may exist.',
	'gc.ruleError': 'Rule error: {detail}',
	'gc.save': 'Save',
	'gc.saving': 'Saving…',
	'gc.cancel': 'Close',
	'gc.saved': 'Grammar example saved.',
	'gc.needTitle': 'Enter a title.',
	'gc.needPoint': 'Choose a grammar point.',
	'gc.needSpan': 'Select a span first.'
} as const;

export type CaptureMessageKey = keyof typeof en;

export function tg(key: CaptureMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
