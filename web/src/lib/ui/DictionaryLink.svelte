<script lang="ts">
	// "Open in dictionary" for anything that shows a word or kanji (FEAT-DICT). Goes to the entry when its seq is
	// known, else to a dictionary search for the kanji literal; renders nothing when neither is known.
	import Button from './Button.svelte';
	import Icon from './Icon.svelte';
	import IconButton from './IconButton.svelte';
	import { ut } from './messages';
	import { dictionaryLinkFor } from '../state/dictionaryLink';

	interface Props {
		seq?: number | null;
		kanji?: string | null;
		/** The word or kanji as shown, for the accessible name of the icon form ("Open 取る in the dictionary"). */
		text?: string;
		/** icon: a round icon link for dense rows. button: an outline button. text: a quiet inline link. */
		look?: 'icon' | 'button' | 'text';
		/** Open in a new tab (review: the session stays where it is). */
		newTab?: boolean;
		full?: boolean;
	}
	let { seq = null, kanji = null, text, look = 'icon', newTab = false, full = false }: Props = $props();

	const href = $derived(dictionaryLinkFor({ entrySeq: seq, kanjiLiteral: kanji }));
	const label = $derived(text ? ut('ui.openInDictionaryFor', { text }) : ut('ui.openInDictionary'));
</script>

{#if href}
	{#if look === 'icon'}
		<IconButton icon="dictionary" {href} {label} target={newTab ? '_blank' : undefined} />
	{:else if look === 'button' && !newTab}
		<Button variant="outline" icon="dictionary" {href} {full}>{ut('ui.openInDictionary')}</Button>
	{:else}
		<a class="dl" {href} target={newTab ? '_blank' : undefined} rel={newTab ? 'noopener' : undefined} aria-label={text ? label : undefined}>
			<Icon name="dictionary" size={16} />
			<span>{ut('ui.openInDictionary')}</span>
		</a>
	{/if}
{/if}

<style>
	.dl {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-md);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-link);
		text-decoration: none;
	}
	.dl:hover {
		text-decoration: underline;
	}
	.dl:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
</style>
