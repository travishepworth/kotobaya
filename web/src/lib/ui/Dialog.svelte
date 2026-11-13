<script lang="ts">
	import type { Snippet } from 'svelte';
	import IconButton from './IconButton.svelte';
	import { ut } from './messages';

	interface Props {
		open?: boolean;
		title: string;
		/** Below 600 px the dialog becomes a bottom sheet (thumb reach); `sheet` forces the sheet everywhere. */
		sheet?: boolean;
		/** Renders the panel in the page flow (styleguide, docs) instead of as a modal. */
		inline?: boolean;
		/** lg: forms and editors (40 rem wide). */
		size?: 'md' | 'lg';
		onclose?: () => void;
		children: Snippet;
		actions?: Snippet;
	}
	let { open = $bindable(false), title, sheet = false, inline = false, size = 'md', onclose, children, actions }: Props = $props();

	let dialog: HTMLDialogElement | undefined = $state();
	const uid = $props.id();

	$effect(() => {
		if (inline || !dialog) return;
		if (open && !dialog.open) dialog.showModal();
		else if (!open && dialog.open) dialog.close();
	});

	function close(): void {
		open = false;
		onclose?.();
	}
</script>

{#snippet panel()}
	<div class="handle" aria-hidden="true"></div>
	<header>
		<h2 id="dlg-{uid}">{title}</h2>
		<IconButton icon="close" label={ut('ui.close')} onclick={close} />
	</header>
	<div class="body">{@render children()}</div>
	{#if actions}<footer>{@render actions()}</footer>{/if}
{/snippet}

{#if inline}
	<div class="panel inline" class:sheet role="group" aria-labelledby="dlg-{uid}">{@render panel()}</div>
{:else}
	<dialog
		bind:this={dialog}
		class="panel {size}"
		class:sheet
		aria-labelledby="dlg-{uid}"
		onclose={() => {
			if (open) close();
		}}
		onclick={(e) => {
			// A click on the backdrop lands on the <dialog> itself.
			if (e.target === dialog) close();
		}}
	>
		{@render panel()}
	</dialog>
{/if}

<style>
	.panel {
		width: min(32rem, calc(100vw - 2 * var(--kb-space-4)));
		max-height: min(80dvh, 48rem);
		padding: 0;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-xl);
		background: var(--kb-color-surface-raised);
		color: var(--kb-color-text);
		box-shadow: var(--kb-elevation-3);
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}
	.panel.lg {
		width: min(40rem, calc(100vw - 2 * var(--kb-space-4)));
		max-height: min(88dvh, 56rem);
	}
	dialog.panel:not([open]) {
		display: none;
	}
	.inline {
		position: relative;
		width: 100%;
		max-width: 32rem;
	}
	dialog::backdrop {
		background: var(--kb-color-scrim);
	}
	dialog[open] {
		animation: rise var(--kb-duration-base) var(--kb-ease-emphasized);
	}
	dialog[open]::backdrop {
		animation: fade var(--kb-duration-base) var(--kb-ease-standard);
	}
	@keyframes rise {
		from {
			opacity: 0;
			transform: translateY(var(--kb-distance-md)) scale(0.99);
		}
	}
	@keyframes fade {
		from {
			opacity: 0;
		}
	}
	.handle {
		display: none;
		width: 2.25rem;
		height: 4px;
		margin: var(--kb-space-2) auto 0;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-border-strong);
	}
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
		padding: var(--kb-space-4) var(--kb-space-3) 0 var(--kb-space-6);
	}
	h2 {
		margin: 0;
		font: var(--kb-type-title);
	}
	.body {
		padding: var(--kb-space-2) var(--kb-space-6) var(--kb-space-4);
		overflow-y: auto;
		font: var(--kb-type-body);
		color: var(--kb-color-text-muted);
	}
	footer {
		display: flex;
		flex-wrap: wrap;
		justify-content: flex-end;
		gap: var(--kb-space-2);
		padding: var(--kb-space-3) var(--kb-space-6) var(--kb-space-5);
	}

	/* Bottom sheet: compact widths, or forced. */
	@media (max-width: 599.98px) {
		dialog.panel {
			width: 100%;
			max-width: none;
			margin: auto 0 0;
			border-radius: var(--kb-radius-xl) var(--kb-radius-xl) 0 0;
			border-bottom: 0;
			padding-bottom: env(safe-area-inset-bottom);
		}
		dialog.panel .handle {
			display: block;
		}
		footer {
			flex-direction: column-reverse;
		}
		footer > :global(*) {
			width: 100%;
		}
	}
	.sheet {
		border-radius: var(--kb-radius-xl) var(--kb-radius-xl) 0 0;
	}
	.sheet .handle {
		display: block;
	}
	dialog.sheet {
		width: 100%;
		max-width: 40rem;
		margin: auto auto 0;
	}
</style>
