<script lang="ts">
	// The actual card, rendered by the shared core with card.css, inside a card surface: Front | Back, the card
	// type when the note has several, and a card-shaped error when the renderer fails (never the whole page).
	import CardFrame from '../../lib/ui/CardFrame.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import { ut } from '../../lib/ui/messages';
	import type { CardSide, Rendered } from '../../lib/state/preview.svelte';
	import { tb } from './messages';

	interface Props {
		rendered: Rendered;
		side: CardSide;
		kinds: string[];
		kind: string;
		kindLabel?: (k: string) => string;
		onkind: (k: string) => void;
		errorTitle: string;
		notice?: string | null;
		onfield?: (key: string) => void;
		overridden?: string[];
		activeField?: string | null;
	}
	let {
		rendered,
		side = $bindable(),
		kinds,
		kind,
		kindLabel = (k) => k,
		onkind,
		errorTitle,
		notice = null,
		onfield,
		overridden = [],
		activeField = null
	}: Props = $props();
</script>

<div class="preview">
	<div class="controls">
		<SegmentedControl
			label={ut('app.side')}
			bind:value={side}
			options={[
				{ value: 'front', label: ut('app.front') },
				{ value: 'back', label: ut('app.back.side') }
			]}
		/>
		{#if kinds.length > 1}
			<div class="kind">
				<Select label={tb('br.kind')} hideLabel value={kind} options={kinds.map((k) => ({ value: k, label: kindLabel(k) }))} onchange={onkind} />
			</div>
		{/if}
	</div>
	<div class="card" aria-label={tb('br.preview')} role="figure">
		{#if rendered.ok}
			<CardFrame rendered={rendered.card} {onfield} {overridden} {activeField} />
		{:else}
			<EmptyState tone="error" art="alert" title={errorTitle} body={rendered.detail} />
		{/if}
	</div>
	{#if notice}<p class="notice">{notice}</p>{/if}
</div>

<style>
	.preview {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.controls {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
	}
	.kind {
		flex: 0 1 12rem;
	}
	.card {
		min-height: 12rem;
		padding: var(--kb-space-8) var(--kb-space-5) var(--kb-space-6);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
	}
	.notice {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
