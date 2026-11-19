<script lang="ts">
	// Error page. A thrown exception (e.g. a Svelte effect loop) reaches SvelteKit as status 500; the user gets a
	// friendly "Something went wrong" with Reload instead of a bare "500" (BUG-ADD2DECK-500). 404 says not found.
	import { page } from '$app/state';
	import Button from '../lib/ui/Button.svelte';
	import { ut } from '../lib/ui/messages';

	const notFound = $derived(page.status === 404);
</script>

<section class="err" role="alert">
	<h1>{notFound ? ut('app.error.notFound') : ut('app.error.title')}</h1>
	<p>{notFound ? ut('app.error.notFoundBody') : ut('app.error.body')}</p>
	<div class="actions">
		{#if !notFound}
			<Button variant="primary" onclick={() => location.reload()}>{ut('app.error.reload')}</Button>
		{/if}
		<Button variant="outline" href="/">{ut('app.error.home')}</Button>
	</div>
</section>

<style>
	.err {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		max-width: 32rem;
		margin: var(--kb-space-8) auto;
		padding: 0 var(--kb-space-4);
	}
	h1 {
		margin: 0;
		font: var(--kb-type-title);
	}
	p {
		margin: 0;
		color: var(--kb-color-text-muted);
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
	}
</style>
