<script lang="ts">
	// Shown instead of a route whose operations all need the server while the phone is offline (SHELL.md 9.4).
	import Button from './Button.svelte';
	import EmptyState from './EmptyState.svelte';
	import { sh } from './shellMessages';
</script>

<div class="wrap">
	<EmptyState art="alert" size="page" title={sh('shell.needs.title')} body={sh('shell.needs.body')}>
		{#snippet action()}
			<Button variant="tonal" href="/">{sh('shell.needs.home')}</Button>
		{/snippet}
	</EmptyState>
</div>

<style>
	.wrap {
		padding: var(--kb-space-6) var(--kb-space-4);
	}
</style>
