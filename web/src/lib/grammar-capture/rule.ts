import type { Sentence } from '../api/generated';

/**
 * Draft rule for a span: the surfaces of the tokens it covers, space separated.
 * Returns '' when the span covers no whole token.
 */
export function draftRule(s: Sentence, start: number, end: number): string {
	return s.tokens
		.filter((t) => t.s >= start && t.e <= end)
		.map((t) => t.surface)
		.join(' ');
}

/** Offsets of the current selection inside `root`'s single text node, or null. */
export function selectionOffsets(root: HTMLElement): { start: number; end: number } | null {
	const sel = window.getSelection();
	if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
	const r = sel.getRangeAt(0);
	if (!root.contains(r.startContainer) || !root.contains(r.endContainer)) return null;
	const start = Math.min(r.startOffset, r.endOffset);
	const end = Math.max(r.startOffset, r.endOffset);
	return end > start && r.startContainer === r.endContainer ? { start, end } : null;
}
