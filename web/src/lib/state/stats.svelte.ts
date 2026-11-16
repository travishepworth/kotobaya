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
import { quietApiConfig } from '../api/client';

// RC-WEB: stats are slow (14-76 s, some 504) and each panel shows its own "couldn't load" + retry: no global toast.
const api = new StatsApi(quietApiConfig);

export type StatsPanel = 'summary' | 'heatmap' | 'retention' | 'forecast' | 'known' | 'calibration';
const ALL: readonly StatsPanel[] = ['summary', 'heatmap', 'retention', 'forecast', 'known', 'calibration'];

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

	/** Aborts the requests still in flight when the screen is left (they would hold browser connections). */
	#abort = new AbortController();

	/** Start the loads of `panels` (all by default; reads no state). */
	start(panels: readonly StatsPanel[] = ALL): void {
		for (const p of panels) this.retry(p);
	}

	/** Load one panel (again). A panel that has data keeps showing it until the new data arrives. */
	retry(panel: StatsPanel): void {
		const init: RequestInit = { signal: this.#abort.signal };
		switch (panel) {
			case 'summary':
				return load(() => api.getStatsSummary(init), (p) => (this.summaryP = p), init.signal);
			case 'heatmap':
				return load(() => api.getStatsHeatmap({}, init), (p) => (this.heatmapP = p), init.signal);
			case 'retention':
				return load(() => api.getStatsRetention({ groupBy: 'card_kind' }, init), (p) => (this.retentionP = p), init.signal);
			case 'forecast':
				return load(() => api.getStatsForecast({ days: 30 }, init), (p) => (this.forecastP = p), init.signal);
			case 'known':
				return load(() => api.getStatsKnownWords({ interval: 'week' }, init), (p) => (this.knownP = p), init.signal);
			case 'calibration':
				return load(() => api.getStatsCalibration({}, init), (p) => (this.calibrationP = p), init.signal);
		}
	}

	/** Leaving the screen: abort what is still loading; nothing is written after this. */
	dispose(): void {
		this.#abort.abort();
	}
}

function load<T>(fetch: () => Promise<T>, set: (p: Panel<T>) => void, signal?: AbortSignal | null): void {
	fetch().then(
		(data) => set({ data, failed: false }),
		() => {
			if (signal?.aborted) return; // the screen was left: not a failure
			set({ data: null, failed: true }); // shown inline by the panel, with a retry
		}
	);
}
