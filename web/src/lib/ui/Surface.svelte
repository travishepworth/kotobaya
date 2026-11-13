<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		/** surface: panels and cards. sunken: wells (mnemonics, code). raised: floating things. */
		tone?: 'surface' | 'sunken' | 'raised';
		padding?: 'none' | 'sm' | 'md' | 'lg';
		elevation?: 0 | 1 | 2 | 3;
		/** Hairline border (default on for surface/raised so panels read in both themes). */
		bordered?: boolean;
		as?: 'div' | 'section' | 'article' | 'aside';
		label?: string;
		children: Snippet;
	}
	let { tone = 'surface', padding = 'md', elevation = 0, bordered = tone !== 'sunken', as = 'div', label, children }: Props =
		$props();
</script>

<svelte:element
	this={as}
	class="surface tone-{tone} pad-{padding}"
	class:bordered
	style:box-shadow="var(--kb-elevation-{elevation})"
	aria-label={label}
>
	{@render children()}
</svelte:element>

<style>
	.surface {
		border-radius: var(--kb-radius-lg);
		color: var(--kb-color-text);
		min-width: 0;
	}
	.tone-surface {
		background: var(--kb-color-surface);
	}
	.tone-sunken {
		background: var(--kb-color-surface-sunken);
		border-radius: var(--kb-radius-md);
	}
	.tone-raised {
		background: var(--kb-color-surface-raised);
	}
	.bordered {
		border: 1px solid var(--kb-color-border);
	}
	.pad-none {
		padding: 0;
	}
	.pad-sm {
		padding: var(--kb-space-3);
	}
	.pad-md {
		padding: var(--kb-space-4) var(--kb-space-5);
	}
	.pad-lg {
		padding: clamp(var(--kb-space-5), 4cqi, var(--kb-space-10));
	}
</style>
