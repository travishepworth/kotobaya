<script lang="ts">
	// The designed load-error state with a retry button. Pages show it when their state module's
	// `loadFailed` flag is set and wire `onretry` to the module's `retry()`.
	// page / inline: the error EmptyState (nothing loaded). banner: an error Banner under rows that did
	// load (a later page failed).
	import Banner from './Banner.svelte';
	import Button from './Button.svelte';
	import EmptyState from './EmptyState.svelte';
	import { ut } from './messages';

	interface Props {
		onretry: () => void;
		title?: string;
		body?: string;
		size?: 'page' | 'inline' | 'banner';
	}
	let { onretry, title = ut('app.loadFailedTitle'), body = ut('app.loadFailedBody'), size = 'inline' }: Props = $props();
</script>

{#if size === 'banner'}
	<Banner tone="error" {title}>
		{body}
		{#snippet action()}<Button size="sm" variant="outline" icon="sync" onclick={onretry}>{ut('app.retry')}</Button>{/snippet}
	</Banner>
{:else}
	<EmptyState tone="error" art="alert" {title} {body} {size}>
		{#snippet action()}<Button variant="outline" icon="sync" onclick={onretry}>{ut('app.retry')}</Button>{/snippet}
	</EmptyState>
{/if}
