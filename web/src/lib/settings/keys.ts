import type { SettingsMessageKey } from './messages';

/** Well-known settings (contracts/db/SCHEMA.md section 6 plus ADR-006 and the kanji-first option). */
export type SettingDef =
	| { key: string; label: SettingsMessageKey; type: 'int'; default: number; min?: number; max?: number }
	| { key: string; label: SettingsMessageKey; type: 'bool'; default: boolean }
	| { key: string; label: SettingsMessageKey; type: 'string'; default: string }
	| { key: string; label: SettingsMessageKey; type: 'buttons'; default: 4 };

export const STUDY_SETTINGS: SettingDef[] = [
	{ key: 'scheduler.timezone', label: 'settings.timezone', type: 'string', default: 'UTC' },
	{ key: 'scheduler.day_rollover_hour', label: 'settings.dayRollover', type: 'int', default: 4, min: 0, max: 23 },
	{ key: 'scheduler.new_per_day', label: 'settings.newPerDay', type: 'int', default: 20, min: 0 },
	{ key: 'scheduler.reviews_per_day', label: 'settings.reviewsPerDay', type: 'int', default: 200, min: 0 },
	{ key: 'scheduler.new_per_session', label: 'settings.newPerSession', type: 'int', default: 10, min: 0 },
	{ key: 'scheduler.grading_buttons', label: 'settings.gradingButtons', type: 'buttons', default: 4 },
	{ key: 'scheduler.leech_threshold', label: 'settings.leechThreshold', type: 'int', default: 8, min: 1 },
	{ key: 'scheduler.bury_siblings', label: 'settings.burySiblings', type: 'bool', default: true },
	{ key: 'scheduler.kanji_before_vocab', label: 'settings.kanjiBeforeVocab', type: 'bool', default: false },
	{ key: 'scheduler.mined_priority', label: 'settings.minedPriority', type: 'int', default: 0 },
	{ key: 'audio.prefetch_days', label: 'settings.audioPrefetch', type: 'int', default: 7, min: 0 }
];

/** Parse a comma separated list of positive whole seconds; null when malformed. */
export function parseSteps(text: string): number[] | null {
	const parts = text
		.split(',')
		.map((p) => p.trim())
		.filter((p) => p !== '');
	const out: number[] = [];
	for (const p of parts) {
		if (!/^\d+$/.test(p)) return null;
		const n = Number(p);
		if (n < 1) return null;
		out.push(n);
	}
	return out;
}

/** Remaining time as whole seconds, never negative. */
export function secondsLeft(expiresAt: Date, now: Date): number {
	return Math.max(0, Math.ceil((expiresAt.getTime() - now.getTime()) / 1000));
}
