import type { IconName } from './icons';

// Small shared vocabularies of the design system (names match the colour tokens).
export type CardState = 'new' | 'learning' | 'review' | 'relearning' | 'suspended' | 'leech';
export type Grade = 'again' | 'hard' | 'good' | 'easy';
export type DeckColor = 'ai' | 'shu' | 'matcha' | 'kohaku' | 'fuji' | 'sora' | 'sakura' | 'aoi' | 'hai';
export type PitchPattern = 'heiban' | 'atamadaka' | 'nakadaka' | 'odaka';

export const CARD_STATES: CardState[] = ['new', 'learning', 'review', 'relearning', 'suspended', 'leech'];
export const GRADES: Grade[] = ['again', 'hard', 'good', 'easy'];
export const DECK_COLORS: DeckColor[] = ['ai', 'shu', 'matcha', 'kohaku', 'fuji', 'sora', 'sakura', 'aoi', 'hai'];

/**
 * Tokyo pitch accent: which morae are high for a downstep position (0 = heiban, 1 = atamadaka, n = the mora after
 * which the pitch drops), and the pattern name. The drop after the last mora (odaka) only shows on a following particle.
 */
export function pitchShape(moraCount: number, downstep: number): { high: boolean[]; pattern: PitchPattern } {
	const high = Array.from({ length: moraCount }, (_, i) => {
		const pos = i + 1;
		if (downstep === 0) return pos > 1;
		if (downstep === 1) return pos === 1;
		return pos > 1 && pos <= downstep;
	});
	const pattern: PitchPattern =
		downstep === 0 ? 'heiban' : downstep === 1 ? 'atamadaka' : downstep >= moraCount ? 'odaka' : 'nakadaka';
	return { high, pattern };
}

/** A DataTable column. */
export interface TableColumn {
	key: string;
	label: string;
	align?: 'start' | 'end';
	/** CSS width (e.g. "8rem", "30%"); the rest share the remaining space. */
	width?: string;
	sortable?: boolean;
	/** lang of the cell text ("ja" for headwords). */
	lang?: string;
	/** Hidden below 600 px of table width (the row keeps its essential columns). */
	optional?: boolean;
}

/** An AppShell destination. */
export interface NavItem {
	href: string;
	label: string;
	icon: IconName;
	/** Settings-like destinations: bottom of the sidebar, inside "More" on phones. */
	secondary?: boolean;
}
