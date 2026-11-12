const en = {
	'imp.title': 'Import jpdb history',
	'imp.intro':
		'Choose the review export from jpdb (JSON). It is uploaded, then imported in the background; re-running the same file imports nothing twice.',
	'imp.file': 'jpdb export file',
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
	'imp.col.kind': 'Card kind'
} as const;

export type ImportMessageKey = keyof typeof en;

export function ti(key: ImportMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
