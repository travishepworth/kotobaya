// Presentation helpers for card state chips (which chip, which word). No domain logic.
import { ut } from './messages';
import type { CardState } from './kinds';
import type { Card } from '../state/cards.svelte';

/** The chip a card shows: suspended and leech win over the scheduling state. */
export function stateOf(c: Pick<Card, 'state' | 'suspended' | 'leech'>): CardState {
	if (c.suspended) return 'suspended';
	if (c.leech) return 'leech';
	return c.state as CardState;
}

export function stateLabel(c: Pick<Card, 'state' | 'suspended' | 'leech'>): string {
	return ut(`ui.state.${stateOf(c)}`);
}
