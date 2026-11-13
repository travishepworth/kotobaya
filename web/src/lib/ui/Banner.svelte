<script lang="ts">
	// An inline notice inside a page or panel: info, warning, success or error. Icon + text, optional action.
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';

	interface Props {
		tone?: 'info' | 'warning' | 'success' | 'error';
		title?: string;
		children: Snippet;
		action?: Snippet;
	}
	let { tone = 'info', title, children, action }: Props = $props();
	const glyph: Record<NonNullable<Props['tone']>, IconName> = {
		info: 'info',
		warning: 'alert',
		success: 'success',
		error: 'alert'
	};
</script>

<div class="banner {tone}" role={tone === 'error' ? 'alert' : 'status'}>
	<span class="glyph"><Icon name={glyph[tone]} size={20} /></span>
	<div class="text">
		{#if title}<p class="title">{title}</p>{/if}
		<div class="body">{@render children()}</div>
	</div>
	{#if action}<div class="action">{@render action()}</div>{/if}
</div>

<style>
	.banner {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-3);
		padding: var(--kb-space-3) var(--kb-space-4);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		font: var(--kb-type-body-sm);
	}
	.warning {
		background: var(--kb-color-warning-container);
		color: var(--kb-color-on-warning-container);
	}
	.success {
		background: var(--kb-color-success-container);
		color: var(--kb-color-on-success-container);
	}
	.error {
		background: var(--kb-color-danger-container);
		color: var(--kb-color-on-danger-container);
	}
	.glyph {
		display: flex;
		flex: none;
		align-self: flex-start;
		padding-top: 1px;
	}
	.text {
		flex: 1 1 14rem;
		min-width: 0;
	}
	.title {
		font-weight: 600;
	}
	.body :global(p) {
		margin: 0;
	}
	.action {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		margin-inline-start: auto;
	}
</style>
