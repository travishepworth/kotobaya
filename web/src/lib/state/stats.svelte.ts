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
import { isUncached, revalidateOn } from './swr';

// RC-WEB: stats are slow (14-76 s, some 504) and each panel shows its own "couldn't load" + retry: no global toast.
const api = new StatsApi(quietApiConfig);

export type StatsPanel = 'summary' | 'heatmap' | 'retention' | 'forecast' | 'known' | 'calibration';
const ALL: readonly StatsPanel[] = ['summary', 'heatmap', 'retention', 'forecast', 'known', 'calibration'];

// Each panel loads on its own: one slow or failing endpoint never blanks the others.
// `data` is kept while a refetch runs or fails (stale-while-revalidate); `uncached`: `503 shell_uncached`, a
// "not downloaded yet" placeholder that resolves on `cache.updated`.
export type Panel<T> = { data: T | null; failed: boolean; uncached: boolean };

const OPERATION: Record<StatsPanel, string> = {
	summary: 'getStatsSummary',
	heatmap: 'getStatsHeatmap',
	retention: 'getStatsRetention',
	forecast: 'getStatsForecast',
	known: 'getStatsKnownWords',
	calibration: 'getStatsCalibration'
};

function empty<T>(): Panel<T> {
	return { data: null, failed: false, uncached: false };
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

	#unwatch: (() => void)[] = [];
	#inflight = new Set<StatsPanel>();

	/** Refetch `panels` on `sync.changed` and on `cache.updated` for their own operation. Returns the unsubscribe. */
	watch(panels: readonly StatsPanel[] = ALL): () => void {
		const offs = panels.map((p) => revalidateOn([OPERATION[p]], () => this.#revalidate(p)));
		const off = (): void => offs.forEach((f) => f());
		this.#unwatch.push(off);
		return off;
	}

	#revalidate(panel: StatsPanel): void {
		if (!this.#inflight.has(panel)) this.retry(panel); // stats are slow: never stack requests
	}

	/** Load one panel (again). A panel that has data keeps showing it until the new data arrives. */
	retry(panel: StatsPanel): void {
		const init: RequestInit = { signal: this.#abort.signal };
		const run = <T,>(fetch: () => Promise<T>, get: () => Panel<T>, set: (p: Panel<T>) => void): void => {
			this.#inflight.add(panel);
			load(fetch, get, set, init.signal, () => this.#inflight.delete(panel));
		};
		switch (panel) {
			case 'summary':
				return run(() => api.getStatsSummary(init), () => this.summaryP, (p) => (this.summaryP = p));
			case 'heatmap':
				return run(() => api.getStatsHeatmap({}, init), () => this.heatmapP, (p) => (this.heatmapP = p));
			case 'retention':
				return run(() => api.getStatsRetention({ groupBy: 'card_kind' }, init), () => this.retentionP, (p) => (this.retentionP = p));
			case 'forecast':
				return run(() => api.getStatsForecast({ days: 30 }, init), () => this.forecastP, (p) => (this.forecastP = p));
			case 'known':
				return run(() => api.getStatsKnownWords({ interval: 'week' }, init), () => this.knownP, (p) => (this.knownP = p));
			case 'calibration':
				return run(() => api.getStatsCalibration({}, init), () => this.calibrationP, (p) => (this.calibrationP = p));
		}
	}

	/** Leaving the screen: abort what is still loading; nothing is written after this. */
	dispose(): void {
		this.#abort.abort();
		this.#unwatch.splice(0).forEach((f) => f());
	}
}

function load<T>(
	fetch: () => Promise<T>,
	get: () => Panel<T>,
	set: (p: Panel<T>) => void,
	signal: AbortSignal | null | undefined,
	done: () => void
): void {
	fetch().then(
		(data) => {
			done();
			set({ data, failed: false, uncached: false });
		},
		async (e: unknown) => {
			done();
			if (signal?.aborted) return; // the screen was left: not a failure
			const prev = get().data; // a refetch that fails keeps what is shown
			if (await isUncached(e)) set({ data: prev, failed: false, uncached: prev === null });
			else set({ data: prev, failed: prev === null, uncached: false }); // shown inline by the panel, with a retry
		}
	);
}
