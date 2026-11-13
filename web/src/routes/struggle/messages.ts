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
	'st.pairUpdated': 'Pair updated.',
	'st.lead': 'The words that keep slipping away. Drills never change your schedule.',
	'st.calmTitle': 'Nothing is giving you trouble',
	'st.calmBody': 'When a word keeps failing, it shows up here with a quick way to drill it.',
	'st.leechesLead': 'Cards you have forgotten many times. Rewrite the mnemonic, add a sentence, or suspend them.',
	'st.confusablesLead': 'Words you mix up with each other. Confirm real pairs so drills can test them side by side.',
	'st.openLeeches': 'All leeches',
	'st.openConfusables': 'All pairs',
	'st.hotWordsLead': 'Most failures in the last 14 days',
	'st.hotKanjiLead': 'Kanji shared by the words you miss',
	'st.drill': 'Drill',
	'st.vs': 'or',
	'st.wordStats': 'Recent trouble',
	'st.fails14': 'Failures, 14 days',
	'st.scoreLabel': 'Trouble score',
	'st.cardsLabel': 'Cards',
	'st.openDictionary': 'Open in dictionary',
	'st.mnemonicHint': 'A vivid story beats repetition. It shows on the back of every card of this word.',
	'st.notFoundTitle': 'Not struggling any more'
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
