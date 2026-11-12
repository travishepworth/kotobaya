const en = {
	'app.name': 'Kotobaya',
	'app.loading': 'Loading…',
	'nav.home': 'Home',
	'nav.review': 'Review',
	'nav.dictionary': 'Dictionary',
	'nav.notes': 'Notes',
	'nav.decks': 'Decks',
	'nav.media': 'Media',
	'nav.settings': 'Settings',
	'nav.logout': 'Log out',
	'nav.main': 'Main navigation',
	'toast.dismiss': 'Dismiss',
	'login.title': 'Log in',
	'login.password': 'Password',
	'login.totp': 'Authentication code',
	'login.submit': 'Log in',
	'login.submitting': 'Logging in…',
	'login.invalid': 'Wrong password or code.',
	'login.totpRequired': 'Enter the 6-digit authentication code.',
	'login.rateLimited': 'Too many attempts. Try again later.',
	'login.retryAfter': 'Too many attempts. Try again in {seconds} s.',
	'error.network': 'Cannot reach the server.',
	'error.generic': 'Something went wrong ({code}).',
	'error.forbidden': 'Not allowed ({code}).',
	'error.validation': 'Invalid input: {detail}',
	'error.unavailable': 'The server is temporarily unavailable.',
	'events.reconnecting': 'Live updates disconnected; reconnecting…',
	'home.welcome': 'Welcome back.'
} as const;

export type MessageKey = keyof typeof en;

/** Minimal i18n layer; English only for now. All user-visible strings go through t(). */
export function t(key: MessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) {
		for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	}
	return s;
}
