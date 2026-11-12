const en = {
	'app.name': 'Kotobaya'
} as const;

export type MessageKey = keyof typeof en;

/** Minimal i18n layer; English only for now. All user-visible strings go through t(). */
export function t(key: MessageKey): string {
	return en[key];
}
