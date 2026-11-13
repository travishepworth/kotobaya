<script lang="ts">
	import { GrammarCaptureState, selectionOffsets, type Sentence } from '../state/grammarCapture.svelte';
	import { tg } from './messages';

	let { sentence, onclose }: { sentence: Sentence; onclose: () => void } = $props();

	const vm = new GrammarCaptureState(
		() => sentence,
		() => onclose()
	);
	let textEl = $state<HTMLElement | null>(null);

	function capture(): void {
		if (!textEl) return;
		vm.capture(selectionOffsets(textEl));
	}
</script>

<section class="capture" aria-label={tg('gc.title')}>
	<h3>{tg('gc.title')}</h3>
	<p class="muted">{tg('gc.select')}</p>
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<p class="text" lang="ja" bind:this={textEl} onmouseup={capture} onkeyup={capture}>{sentence.text}</p>
	<p>{vm.span ? tg('gc.span', { span: vm.spanText }) : tg('gc.noSpan')}</p>

	<label><input type="radio" bind:group={vm.mode} value="new" /> {tg('gc.mode.new')}</label>
	<label><input type="radio" bind:group={vm.mode} value="existing" /> {tg('gc.mode.existing')}</label>

	{#if vm.mode === 'new'}
		<label>{tg('gc.field.title')} <input bind:value={vm.title} disabled={!!vm.pendingId} /></label>
		<label>{tg('gc.field.pattern')} <input bind:value={vm.pattern} disabled={!!vm.pendingId} /></label>
	{:else}
		<input type="search" placeholder={tg('gc.search')} aria-label={tg('gc.search')} bind:value={vm.q} />
		{#if vm.q.trim() && vm.results.length === 0}<p class="muted">{tg('gc.noResults')}</p>{/if}
		<ul>
			{#each vm.results as p (p.id)}
				<li>
					<label>
						<input
							type="radio"
							name="gc-point"
							checked={vm.chosen?.id === p.id}
							onchange={() => vm.choose(p)}
						/>
						<span lang="ja">{p.title}</span>
						{#if p.pattern}<small lang="ja">{p.pattern}</small>{/if}
					</label>
				</li>
			{/each}
		</ul>
	{/if}

	<label>{tg('gc.field.rule')} <input lang="ja" bind:value={vm.rule} /></label>
	<button
		type="button"
		onclick={() => void vm.test()}
		disabled={vm.busy || !vm.rule.trim() || (vm.mode === 'existing' && !vm.chosen)}
	>
		{vm.busy ? tg('gc.testing') : tg('gc.test')}
	</button>
	{#if vm.ruleError}<p class="err" role="alert">{vm.ruleError}</p>{/if}
	{#if vm.testResult}
		<p>{tg('gc.testResult', { count: vm.testResult.matches.length, scanned: vm.testResult.scanned })}</p>
		{#if vm.testResult.truncated}<p class="muted">{tg('gc.testTruncated')}</p>{/if}
		<ul lang="ja">
			{#each vm.testResult.matches as m (m.sentence.id)}
				{@const sp = m.spans[0]}
				<li>
					{#if sp}{m.sentence.text.slice(0, sp.startOffset)}<mark
							>{m.sentence.text.slice(sp.startOffset, sp.endOffset)}</mark
						>{m.sentence.text.slice(sp.endOffset)}{:else}{m.sentence.text}{/if}
				</li>
			{/each}
		</ul>
	{/if}

	{#if vm.error}<p class="err" role="alert">{vm.error}</p>{/if}
	<div>
		<button type="button" onclick={() => void vm.save()} disabled={vm.busy}>
			{vm.busy ? tg('gc.saving') : tg('gc.save')}
		</button>
		<button type="button" onclick={onclose}>{tg('gc.cancel')}</button>
	</div>
</section>

<style>
	.capture {
		display: grid;
		gap: var(--kb-space-2);
		margin: var(--kb-space-2) 0;
		padding: var(--kb-space-3);
		border: 1px solid currentColor;
	}
	.text {
		font-size: 1.2rem;
		user-select: text;
	}
	.err {
		color: crimson;
	}
	ul {
		list-style: none;
		padding: 0;
	}
</style>
