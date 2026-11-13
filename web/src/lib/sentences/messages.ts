const en = {
	'sn.title': 'Sentences',
	'sn.filter': 'Show',
	'sn.filter.all': 'All',
	'sn.filter.i1': 'i+1',
	'sn.filter.i0': 'i+0',
	'sn.segment': 'Segment',
	'sn.allSegments': 'All segments',
	'sn.search': 'Search sentences',
	'sn.loading': 'Loading…',
	'sn.empty': 'No sentences match.',
	'sn.more': 'Load more',
	'sn.at': 'at {time}',
	'sn.play': 'Play audio',
	'sn.make': 'Make sentence card',
	'sn.form.sentence': 'Sentence',
	'sn.form.reading': 'Reading',
	'sn.form.translation': 'Translation',
	'sn.form.deck': 'Deck',
	'sn.form.defaultDeck': 'Default deck',
	'sn.form.alsoVocab': 'Also add the unknown word as vocab',
	'sn.form.create': 'Create card',
	'sn.form.cancel': 'Cancel',
	'sn.created': 'Sentence card created.',
	'sn.vocabCreated': 'Vocab card created.',
	'sn.duplicate': 'A note for this already exists.'
} as const;

export type SentenceMessageKey = keyof typeof en;

export function ts(key: SentenceMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
