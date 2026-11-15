<script lang="ts">
	// Word actions as one overflow menu: Known / Never forget / Ignore / Blacklist / Reset, and Add to my notes.
	// Shared by the deck inspector; the state that performs the calls lives with the caller.
	import Menu from '../ui/Menu.svelte';
	import type { MenuItem } from '../ui/kinds';
	import { td } from '../decks/messages';
	import type { SettableStatus, StatusAction } from '../state/deckInspect';
	import { WordCardsState } from '../state/wordCards.svelte';
	import { pushToast } from '../toasts.svelte';

	interface Props {
		status: string;
		/** Offer "Add to my notes" (the word has no note yet). */
		canMine?: boolean;
		disabled?: boolean;
		onaction: (a: StatusAction) => void;
		onmine?: () => void;
		/** Dictionary entry of the word: enables the Suspend/Unsuspend cards toggle (shown only when it has cards). */
		seq?: number | null;
	}
	let { status, canMine = false, disabled = false, onaction, onmine, seq = null }: Props = $props();

	const cards = new WordCardsState();

	function loadCards(): void {
		if (seq != null) void cards.load(seq);
	}

	async function toggleCards(): Promise<void> {
		if (seq == null) return;
		const was = cards.allSuspended;
		if (await cards.toggle(seq)) pushToast(td(was ? 'decks.cardsUnsuspended' : 'decks.cardsSuspended'), 'info');
	}

	const SET: readonly SettableStatus[] = ['known', 'never_forget', 'ignored', 'blacklisted'];

	const items = $derived<MenuItem[]>([
		...SET.map((st) => ({ label: td(`decks.act.${st}`), checked: status === st, disabled, onselect: () => onaction({ kind: 'set', status: st }) })),
		{ label: td('decks.act.reset'), disabled, onselect: () => onaction({ kind: 'reset' }) },
		...(cards.hasCards
			? [{ label: td(cards.allSuspended ? 'decks.act.unsuspendCards' : 'decks.act.suspendCards'), icon: 'pause' as const, disabled: disabled || cards.busy, onselect: () => void toggleCards() }]
			: []),
		...(canMine && onmine ? [{ label: td('decks.act.mine'), icon: 'plus' as const, disabled, onselect: onmine }] : [])
	]);
</script>

<Menu label={td('decks.act.more')} {items} onopen={loadCards} />
