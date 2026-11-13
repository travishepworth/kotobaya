// Stats screen state (/stats): six independently loaded panels plus the chart geometry derived from them.
import { StatsApi } from '../api/generated';
import type {
	StatsCalibration,
	StatsForecast,
	StatsHeatmap,
	StatsKnownWords,
	StatsRetention,
	StatsSummary
} from '../api/generated';
import { apiConfig } from '../api/client';

const api = new StatsApi(apiConfig);

// Each panel loads on its own: one slow or failing endpoint never blanks the others.
export type Panel<T> = { data: T | null; failed: boolean };

function empty<T>(): Panel<T> {
	return { data: null, failed: false };
}

export const pct = (v: number | null): string => (v === null ? '-' : `${(v * 100).toFixed(1)}%`);
export const iso = (d: Date): string => d.toISOString().slice(0, 10);

export class StatsPage {
	summaryP = $state<Panel<StatsSummary>>(empty());
	heatmapP = $state<Panel<StatsHeatmap>>(empty());
	retentionP = $state<Panel<StatsRetention>>(empty());
	forecastP = $state<Panel<StatsForecast>>(empty());
	knownP = $state<Panel<StatsKnownWords>>(empty());
	calibrationP = $state<Panel<StatsCalibration>>(empty());

	readonly summary = $derived(this.summaryP.data);
	readonly heatmap = $derived(this.heatmapP.data);
	readonly retention = $derived(this.retentionP.data);
	readonly forecast = $derived(this.forecastP.data);
	readonly known = $derived(this.knownP.data);
	readonly calibration = $derived(this.calibrationP.data);

	// Heatmap: one cell per day, weeks as columns (Monday first).
	readonly cells = $derived.by(() => {
		const heatmap = this.heatmap;
		if (!heatmap) return [] as { x: number; y: number; date: string; n: number }[];
		const byDate = new Map(heatmap.days.map((d) => [iso(d.date), d.reviews]));
		const start = new Date(heatmap.from.getTime());
		start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7));
		const out: { x: number; y: number; date: string; n: number }[] = [];
		for (let t = start.getTime(), i = 0; t <= heatmap.to.getTime(); t += 86_400_000, i++) {
			const date = iso(new Date(t));
			out.push({ x: Math.floor(i / 7), y: i % 7, date, n: byDate.get(date) ?? 0 });
		}
		return out;
	});
	readonly heatMax = $derived(Math.max(1, ...this.cells.map((c) => c.n)));
	readonly heatCols = $derived(this.cells.length ? this.cells[this.cells.length - 1].x + 1 : 0);

	readonly forecastMax = $derived(Math.max(1, ...(this.forecast?.days.map((d) => d.due) ?? [0])));
	readonly knownMax = $derived(Math.max(1, ...(this.known?.series.map((p) => p.known) ?? [0])));
	readonly knownNow = $derived(this.known ? this.known.current.known + this.known.current.neverForget : 0);

	/** Start all panel loads (reads no state). */
	start(): void {
		load(() => api.getStatsSummary(), (p) => (this.summaryP = p));
		load(() => api.getStatsHeatmap(), (p) => (this.heatmapP = p));
		load(() => api.getStatsRetention({ groupBy: 'card_kind' }), (p) => (this.retentionP = p));
		load(() => api.getStatsForecast({ days: 30 }), (p) => (this.forecastP = p));
		load(() => api.getStatsKnownWords({ interval: 'week' }), (p) => (this.knownP = p));
		load(() => api.getStatsCalibration(), (p) => (this.calibrationP = p));
	}
}

function load<T>(fetch: () => Promise<T>, set: (p: Panel<T>) => void): void {
	fetch().then(
		(data) => set({ data, failed: false }),
		() => set({ data: null, failed: true }) // the client already toasted
	);
}
