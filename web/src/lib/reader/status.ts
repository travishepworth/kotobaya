import { DictionaryApi } from '../api/generated';
import type { WordStatusValue } from '../api/generated';
import { apiConfig } from '../api/client';

const dict = new DictionaryApi(apiConfig);
const cache = new Map<number, WordStatusValue>();
const inflight = new Map<number, Promise<WordStatusValue | null>>();

export function cachedStatus(seq: number): WordStatusValue | undefined {
	return cache.get(seq);
}

export function setCachedStatus(seq: number, status: WordStatusValue): void {
	cache.set(seq, status);
}

/** Word status of an entry, fetched once and cached (SentenceToken carries no status). */
export function loadStatus(seq: number): Promise<WordStatusValue | null> {
	const hit = cache.get(seq);
	if (hit) return Promise.resolve(hit);
	let p = inflight.get(seq);
	if (!p) {
		p = dict
			.getDictEntry({ seq })
			.then((e) => {
				cache.set(seq, e.status);
				return e.status;
			})
			.catch(() => null)
			.finally(() => inflight.delete(seq));
		inflight.set(seq, p);
	}
	return p;
}
