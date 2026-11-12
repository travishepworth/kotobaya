// Deck-screen strings. Same contract as ../i18n.ts (English only); own file like ../settings/messages.ts.
const en = {
	'decks.title': 'Decks',
	'decks.loading': 'Loading…',
	'decks.none': 'No decks yet.',
	'decks.new': 'New deck',
	'decks.back': 'Back to decks',
	'decks.items': '{n} items',
	'decks.priority': 'Priority {n}',
	'decks.disabled': 'Disabled',
	'decks.type.user': 'Your deck',
	'decks.type.smart': 'Smart deck',
	'decks.type.list': 'List deck',
	'decks.type.media': 'Media deck',
	'decks.type.other': 'Deck',
	'decks.readOnly': 'This deck is read-only here. Its contents are managed by the server.',
	'decks.name': 'Name',
	'decks.type': 'Type',
	'decks.query': 'Search query',
	'decks.queryHelp': 'Cards matching this query form the deck. Same syntax as the card browser.',
	'decks.queryOk': 'Query is valid.',
	'decks.queryInvalid': 'Query is not valid.',
	'decks.queryServerRejected': 'The server rejected the query: {detail}',
	'decks.suggestions': 'Suggestions',
	'decks.listKey': 'Word list',
	'decks.listChoose': 'Choose a list…',
	'decks.listItems': '{title} ({n} words)',
	'decks.enabled': 'Include in study queues',
	'decks.priorityLabel': 'Priority (higher is introduced first)',
	'decks.newPerDay': 'New cards per day (blank = use the global limit)',
	'decks.reviewsPerDay': 'Reviews per day (blank = use the global limit)',
	'decks.invalidNumber': 'Enter a whole number for “{field}”.',
	'decks.save': 'Save',
	'decks.create': 'Create deck',
	'decks.saved': 'Saved.',
	'decks.created': 'Deck created.',
	'decks.delete': 'Delete deck',
	'decks.confirmDelete': 'Delete deck “{name}”? Its cards are kept; only the grouping is removed.',
	'decks.deleted': 'Deck deleted.',
	'decks.notFound': 'This deck does not exist or was deleted.',
	'decks.coverage': 'Coverage',
	'decks.coverageNA': 'Coverage is not available yet.',
	'decks.itemsTitle': 'Items',
	'decks.itemsNone': 'No items.',
	'decks.itemNote': 'Note {id}',
	'decks.itemEntry': 'Dictionary entry {seq}',
	'decks.itemRemove': 'Remove',
	'decks.itemsMore': 'Load more',
	'decks.itemsAddHeading': 'Add items',
	'decks.itemsAddEntry': 'Dictionary entry number',
	'decks.itemsAddNote': 'Note id',
	'decks.itemsAdd': 'Add',
	'decks.itemsAdded': 'Added.',
	'decks.itemsSmart': 'Items of a smart deck come from its query and cannot be edited.',
	'decks.itemsList': 'Items of a list deck come from the list.',
	'decks.itemsMedia': 'Media deck items are managed by the media import.',
	'search.error.unknown_key': 'Unknown search key.',
	'search.error.bad_value': 'This value is not valid for its key.',
	'search.error.missing_value': 'A value is missing.',
	'search.error.unbalanced_paren': 'Unbalanced parenthesis.',
	'search.error.bad_quote': 'Unclosed or empty quotation marks.',
	'search.error.dangling_operator': 'An operator is missing an operand.',
	'search.error.other': 'Invalid query.',
	'decks.itemsListZero': 'Word counts for built-in lists are not reported yet.',
} as const;

export type DeckMessageKey = keyof typeof en;

/** Message for a core search error key (`search.error.<code>`); unknown codes get a generic text. */
export function searchErrorText(messageKey: string): string {
	return messageKey in en ? td(messageKey as DeckMessageKey) : td('search.error.other');
}

export function td(key: DeckMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
