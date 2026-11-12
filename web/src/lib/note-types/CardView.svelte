<script lang="ts">
	// Displays one RenderedCard from the shared core renderer. This is the single place the
	// note-type preview puts card HTML into the DOM. T209's review screen has no exported
	// CardView yet (web/src/lib/review/ does not exist on main); when it lands, swap this
	// component in Editor.svelte for that one (same input: the RenderedCard).
	import type { RenderedCard } from '../core/index';

	let { rendered }: { rendered: RenderedCard } = $props();

	let host: HTMLDivElement | undefined = $state();

	// The note type's CSS is user text: set it as text (never parsed as HTML) and scope it to this
	// preview so it cannot restyle the editor around it.
	$effect(() => {
		if (!host) return;
		const style = document.createElement('style');
		style.textContent = `@scope (.kb-preview-card) {\n${rendered.extraCss}\n}`;
		// eslint-disable-next-line svelte/no-dom-manipulating -- style text is set via textContent, never parsed as HTML
		host.prepend(style);
		return () => style.remove();
	});
</script>

<div class="kb-preview-card" bind:this={host}>
	<!-- html comes from renderCard, which sanitises it (allow-list of elements, attributes, classes) -->
	<!-- eslint-disable-next-line svelte/no-at-html-tags -->
	{@html rendered.html}
</div>
