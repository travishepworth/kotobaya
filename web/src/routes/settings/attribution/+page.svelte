<script lang="ts">
	import Page from '../../../lib/ui/Page.svelte';
	import { ts, type SettingsMessageKey } from '../../../lib/settings/messages';

	const ITEMS: { text: SettingsMessageKey; href: string }[] = [
		{ text: 'attr.jmdict', href: 'https://www.edrdg.org/edrdg/licence.html' },
		{ text: 'attr.tatoeba', href: 'https://tatoeba.org/' },
		{ text: 'attr.fsrs', href: 'https://github.com/open-spaced-repetition' },
		{ text: 'attr.seaweedfs', href: 'https://github.com/seaweedfs/seaweedfs' }
	];
</script>

<svelte:head><title>{ts('attr.title')} · Kotobaya</title></svelte:head>

<Page title={ts('attr.title')} back={{ href: '/settings', label: ts('settings.title') }} width="narrow">
	<ul class="list">
		{#each ITEMS as it (it.text)}
			<li>
				<p>{ts(it.text)}</p>
				<a href={it.href} rel="noopener noreferrer" target="_blank">{ts('attr.link')}</a>
			</li>
		{/each}
	</ul>
</Page>

<style>
	.list {
		display: flex;
		flex-direction: column;
		margin: 0;
		padding: 0;
		list-style: none;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	li {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-4) var(--kb-space-5);
		border-bottom: 1px solid var(--kb-color-border);
	}
	li:last-child {
		border-bottom: 0;
	}
	a {
		display: inline-flex;
		align-items: center;
		align-self: flex-start;
		min-height: var(--kb-size-touch-min);
		font: var(--kb-type-label);
	}
</style>
