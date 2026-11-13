# Grammar rule language

A grammar point's `detection_rules` (`app.grammar_point.detection_rules`,
`[{"rule": "<text>", "draft": false}]`) hold rules in this language. A rule is a token pattern
over the parser's tokens of one sentence (Sudachi, `contracts/api/PARSER.md` 1: `surface`,
`lemma`, `reading`, `pos`, `cform`). The single implementation is `grammar/dsl.py` (parser) and
`grammar/matcher.py` (matcher). This file is not a contract; it documents that implementation.

```
[verb:未然形] ざる を 得 ない
```

## Elements

Elements are separated by whitespace. Each element matches tokens in order.

| Element | Matches |
|---|---|
| `ない` (bare word) | one token whose `surface` **or** `lemma` equals the word |
| `"にもかかわらず"` (quoted) | one or more **adjacent** tokens whose surfaces, concatenated, equal the text. Use it where Sudachi may or may not split a phrase. `\"` and `\\` escape. |
| `[…]` (token test) | one token passing every test inside the brackets (below) |
| `*` | any one token |
| `…` or `...` (gap) | 0 to 5 tokens of anything (lazy: as few as possible) |
| `( a b \| c )` | a group; `\|` separates alternatives, tried left to right |

`|` also works at the top level: `ざるを得ない | ずにはいられない`-style rules need no parentheses.

## Token tests (`[…]`)

Comma-separated; all must hold.

- First item may be positional: `POS` or `POS:CFORM`, e.g. `[verb]`, `[verb:未然形]`,
  `[*:連用形]` (any POS), `[助詞-接続助詞]`.
- Then (or instead) `key=value` or `key!=value`, keys `surface`, `lemma`, `word`
  (surface or lemma), `reading` (kana; katakana and hiragana are the same), `pos`, `cform`.
  E.g. `[verb:連用形, lemma=得る]`, `[aux, lemma!=た]`.
- Any value may list alternatives with `/`: `[verb/adj:連用形]`, `[lemma=ほど/くらい]`.

**POS** is an English alias or Sudachi's own levels joined by `-`; it matches when the token's
POS starts with those levels (`助詞` matches `助詞-格助詞`). Aliases: `verb` 動詞, `adj` 形容詞,
`na-adj` / `adjna` 形状詞, `noun` 名詞, `pron` 代名詞, `particle` 助詞, `aux` 助動詞,
`adv` 副詞, `adnominal` 連体詞, `conjunction` 接続詞, `interj` 感動詞, `prefix` 接頭辞,
`suffix` 接尾辞, `symbol` 記号.

**CFORM** is an alias or Sudachi's conjugation form; like POS it matches on leading levels
(`未然形` matches `未然形-一般` and `未然形-撥音便`). Aliases: `mizen` / `neg` 未然形,
`renyou` / `masu` 連用形, `shuushi` / `plain` 終止形, `rentai` 連体形, `katei` / `ba` 仮定形,
`meirei` / `imp` 命令形, `ishi` / `vol` 意志推量形, `stem` 語幹. A token that does not
inflect (`cform` null) never passes a CFORM test.

## Quantifiers

After any element or group: `?` (optional), `{n}`, `{m,n}` (0 ≤ m ≤ n, 1 ≤ n ≤ 10). They are
greedy (as many as possible) except on `*`, where `*{m,n}` is a lazy gap like `…`.

## Matching

- Comparison is exact after NFKC on both sides (full-width ASCII = ASCII).
- Punctuation and spaces are not tokens, so they are invisible to bare words and token tests;
  a quoted text only matches tokens with nothing between them.
- A sentence is scanned left to right; at each start token the first match in priority order
  (alternatives left to right, greedy/lazy as above) wins; the next search starts after it, so one
  rule's matches in a sentence never overlap.
- The span is the UTF-16 range from the first matched token's start to the last one's end.

## Errors

Invalid rules raise `GrammarRuleError` with a 1-based column (code points), e.g.
`column 2: unknown part of speech 'verbb'`. Also rejected: empty rules or alternatives,
unbalanced `( ) [ ] "`, a quantifier on a gap or after another quantifier, a rule that can match
zero tokens, and a rule that starts or ends with a gap (`*`, `…`), which would only widen spans.
