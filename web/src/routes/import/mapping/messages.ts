const en = {
	'map.title': 'jpdb vocabulary to review',
	'map.back': 'Back to import',
	'map.intro':
		'Vocabulary from jpdb decks that did not map to a dictionary entry. Pick the entry it means, or ignore it. Affected media are re-published in the background.',
	'map.empty': 'Nothing to review.',
	'map.col.spelling': 'Spelling',
	'map.col.reading': 'Reading',
	'map.col.occurrences': 'Occurrences',
	'map.col.media': 'Media',
	'map.col.resolve': 'Resolve',
	'map.search': 'Search dictionary',
	'map.searchButton': 'Search',
	'map.searching': 'Searching…',
	'map.noMatches': 'No matches.',
	'map.ignore': 'Ignore',
	'map.mediaCount': '{title} ({count})',
	'map.loadMore': 'Load more',
	'map.republishHeading': 'Re-publish jobs',
	'map.republish': 'Re-publish of {title}: {status}',
	'map.status.queued': 'queued',
	'map.status.running': 'running',
	'map.status.succeeded': 'succeeded',
	'map.status.failed': 'failed',
	'map.status.cancelled': 'cancelled',
	'map.status.other': 'other'
} as const;

export type MapMessageKey = keyof typeof en;

export function tm(key: MapMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
