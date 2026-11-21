<script lang="ts">
	// Inline "needs connection" label shown next to a disabled remote-only action while the shell is offline.
	import { needsConnectionFor } from './access';
	import { sh } from './shellMessages';
	let { op }: { op: string } = $props();
</script>

{#if needsConnectionFor(op)}<span class="nc" role="status">{sh('shell.needs.action')}</span>{/if}

<style>
	.nc {
		color: var(--kb-text-muted, inherit);
		font-size: 0.875em;
	}
</style>
