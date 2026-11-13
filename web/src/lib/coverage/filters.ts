export const HIDE_CLASSES = ['names', 'particles', 'auxiliary', 'ignored', 'unmapped'] as const;
export type HideClass = (typeof HIDE_CLASSES)[number];

export interface CoverageFilters {
	hide: HideClass[];
	/** Selected segment id; null = whole media. */
	segmentId: string | null;
}

export const STORAGE_KEY = 'kotobaya.coverage.filters';

export function defaultFilters(): CoverageFilters {
	return { hide: ['ignored'], segmentId: null };
}

export interface StorageLike {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
}

function defaultStorage(): StorageLike | null {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage;
	} catch {
		return null;
	}
}

export function loadFilters(storage: StorageLike | null = defaultStorage()): CoverageFilters {
	try {
		const raw = storage?.getItem(STORAGE_KEY);
		if (!raw) return defaultFilters();
		const v: unknown = JSON.parse(raw);
		if (typeof v !== 'object' || v === null) return defaultFilters();
		const o = v as { hide?: unknown; segmentId?: unknown };
		const hide = Array.isArray(o.hide)
			? HIDE_CLASSES.filter((c) => (o.hide as unknown[]).includes(c))
			: defaultFilters().hide;
		const segmentId = typeof o.segmentId === 'string' ? o.segmentId : null;
		return { hide: [...hide], segmentId };
	} catch {
		return defaultFilters();
	}
}

export function saveFilters(
	f: CoverageFilters,
	storage: StorageLike | null = defaultStorage()
): void {
	try {
		storage?.setItem(STORAGE_KEY, JSON.stringify(f));
	} catch {
		/* private mode or quota: the page works without persistence */
	}
}
