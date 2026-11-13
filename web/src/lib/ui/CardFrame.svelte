<script lang="ts">
	// Shows one RenderedCard from the shared core renderer (review, browser preview, note-type preview).
	// Furigana is a class switch, never a re-render. With `onfield`, the card's field regions (C-19
	// `data-field` wrappers, present when the state layer renders with fieldRegions) become clickable,
	// and regions listed in `overridden` / `activeField` are marked.
	import { MEDIA_URL_TEMPLATE, type RenderedCard } from '../state/preview.svelte';

	interface Props {
		rendered: RenderedCard;
		furigana?: boolean;
		/** Field-region click (FEAT-x WYSIWYG editing). */
		onfield?: (key: string) => void;
		/** Field keys whose value is the note's own override of reference data. */
		overridden?: string[];
		/** The field being edited (highlighted). */
		activeField?: string | null;
		/** Animate the part after the divider in (the review reveal). */
		reveal?: boolean;
	}
	let { rendered, furigana = true, onfield, overridden = [], activeField = null, reveal = false }: Props = $props();

	let host: HTMLDivElement | undefined = $state();
	let current: HTMLAudioElement | null = null;

	// The note type's CSS is user text: set it as text (never parsed as HTML) and scope it to this card
	// so it cannot restyle the page around it.
	$effect(() => {
		if (!host) return;
		const style = document.createElement('style');
		style.textContent = `@scope (.kb-card-host) {\n${rendered.extraCss}\n}`;
		// eslint-disable-next-line svelte/no-dom-manipulating -- style text is set via textContent, never parsed as HTML
		host.prepend(style);
		return () => style.remove();
	});

	// Mark overridden / active regions (attributes only; the sanitised HTML itself is never touched).
	$effect(() => {
		if (!host) return;
		void rendered.html;
		const marks = new Set(overridden);
		for (const el of host.querySelectorAll<HTMLElement>('[data-field]')) {
			const key = el.dataset.field ?? '';
			el.toggleAttribute('data-kb-override', marks.has(key));
			el.toggleAttribute('data-kb-active', key === activeField);
		}
	});

	function play(hash: string): void {
		current?.pause();
		current = new Audio(MEDIA_URL_TEMPLATE.replace('{sha256}', hash));
		void current.play().catch(() => {});
	}

	function onClick(e: MouseEvent): void {
		const target = e.target as Element | null;
		const audio = target?.closest('[data-kb-audio]')?.getAttribute('data-kb-audio');
		if (audio) {
			play(audio);
			return;
		}
		const field = target?.closest<HTMLElement>('[data-field]')?.dataset.field;
		if (field && onfield) onfield(field);
	}

	/** Play the first audio of the card (review `P` shortcut). */
	export function playFirst(): void {
		const first = rendered.audio[0];
		if (first) play(first);
	}
</script>

<!-- Keyboard users edit fields from the field list next to the preview; clicking a region is a shortcut. -->
<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div
	class="kb-card-host frame"
	class:no-furigana={!furigana}
	class:editable={!!onfield}
	class:reveal
	bind:this={host}
	onclick={onClick}
>
	<!-- html comes from renderCard, which sanitises it (allow-list of elements, attributes, classes) -->
	<!-- eslint-disable-next-line svelte/no-at-html-tags -->
	{@html rendered.html}
</div>

<style>
	.frame {
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.frame :global(.kb-card) {
		background: transparent;
		padding: 0;
	}
	.frame :global(.kb-expression),
	.frame :global(.kb-sentence),
	.frame :global(.kb-literal) {
		word-break: auto-phrase;
		overflow-wrap: normal;
	}
	.frame :global(ruby) {
		line-height: var(--kb-ruby-lh);
	}
	.frame :global(.kb-audio) {
		width: var(--kb-size-touch-min);
		height: var(--kb-size-touch-min);
	}
	.frame :global(.kb-audio:focus-visible) {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.no-furigana :global(rt) {
		display: none;
	}

	/* The reveal: the divider draws from the centre, the answer rises into place. */
	.reveal :global(.kb-divider) {
		animation: draw var(--kb-duration-fast) var(--kb-ease-standard) both;
	}
	.reveal :global(.kb-divider ~ *) {
		animation: rise var(--kb-duration-base) var(--kb-ease-emphasized) both;
	}
	@keyframes draw {
		from {
			transform: scaleX(0);
		}
	}
	@keyframes rise {
		from {
			opacity: 0;
			transform: translateY(var(--kb-distance-md));
		}
	}

	/* FEAT-x: field regions are targets. */
	.editable :global([data-field]) {
		border-radius: var(--kb-radius-xs);
		outline: 1px dashed transparent;
		outline-offset: 3px;
		cursor: pointer;
		transition: outline-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.editable :global([data-field]:hover) {
		outline-color: var(--kb-color-border-strong);
	}
	.editable :global([data-kb-override]) {
		outline: 1px dashed var(--kb-color-primary);
		background: color-mix(in oklab, var(--kb-color-primary-container) 60%, transparent);
	}
	.editable :global([data-kb-active]) {
		outline: 2px solid var(--kb-color-focus);
	}
</style>
