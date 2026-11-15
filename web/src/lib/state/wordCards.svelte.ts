// Suspend / unsuspend all live cards of one dictionary word (entry seq), via the card search `entry:<seq>`
// and POST /cards/bulk with a query selector. Shared by the deck item menu and the reader token sheet.
import { CardsApi } from '../api/generated';
import { apiConfig } from '../api/client';

const cardsApi = new CardsApi(apiConfig);

/** One page covers any realistic word (a few kinds per note); more than that is treated as "has cards". */
const PAGE = 500;

export class WordCardsState {
	/** idle: not asked yet; loading; ready: counts valid; failed: lookup failed (the toggle stays hidden). */
	phase = $state<'idle' | 'loading' | 'ready' | 'failed'>('idle');
	total = $state(0);
	suspended = $state(0);
	busy = $state(false);

	readonly hasCards = $derived(this.phase === 'ready' && this.total > 0);
	/** Every card of the word is suspended: the toggle offers Unsuspend. */
	readonly allSuspended = $derived(this.hasCards && this.suspended === this.total);

	static query(seq: number): string {
		return `entry:${seq}`;
	}

	async load(seq: number): Promise<void> {
		this.phase = 'loading';
		try {
			const page = await cardsApi.listCards({ query: WordCardsState.query(seq), limit: PAGE });
			this.total = page.items.length;
			this.suspended = page.items.filter((c) => c.suspended).length;
			this.phase = 'ready';
		} catch {
			this.phase = 'failed'; // toast shown by the client
		}
	}

	/** Suspend (or, when all are suspended, unsuspend) every live card of the word. Returns whether it worked. */
	async toggle(seq: number): Promise<boolean> {
		if (this.busy || !this.hasCards) return false;
		const suspend = !this.allSuspended;
		this.busy = true;
		try {
			await cardsApi.bulkCards({
				cardBulkRequest: { query: WordCardsState.query(seq), action: { type: suspend ? 'suspend' : 'unsuspend' } }
			});
			this.suspended = suspend ? this.total : 0;
			return true;
		} catch {
			return false;
		} finally {
			this.busy = false;
		}
	}
}
