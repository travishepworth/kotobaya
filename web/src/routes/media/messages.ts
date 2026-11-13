// Reading (media) screen strings added by the rebuild; the rest come from lib/media, lib/coverage, lib/reader,
// lib/sentences messages.
const en = {
	'mx.lead': 'What you read and watch: coverage, a reader with a dictionary at hand, and sentences to mine.',
	'mx.search': 'Search titles',
	'mx.emptyTitle': 'Your library is empty',
	'mx.emptyBody': 'Import a novel, subtitles, a web page or a jpdb deck to see how much of it you already know.',
	'mx.noMatch': 'No media match these filters.',
	'mx.actions': 'Actions for {title}',
	'mx.segments': '{n} parts',
	'mx.deleteTitle': 'Delete “{title}”?',
	'mx.library': 'Reading',
	'mx.segmentAll': 'Whole work',
	'mx.segment': 'Part',
	'mx.known': 'Known',
	'mx.learning': 'Learning',
	'mx.unknown': 'Unknown',
	'mx.unmapped': 'Not in the dictionary',
	'mx.tokens': '{n} words in total',
	'mx.uniqueTitle': 'Distinct words',
	'mx.curveTitle': 'What learning more would give you',
	'mx.curveSub': 'Known share of the text if you learned the most frequent unknown words first',
	'mx.curveWords': '+{n} words',
	'mx.unknownTitle': 'Unknown words',
	'mx.sortBy': 'Sort',
	'mx.hideTitle': 'Hide',
	'mx.readerTitle': 'Reader',
	'mx.part': 'Part',
	'mx.ruby': 'Furigana',
	'mx.legend.known': 'known',
	'mx.legend.learning': 'learning',
	'mx.legend.new': 'unknown (dotted)',
	'mx.tapHint': 'Tap a word to look it up.',
	'mx.emptyReader': 'No sentences in this part yet.',
	'mx.sentencesTitle': 'Sentences',
	'mx.filter': 'Show',
	'mx.makeCard': 'Make card',
	'mx.newTitle': 'Import media',
	'mx.source': 'Source',
	'mx.details': 'Details',
	'mx.deck': 'Study deck',
	'mx.drop': 'Choose files',
	'mx.dropHint': 'EPUB, text, subtitles (SRT/ASS) or HTML. Several files become parts.',
	'mx.chosen': '{n} files chosen'
} as const;

export type MediaViewKey = keyof typeof en;

export function mx(key: MediaViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
