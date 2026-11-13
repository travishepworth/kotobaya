<script lang="ts">
	// "Saw something weird": mark the grammar in a sentence, attach it to a new or existing grammar point and
	// test a draft detection rule. A dialog (bottom sheet on phones) over the dictionary or the reader.
	import Banner from './Banner.svelte';
	import Button from './Button.svelte';
	import Dialog from './Dialog.svelte';
	import SegmentedControl from './SegmentedControl.svelte';
	import TextField from './TextField.svelte';
	import { tg } from '../grammar-capture/messages';
	import { GrammarCaptureState, selectionOffsets, type Sentence } from '../state/grammarCapture.svelte';

	interface Props {
		sentence: Sentence;
		onclose: () => void;
	}
	let { sentence, onclose }: Props = $props();

	const vm = new GrammarCaptureState(
		() => sentence,
		() => onclose()
	);
	let open = $state(true);
	let textEl = $state<HTMLElement | null>(null);

	function capture(): void {
		if (textEl) vm.capture(selectionOffsets(textEl));
	}
</script>

<Dialog bind:open title={tg('gc.title')} size="lg" {onclose}>
	<div class="capture">
		<p class="help">{tg('gc.select')}</p>
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
		<p class="sentence" lang="ja" bind:this={textEl} onmouseup={capture} onkeyup={capture} ontouchend={capture}>
			{sentence.text}
		</p>
		<p class="span" aria-live="polite">
			{#if vm.span}{tg('gc.span', { span: '' })}<mark lang="ja">{vm.spanText}</mark>{:else}{tg('gc.noSpan')}{/if}
		</p>

		<SegmentedControl
			label={tg('gc.title')}
			full
			bind:value={vm.mode}
			options={[
				{ value: 'new', label: tg('gc.mode.new') },
				{ value: 'existing', label: tg('gc.mode.existing') }
			]}
		/>

		{#if vm.mode === 'new'}
			<div class="two">
				<TextField label={tg('gc.field.title')} bind:value={vm.title} disabled={!!vm.pendingId} lang="ja" />
				<TextField label={tg('gc.field.pattern')} bind:value={vm.pattern} disabled={!!vm.pendingId} lang="ja" />
			</div>
		{:else}
			<TextField label={tg('gc.search')} type="search" icon="search" bind:value={vm.q} />
			{#if vm.q.trim() && vm.results.length === 0}<p class="help">{tg('gc.noResults')}</p>{/if}
			{#if vm.results.length > 0}
				<div class="points" role="radiogroup" aria-label={tg('gc.search')}>
					{#each vm.results as p (p.id)}
						<label class="point" class:on={vm.chosen?.id === p.id}>
							<input type="radio" name="gc-point" checked={vm.chosen?.id === p.id} onchange={() => vm.choose(p)} />
							<span lang="ja">{p.title}</span>
							{#if p.pattern}<small lang="ja">{p.pattern}</small>{/if}
						</label>
					{/each}
				</div>
			{/if}
		{/if}

		<div class="rule">
			<TextField label={tg('gc.field.rule')} bind:value={vm.rule} lang="ja" error={vm.ruleError || undefined} />
			<Button
				variant="outline"
				loading={vm.busy}
				disabled={!vm.rule.trim() || (vm.mode === 'existing' && !vm.chosen)}
				onclick={() => void vm.test()}>{tg('gc.test')}</Button
			>
		</div>
		{#if vm.testResult}
			<div class="results">
				<p class="help">{tg('gc.testResult', { count: vm.testResult.matches.length, scanned: vm.testResult.scanned })}</p>
				{#if vm.testResult.truncated}<p class="help">{tg('gc.testTruncated')}</p>{/if}
				<ul lang="ja">
					{#each vm.testResult.matches as m (m.sentence.id)}
						{@const sp = m.spans[0]}
						<li>
							{#if sp}{m.sentence.text.slice(0, sp.startOffset)}<mark>{m.sentence.text.slice(sp.startOffset, sp.endOffset)}</mark
								>{m.sentence.text.slice(sp.endOffset)}{:else}{m.sentence.text}{/if}
						</li>
					{/each}
				</ul>
			</div>
		{/if}
		{#if vm.error}<Banner tone="error">{vm.error}</Banner>{/if}
	</div>
	{#snippet actions()}
		<Button variant="quiet" onclick={onclose}>{tg('gc.cancel')}</Button>
		<Button variant="primary" loading={vm.busy} onclick={() => void vm.save()}>{tg('gc.save')}</Button>
	{/snippet}
</Dialog>

<style>
	.capture {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		color: var(--kb-color-text);
	}
	.help {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.sentence {
		padding: var(--kb-space-4);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-jp-body);
		font-size: 1.25rem;
		user-select: text;
		cursor: text;
	}
	.span {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	mark {
		padding: 0 0.15em;
		border-radius: var(--kb-radius-xs);
		background: var(--kb-color-highlight);
		color: var(--kb-color-on-highlight);
	}
	.two {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
		gap: var(--kb-space-3);
	}
	.points {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
	}
	.point {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-3);
		border-radius: var(--kb-radius-md);
		cursor: pointer;
	}
	.point:hover,
	.point.on {
		background: var(--kb-color-surface-selected);
	}
	.point small {
		color: var(--kb-color-text-muted);
	}
	.rule {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
		gap: var(--kb-space-2);
	}
	.results ul {
		margin: var(--kb-space-2) 0 0;
		padding-left: var(--kb-space-5);
		font: var(--kb-type-jp-body);
	}
</style>
