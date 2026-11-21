<script lang="ts">
	// Full-screen "Setting up" while the first sync pulls (SHELL.md 10.18, ADR-050). The layout shows it only while
	// `isFirstSyncPending()`; the progress comes from `shell.firstSync`, fed by `shell.sync` events.
	import { th } from '../../routes/messages';
	import { shell } from '../state/shell.svelte';

	const total = $derived(shell.firstSync?.total ?? null);
	const applied = $derived(shell.firstSync?.applied ?? 0);
	const determinate = $derived(total !== null && total > 0);
</script>

<div class="setup" role="status" aria-live="polite">
	<span class="seal" aria-hidden="true">言</span>
	<h1 class="title">{th('firstSync.title')}</h1>
	{#if determinate && total !== null}
		<progress class="bar" max={total} value={Math.min(applied, total)} aria-label={th('firstSync.title')}></progress>
		<p class="count kb-num">{th('firstSync.progress', { n: Math.min(applied, total), total })}</p>
	{:else}
		<progress class="bar" aria-label={th('firstSync.title')}></progress>
		<p class="count">{th('firstSync.indeterminate')}</p>
	{/if}
	{#if !shell.online}<p class="wait">{th('firstSync.waiting')}</p>{/if}
</div>

<style>
	.setup {
		display: grid;
		place-items: center;
		align-content: center;
		gap: var(--kb-space-4);
		min-height: 100dvh;
		padding: var(--kb-space-6) var(--kb-space-4);
		text-align: center;
	}
	.seal {
		display: grid;
		place-items: center;
		width: 3rem;
		height: 3rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-brand);
		color: var(--kb-color-surface);
		font: 600 1.75rem/1 var(--kb-family-serif);
	}
	.title {
		margin: 0;
		font: var(--kb-type-title);
		color: var(--kb-color-text);
	}
	.bar {
		width: min(20rem, 80vw);
		height: 4px;
		accent-color: var(--kb-color-primary);
	}
	.count,
	.wait {
		margin: 0;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
