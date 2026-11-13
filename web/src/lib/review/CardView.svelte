<script lang="ts">
	// Displays one RenderedCard from the shared core renderer. No review-session state: used by the
	// review screen and the note-type preview (T211). Furigana is a CSS class switch on the
	// container (the card is always rendered with readings), never a re-render.
	import { MEDIA_URL_TEMPLATE, type RenderedCard } from '../state/preview.svelte';

	let {
		rendered,
		furigana = true,
		mediaUrlTemplate = MEDIA_URL_TEMPLATE
	}: { rendered: RenderedCard; furigana?: boolean; mediaUrlTemplate?: string } = $props();

	let host: HTMLDivElement | undefined = $state();
	let current: HTMLAudioElement | null = null;

	// The note type's CSS is user text: set it as text (never parsed as HTML) and scope it to this
	// card so it cannot restyle the page around it.
	$effect(() => {
		if (!host) return;
		const style = document.createElement('style');
		style.textContent = `@scope (.kb-card-host) {\n${rendered.extraCss}\n}`;
		// eslint-disable-next-line svelte/no-dom-manipulating -- style text is set via textContent, never parsed as HTML
		host.prepend(style);
		return () => style.remove();
	});

	// Audio placeholders are `<button data-kb-audio="<sha256>">`; clicking plays the blob.
	function play(hash: string): void {
		current?.pause();
		current = new Audio(mediaUrlTemplate.replace('{sha256}', hash));
		void current.play().catch(() => {});
	}

	function onClick(e: MouseEvent): void {
		const el = (e.target as Element | null)?.closest('[data-kb-audio]');
		const hash = el?.getAttribute('data-kb-audio');
		if (hash) play(hash);
	}

	/** Play the first audio of the card (used by the review screen's `p` shortcut and autoplay). */
	export function playFirst(): void {
		const first = rendered.audio[0];
		if (first) play(first);
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="kb-card-host" class:no-furigana={!furigana} bind:this={host} onclick={onClick}>
	<!-- html comes from renderCard, which sanitises it (allow-list of elements, attributes, classes) -->
	<!-- eslint-disable-next-line svelte/no-at-html-tags -->
	{@html rendered.html}
</div>

<style>
	.no-furigana :global(rt) {
		display: none;
	}
</style>
