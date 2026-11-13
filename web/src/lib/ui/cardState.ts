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

/**
 * A display name for a note without dictionary data at hand: its first non-empty text field (HTML stripped), the
 * kanji literal, else a short id. Dictionary-backed notes keep `word` empty, so `??` chains would show nothing.
 */
export function noteTitle(n: { id: string; fields: Record<string, string>; kanjiLiteral: string | null }): string {
	for (const k of ['word', 'expression', 'sentence', 'pattern', 'reading', 'meaning']) {
		const v = (n.fields[k] ?? '').replace(/<[^>]*>/g, '').trim();
		if (v) return v;
	}
	return n.kanjiLiteral ?? `#${n.id.slice(-6)}`;
}
