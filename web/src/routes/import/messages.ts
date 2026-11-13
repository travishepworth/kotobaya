const en = {
	'imp.title': 'Import jpdb history',
	'imp.intro':
		'Choose the review export from jpdb (JSON). It is uploaded, then imported in the background; re-running the same file imports nothing twice.',
	'imp.file': 'jpdb export file',
	'imp.mapping': 'Review unmapped jpdb vocabulary',
	'imp.reviews': 'Import review history',
	'imp.status': 'Import known words as word statuses',
	'imp.start': 'Upload and import',
	'imp.uploading': 'Uploading…',
	'imp.progress': 'Import {status}: {percent}%',
	'imp.failed': 'The import failed: {error}',
	'imp.noError': 'no details',
	'imp.cancelled': 'The import was cancelled.',
	'imp.reset': 'Import another file',
	'imp.done': 'Import finished.',
	'imp.counters': 'Result',
	'imp.counter.other': 'Other counters',
	'imp.unmapped': 'Vocabulary that could not be mapped ({count})',
	'imp.kanjiUnmapped': 'Kanji that could not be mapped ({count})',
	'imp.none': 'None.',
	'imp.col.spelling': 'Spelling',
	'imp.col.reading': 'Reading',
	'imp.col.reviews': 'Reviews',
	'imp.col.vid': 'jpdb id',
	'imp.col.character': 'Character',
	'imp.col.kind': 'Card kind',
	'imp.steps': 'Import steps',
	'imp.step.upload': 'Upload',
	'imp.step.import': 'Import',
	'imp.step.review': 'Review',
	'imp.step.done': 'Done',
	'imp.drop': 'Choose your jpdb export',
	'imp.dropHint': 'The JSON file from jpdb › Settings › Export reviews.',
	'imp.chosen': '{name} · {size}',
	'imp.change': 'Choose another file',
	'imp.options': 'What to import',
	'imp.leave': 'You can leave this page; the import keeps running.',
	'imp.vocab': 'Vocabulary',
	'imp.kanji': 'Kanji',
	'imp.reviewsGroup': 'Reviews',
	'imp.other': 'Other',
	'imp.nothingDropped': 'Everything in the file was accounted for: nothing was dropped silently.',
	'imp.title.page': 'Import'
} as const;

export type ImportMessageKey = keyof typeof en;

export function ti(key: ImportMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
