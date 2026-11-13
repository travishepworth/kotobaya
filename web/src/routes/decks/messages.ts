// Deck screen strings added by the rebuild; the rest come from lib/decks/messages.
const en = {
	'dk.lead': 'Decks group what you study. Smart decks follow a search; lists and media decks fill themselves.',
	'dk.filter': 'Filter decks',
	'dk.group.user': 'Your decks',
	'dk.group.smart': 'Smart decks',
	'dk.group.list': 'Word lists',
	'dk.group.media': 'From your reading',
	'dk.noMatch': 'No deck names match “{q}”.',
	'dk.emptyTitle': 'No decks yet',
	'dk.emptyBody': 'Create a deck for the words you want to learn, or import your history from jpdb.',
	'dk.import': 'Import from jpdb',
	'dk.priority': 'Priority {n}',
	'dk.off': 'Paused',
	'dk.itemsCount': '{n} items',
	'dk.basics': 'Deck',
	'dk.options': 'Options',
	'dk.limits': 'Daily limits',
	'dk.limitsHint': 'Leave blank to use the global limits from Settings › Study.',
	'dk.newPerDay': 'New cards per day',
	'dk.reviewsPerDay': 'Reviews per day',
	'dk.priorityLabel': 'Priority',
	'dk.priorityHint': 'Higher priority decks introduce their new cards first.',
	'dk.enabled': 'Include in study',
	'dk.enabledHint': 'Paused decks keep their cards but leave the daily queue.',
	'dk.items': 'Items',
	'dk.addEntry': 'Dictionary entry number',
	'dk.addNote': 'or note id',
	'dk.addItems': 'Add an item',
	'dk.danger': 'Delete deck',
	'dk.dangerBody': 'The cards stay in your collection; only this grouping is removed.',
	'dk.deleteTitle': 'Delete “{name}”?',
	'dk.notFoundTitle': 'Deck not found',
	'dk.typeLabel': 'Kind of deck',
	'dk.type.user.hint': 'You choose the words.',
	'dk.type.smart.hint': 'Every card matching a search.',
	'dk.type.list.hint': 'A ready-made word list.',
	'dk.itemsEmpty': 'Nothing in this deck yet.'
} as const;

export type DeckViewKey = keyof typeof en;

export function dk(key: DeckViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
