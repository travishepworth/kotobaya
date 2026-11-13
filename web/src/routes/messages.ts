// Today (home) screen strings (English only for now).
const en = {
	'home.title': 'Today',
	'home.dueNow': 'Due now',
	'home.cards': 'cards',
	'home.card': 'card',
	'home.new': '{n} new',
	'home.learning': '{n} learning',
	'home.review': '{n} to review',
	'home.eta': 'About {n} min',
	'home.etaShort': 'Under a minute',
	'home.start': 'Start review',
	'home.doneTitle': 'All done for today',
	'home.nextDue': 'Next reviews at {time}.',
	'home.nextDueDay': 'Next reviews {date}.',
	'home.nothingScheduled': 'Nothing else is scheduled yet.',
	'home.doneToday': 'Done today',
	'home.doneReviews': '{n} reviews',
	'home.doneRetention': '{pct} correct',
	'home.doneTime': '{n} min',
	'home.decks': 'Decks',
	'home.allDecks': 'All decks',
	'home.deckItems': '{n} items',
	'home.noDecks': 'No decks yet',
	'home.noDecksBody': 'Decks collect the words you study. Create one, or import your jpdb history.',
	'home.createDeck': 'Create deck',
	'home.activity': 'Activity',
	'home.activityCaption': 'Reviews per day, last 14 days',
	'home.streak': 'Day streak',
	'home.retention': 'Retention',
	'home.totalCards': 'Cards',
	'home.struggle': 'Struggling with',
	'home.struggleLink': 'Struggle',
	'home.struggleEmpty': 'Nothing is giving you trouble right now.',
	'home.leeches': '{n} leeches',
	'home.emptyTitle': 'Add your first words',
	'home.emptyBody': 'Look words up in the dictionary, import what you already know, or start a deck. Your reviews will appear here.',
	'home.emptySearch': 'Search the dictionary',
	'home.emptyImport': 'Import from jpdb',
	'home.errorTitle': 'Could not load today’s numbers',
	'home.errorBody': 'The server did not answer. Your reviews are safe.'
} as const;

export type HomeMessageKey = keyof typeof en;

export function th(key: HomeMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
