// Struggle-screen strings. Same contract as ../../lib/i18n.ts (English only).
const en = {
	'st.title': 'Struggle',
	'st.loading': 'Loading…',
	'st.hotWords': 'Hot words',
	'st.hotWordsNone': 'No hot words right now.',
	'st.hotKanji': 'Hot kanji',
	'st.hotKanjiNone': 'No hot kanji right now.',
	'st.score': 'score {n}',
	'st.failures': '{n} failures in 14 days',
	'st.kanjiWords': '{n} words',
	'st.leeches': 'Leeches',
	'st.confusables': 'Confusable pairs',
	'st.drillHot': 'Drill hot words',
	'st.drillLeeches': 'Drill leeches',
	'st.drillConfusables': 'Drill confusables',
	'st.drillNote': 'Drills do not change scheduling.',
	'st.back': 'Back to struggle',
	'st.notFound': 'This word is not hot any more.',
	'st.extraDrill': 'Extra drill',
	'st.extraDrillNote': 'Starts a drill of all hot words (there is no single-word drill source).',
	'st.sentences': 'Sentences from your media',
	'st.sentencesNone': 'No sentences found.',
	'st.makeCard': 'Make sentence card',
	'st.cardCreated': 'Sentence card created.',
	'st.duplicate': 'A note for this already exists.',
	'st.mnemonic': 'Mnemonic',
	'st.mnemonicSave': 'Save mnemonic',
	'st.mnemonicSaved': 'Mnemonic saved.',
	'st.partners': 'Confusable partners',
	'st.partnersNone': 'No confusable partners.',
	'st.leechesNone': 'No leeches.',
	'st.lapses': '{n} lapses',
	'st.suspended': 'Suspended',
	'st.more': 'Load more',
	'st.confusablesNone': 'No confusable pairs.',
	'st.filter': 'Show',
	'st.status.suggested': 'Suggested',
	'st.status.confirmed': 'Confirmed',
	'st.status.rejected': 'Rejected',
	'st.status.other': 'Other',
	'st.reason.homophone': 'Homophones',
	'st.reason.look_alike': 'Look alike',
	'st.reason.meaning': 'Similar meaning',
	'st.reason.other': 'Other',
	'st.confirm': 'Confirm',
	'st.reject': 'Reject',
	'st.pairUpdated': 'Pair updated.'
} as const;

export type StruggleMessageKey = keyof typeof en;

export function tg(key: StruggleMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}

/** Message for a dynamic enum value, falling back to the `other` text. */
export function tgEnum(prefix: 'st.status' | 'st.reason', value: string): string {
	const key = `${prefix}.${value}`;
	return key in en ? tg(key as StruggleMessageKey) : tg(`${prefix}.other`);
}
