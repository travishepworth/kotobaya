<script lang="ts">
	// One card in the browser: the rendered card, its schedule, and a way into the note. Clicking a field
	// region in the card opens that field's box in the note editor (FEAT-x).
	import { goto } from '$app/navigation';
	import { noteHref } from '../../lib/state/noteLink';
	import Button from '../../lib/ui/Button.svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import type { CardState } from '../../lib/ui/kinds';
	import { tc } from '../../lib/cards/messages';
	import { CardPreviewState, type Card, type NoteType } from '../../lib/state/cards.svelte';
	import PreviewPane from './PreviewPane.svelte';
	import { tb } from './messages';
	import { kindLabel, stateOf, stateLabel } from '../../lib/ui/cardState';

	interface Props {
		card: Card;
		noteType: NoteType | undefined;
		onheadword: (seq: number, h: string) => void;
	}
	let { card, noteType, onheadword }: Props = $props();

	const s = new CardPreviewState(
		() => card,
		() => noteType,
		() => onheadword
	);
	const st = $derived<CardState>(stateOf(card));
	const notice = $derived(s.ref.loading ? tc('cards.previewLoading') : s.ref.failed ? tc('cards.previewDictFailed') : null);

	function date(d: Date | null): string {
		return d ? d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : tb('br.dueNone');
	}
</script>

<div class="detail">
	<PreviewPane
		rendered={s.rendered}
		bind:side={s.side}
		kinds={s.kinds}
		kind={s.activeKind}
		kindLabel={kindLabel}
		onkind={(k) => (s.kind = k)}
		errorTitle={tc('cards.previewFailed', { detail: '' }).replace(/[:\s]+$/, '')}
		{notice}
		onfield={(key) => void goto(noteHref(card.noteId, key))}
	/>

	<section class="schedule" aria-labelledby="sched-h">
		<div class="row">
			<h2 id="sched-h">{tb('br.schedule')}</h2>
			<Chip kind="state" state={st} label={stateLabel(card)} />
		</div>
		<dl>
			<div><dt>{tb('br.due')}</dt><dd>{date(card.dueAt)}</dd></div>
			<div><dt>{tb('br.interval')}</dt><dd>{card.intervalDays != null ? tb('br.intervalDays', { n: Math.round(card.intervalDays) }) : '–'}</dd></div>
			<div><dt>{tb('br.reps')}</dt><dd class="kb-num">{card.reps}</dd></div>
			<div><dt>{tb('br.lapses')}</dt><dd class="kb-num">{card.lapses}</dd></div>
		</dl>
	</section>

	<Button variant="outline" icon="edit" href={noteHref(card.noteId)} full>{tb('br.editNote')}</Button>
</div>

<style>
	.detail {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-3);
	}
	h2 {
		font: var(--kb-type-title);
	}
	dl {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr));
		gap: var(--kb-space-3);
		margin: 0;
	}
	dl div {
		padding: var(--kb-space-3);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-title-sm);
	}
</style>
