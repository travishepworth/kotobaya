<script lang="ts">
	// Word actions as one overflow menu: Known / Never forget / Ignore / Blacklist / Reset, and Add to my notes.
	// Shared by the deck inspector; the state that performs the calls lives with the caller.
	import Menu from '../ui/Menu.svelte';
	import type { MenuItem } from '../ui/kinds';
	import { td } from '../decks/messages';
	import type { SettableStatus, StatusAction } from '../state/deckInspect';

	interface Props {
		status: string;
		/** Offer "Add to my notes" (the word has no note yet). */
		canMine?: boolean;
		disabled?: boolean;
		onaction: (a: StatusAction) => void;
		onmine?: () => void;
	}
	let { status, canMine = false, disabled = false, onaction, onmine }: Props = $props();

	const SET: readonly SettableStatus[] = ['known', 'never_forget', 'ignored', 'blacklisted'];

	const items = $derived<MenuItem[]>([
		...SET.map((st) => ({ label: td(`decks.act.${st}`), checked: status === st, disabled, onselect: () => onaction({ kind: 'set', status: st }) })),
		{ label: td('decks.act.reset'), disabled, onselect: () => onaction({ kind: 'reset' }) },
		...(canMine && onmine ? [{ label: td('decks.act.mine'), icon: 'plus' as const, disabled, onselect: onmine }] : [])
	]);
</script>

<Menu label={td('decks.act.more')} {items} />
