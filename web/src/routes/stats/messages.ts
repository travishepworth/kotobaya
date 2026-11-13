// Stats-screen strings. Same contract as ../../lib/i18n.ts (English only).
const en = {
	'stats.title': 'Stats',
	'stats.loading': 'Loading…',
	'stats.summary': 'Today',
	'stats.due': 'Due: {new} new, {learning} learning, {review} review',
	'stats.done': 'Done: {n} reviews, {m} new cards, {min} min',
	'stats.retentionToday': 'Retention today: {pct}',
	'stats.streak': 'Streak: {n} days',
	'stats.totals': '{notes} notes, {cards} cards, {suspended} suspended, {leeches} leeches',
	'stats.heatmap': 'Reviews per day (last year)',
	'stats.heatmapDay': '{date}: {n} reviews',
	'stats.retention': 'Retention by card kind',
	'stats.retentionRow': '{pct} of {n}',
	'stats.retentionYoungMature': 'young {young}, mature {mature}',
	'stats.overall': 'Overall',
	'stats.forecast': 'Due forecast (next 30 days)',
	'stats.forecastBar': '{date}: {n} due',
	'stats.knownWords': 'Known words',
	'stats.knownNow': '{n} known now (including never-forget)',
	'stats.knownPoint': '{date}: {n} known',
	'stats.calibration': 'FSRS calibration',
	'stats.calibrationHelp': 'Predicted recall (x) against actual pass rate (y). The diagonal is perfect calibration.',
	'stats.calibrationPoint': 'Predicted {p}, actual {a}, {n} reviews',
	'stats.none': 'No data yet.',
	'stats.error': 'Could not load stats.'
} as const;

export type StatsMessageKey = keyof typeof en;

export function ts(key: StatsMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
