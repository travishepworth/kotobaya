package kotobaya.render

/** Expected output of one golden case. */
data class Golden(val html: String, val audio: List<String>, val warnings: List<String>)

/**
 * Golden snapshots: every built-in note type x card kind x side for [RenderFixtures.NOTES] with
 * [RenderFixtures.OPTIONS]. Common test code, so the jvm and js test runs assert the same strings.
 * Update deliberately when a built-in template changes, never just to make a failing test pass.
 */
val RENDER_GOLDENS: Map<String, Golden> = mapOf(
        "vocab/meaning/front" to Golden(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div></div>""",
            listOf(),
            listOf(),
        ),
        "vocab/meaning/back" to Golden(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-back"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div><hr class="kb-divider"><div class="kb-reading"><span class="kb-field-reading"><ruby>食<rt>た</rt></ruby>べ<ruby>物<rt>もの</rt></ruby></span></div><div class="kb-reading"><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high">べ</span><span class="kb-mora kb-pitch-high kb-pitch-drop">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high kb-pitch-drop">べ</span><span class="kb-mora kb-pitch-low">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span></div><div class="kb-meaning"><span class="kb-field-meaning">food; <i>something to eat</i></span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-translation"><span class="kb-field-sentence_translation">Tasty food</span></div><span class="kb-field-sentence_audio"><button class="kb-audio" data-kb-audio="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"></button></span><div class="kb-notes"><span class="kb-field-notes"><b>common</b> word</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            listOf(),
        ),
        "vocab/reading/front" to Golden(
            """<div class="kb-card kb-vocab kb-kind-reading kb-front"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div></div>""",
            listOf(),
            listOf(),
        ),
        "vocab/reading/back" to Golden(
            """<div class="kb-card kb-vocab kb-kind-reading kb-back"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div><hr class="kb-divider"><div class="kb-reading"><span class="kb-field-reading"><ruby>食<rt>た</rt></ruby>べ<ruby>物<rt>もの</rt></ruby></span></div><div class="kb-reading"><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high">べ</span><span class="kb-mora kb-pitch-high kb-pitch-drop">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high kb-pitch-drop">べ</span><span class="kb-mora kb-pitch-low">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span></div><div class="kb-meaning"><span class="kb-field-meaning">food; <i>something to eat</i></span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-translation"><span class="kb-field-sentence_translation">Tasty food</span></div><span class="kb-field-sentence_audio"><button class="kb-audio" data-kb-audio="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"></button></span><div class="kb-notes"><span class="kb-field-notes"><b>common</b> word</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            listOf(),
        ),
        "vocab/listening/front" to Golden(
            """<div class="kb-card kb-vocab kb-kind-listening kb-front"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "vocab/listening/back" to Golden(
            """<div class="kb-card kb-vocab kb-kind-listening kb-back"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div><hr class="kb-divider"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-reading"><span class="kb-field-reading"><ruby>食<rt>た</rt></ruby>べ<ruby>物<rt>もの</rt></ruby></span></div><div class="kb-reading"><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high">べ</span><span class="kb-mora kb-pitch-high kb-pitch-drop">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high kb-pitch-drop">べ</span><span class="kb-mora kb-pitch-low">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span></div><div class="kb-meaning"><span class="kb-field-meaning">food; <i>something to eat</i></span></div><div class="kb-translation"><span class="kb-field-sentence_translation">Tasty food</span></div><span class="kb-field-sentence_audio"><button class="kb-audio" data-kb-audio="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"></button></span><div class="kb-notes"><span class="kb-field-notes"><b>common</b> word</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            listOf(),
        ),
        "vocab/production/front" to Golden(
            """<div class="kb-card kb-vocab kb-kind-production kb-front"><div class="kb-meaning"><span class="kb-field-meaning">food; <i>something to eat</i></span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div></div>""",
            listOf(),
            listOf(),
        ),
        "vocab/production/back" to Golden(
            """<div class="kb-card kb-vocab kb-kind-production kb-back"><div class="kb-meaning"><span class="kb-field-meaning">food; <i>something to eat</i></span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div><hr class="kb-divider"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-reading"><span class="kb-field-reading"><ruby>食<rt>た</rt></ruby>べ<ruby>物<rt>もの</rt></ruby></span></div><div class="kb-reading"><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high">べ</span><span class="kb-mora kb-pitch-high kb-pitch-drop">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high kb-pitch-drop">べ</span><span class="kb-mora kb-pitch-low">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-translation"><span class="kb-field-sentence_translation">Tasty food</span></div><span class="kb-field-sentence_audio"><button class="kb-audio" data-kb-audio="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"></button></span><div class="kb-notes"><span class="kb-field-notes"><b>common</b> word</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            listOf(),
        ),
        "vocab/pitch/front" to Golden(
            """<div class="kb-card kb-vocab kb-kind-pitch kb-front"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-reading"><span class="kb-field-reading">たべもの</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div></div>""",
            listOf(),
            listOf(),
        ),
        "vocab/pitch/back" to Golden(
            """<div class="kb-card kb-vocab kb-kind-pitch kb-back"><div class="kb-expression"><span class="kb-field-expression">食べ物</span></div><div class="kb-reading"><span class="kb-field-reading">たべもの</span></div><div class="kb-sentence"><span class="kb-field-sentence">美味しい食べ物</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="food"></span></div><hr class="kb-divider"><div class="kb-reading"><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high">べ</span><span class="kb-mora kb-pitch-high kb-pitch-drop">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span><span class="kb-pitch"><span class="kb-mora kb-pitch-low">た</span><span class="kb-mora kb-pitch-high kb-pitch-drop">べ</span><span class="kb-mora kb-pitch-low">も</span><span class="kb-mora kb-pitch-low">の</span><small>nakadaka</small></span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-translation"><span class="kb-field-sentence_translation">Tasty food</span></div><span class="kb-field-sentence_audio"><button class="kb-audio" data-kb-audio="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"></button></span><div class="kb-notes"><span class="kb-field-notes"><b>common</b> word</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            listOf(),
        ),
        "kanji/keyword/front" to Golden(
            """<div class="kb-card kb-kanji kb-kind-keyword kb-front"><div class="kb-literal"><span class="kb-field-literal">食</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "kanji/keyword/back" to Golden(
            """<div class="kb-card kb-kanji kb-kind-keyword kb-back"><div class="kb-literal"><span class="kb-field-literal">食</span></div><hr class="kb-divider"><div class="kb-keyword"><span class="kb-field-keyword">eat; food</span></div><div class="kb-reading"><span class="kb-field-readings">ショク、ジキ、た.べる、く.う</span></div><div class="kb-notes"><span class="kb-field-notes">人 + 良</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "kanji/writing/front" to Golden(
            """<div class="kb-card kb-kanji kb-kind-writing kb-front"><div class="kb-keyword"><span class="kb-field-keyword">eat; food</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "kanji/writing/back" to Golden(
            """<div class="kb-card kb-kanji kb-kind-writing kb-back"><div class="kb-keyword"><span class="kb-field-keyword">eat; food</span></div><hr class="kb-divider"><div class="kb-literal"><span class="kb-field-literal">食</span></div><div class="kb-reading"><span class="kb-field-readings">ショク、ジキ、た.べる、く.う</span></div><div class="kb-notes"><span class="kb-field-notes">人 + 良</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "kanji/components/front" to Golden(
            """<div class="kb-card kb-kanji kb-kind-components kb-front"><div class="kb-literal"><span class="kb-field-literal">食</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "kanji/components/back" to Golden(
            """<div class="kb-card kb-kanji kb-kind-components kb-back"><div class="kb-literal"><span class="kb-field-literal">食</span></div><hr class="kb-divider"><div class="kb-notes"><span class="kb-field-notes">人 + 良</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "phrase/meaning/front" to Golden(
            """<div class="kb-card kb-phrase kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression">お疲れ様</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "phrase/meaning/back" to Golden(
            """<div class="kb-card kb-phrase kb-kind-meaning kb-back"><div class="kb-expression"><span class="kb-field-expression">お疲れ様</span></div><hr class="kb-divider"><div class="kb-reading"><span class="kb-field-reading">お<ruby>疲<rt>つか</rt></ruby>れ<ruby>様<rt>さま</rt></ruby></span></div><div class="kb-meaning"><span class="kb-field-meaning">thanks for your hard work</span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "phrase/production/front" to Golden(
            """<div class="kb-card kb-phrase kb-kind-production kb-front"><div class="kb-meaning"><span class="kb-field-meaning">thanks for your hard work</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "phrase/production/back" to Golden(
            """<div class="kb-card kb-phrase kb-kind-production kb-back"><div class="kb-meaning"><span class="kb-field-meaning">thanks for your hard work</span></div><hr class="kb-divider"><div class="kb-expression"><span class="kb-field-expression">お疲れ様</span></div><div class="kb-reading"><span class="kb-field-reading">お<ruby>疲<rt>つか</rt></ruby>れ<ruby>様<rt>さま</rt></ruby></span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "phrase/listening/front" to Golden(
            """<div class="kb-card kb-phrase kb-kind-listening kb-front"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "phrase/listening/back" to Golden(
            """<div class="kb-card kb-phrase kb-kind-listening kb-back"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><hr class="kb-divider"><div class="kb-expression"><span class="kb-field-expression">お疲れ様</span></div><div class="kb-reading"><span class="kb-field-reading">お<ruby>疲<rt>つか</rt></ruby>れ<ruby>様<rt>さま</rt></ruby></span></div><div class="kb-meaning"><span class="kb-field-meaning">thanks for your hard work</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "grammar/cloze/front" to Golden(
            """<div class="kb-card kb-grammar kb-kind-cloze kb-front"><div class="kb-sentence kb-cloze">行か<span class="kb-cloze-blank">＿＿＿</span>。</div><div class="kb-hint">I have no choice but to go.</div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/cloze/back" to Golden(
            """<div class="kb-card kb-grammar kb-kind-cloze kb-back"><div class="kb-sentence kb-cloze">行か<span class="kb-cloze-answer">ざるを得ない</span>。</div><hr class="kb-divider"><div class="kb-pattern"><span class="kb-field-pattern">〜ざるを得ない</span></div><div class="kb-meaning"><span class="kb-field-meaning">cannot help but</span></div><div class="kb-translation">I have no choice but to go.</div><div class="kb-notes"><span class="kb-field-notes">Formal.</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/recognition/front" to Golden(
            """<div class="kb-card kb-grammar kb-kind-recognition kb-front"><div class="kb-pattern"><span class="kb-field-pattern">〜ざるを得ない</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/recognition/back" to Golden(
            """<div class="kb-card kb-grammar kb-kind-recognition kb-back"><div class="kb-pattern"><span class="kb-field-pattern">〜ざるを得ない</span></div><hr class="kb-divider"><div class="kb-meaning"><span class="kb-field-meaning">cannot help but</span></div><div class="kb-notes"><span class="kb-field-notes">Formal.</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/spot/front" to Golden(
            """<div class="kb-card kb-grammar kb-kind-spot kb-front"><div class="kb-sentence">行かざるを得ない。</div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/spot/back" to Golden(
            """<div class="kb-card kb-grammar kb-kind-spot kb-back"><div class="kb-sentence">行か<span class="kb-spot">ざるを得ない</span>。</div><hr class="kb-divider"><div class="kb-pattern"><span class="kb-field-pattern">〜ざるを得ない</span></div><div class="kb-meaning"><span class="kb-field-meaning">cannot help but</span></div><div class="kb-translation">I have no choice but to go.</div><div class="kb-notes"><span class="kb-field-notes">Formal.</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/production/front" to Golden(
            """<div class="kb-card kb-grammar kb-kind-production kb-front"><div class="kb-meaning"><span class="kb-field-meaning">cannot help but</span></div></div>""",
            listOf(),
            listOf(),
        ),
        "grammar/production/back" to Golden(
            """<div class="kb-card kb-grammar kb-kind-production kb-back"><div class="kb-meaning"><span class="kb-field-meaning">cannot help but</span></div><hr class="kb-divider"><div class="kb-pattern"><span class="kb-field-pattern">〜ざるを得ない</span></div><div class="kb-notes"><span class="kb-field-notes">Formal.</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
        "sentence/comprehension/front" to Golden(
            """<div class="kb-card kb-sentence kb-kind-comprehension kb-front"><div class="kb-sentence"><span class="kb-field-sentence">猫が好きです。</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"></span></div></div>""",
            listOf(),
            listOf(),
        ),
        "sentence/comprehension/back" to Golden(
            """<div class="kb-card kb-sentence kb-kind-comprehension kb-back"><div class="kb-sentence"><span class="kb-field-sentence">猫が好きです。</span></div><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"></span></div><hr class="kb-divider"><div class="kb-reading"><span class="kb-field-reading"><ruby>猫<rt>ねこ</rt></ruby>が<ruby>好<rt>す</rt></ruby>きです。</span></div><div class="kb-translation"><span class="kb-field-translation">I like cats.</span></div><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "sentence/listening/front" to Golden(
            """<div class="kb-card kb-sentence kb-kind-listening kb-front"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"></span></div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "sentence/listening/back" to Golden(
            """<div class="kb-card kb-sentence kb-kind-listening kb-back"><span class="kb-field-audio"><button class="kb-audio" data-kb-audio="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></button></span><div class="kb-image"><span class="kb-field-image"><img src="/api/v1/blobs/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"></span></div><hr class="kb-divider"><div class="kb-sentence"><span class="kb-field-sentence">猫が好きです。</span></div><div class="kb-reading"><span class="kb-field-reading"><ruby>猫<rt>ねこ</rt></ruby>が<ruby>好<rt>す</rt></ruby>きです。</span></div><div class="kb-translation"><span class="kb-field-translation">I like cats.</span></div><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf(),
        ),
        "custom/basic/front" to Golden(
            """<div class="kb-card kb-custom kb-kind-basic kb-front"><span class="kb-field-front">What is <b>2+2</b>?</span></div>""",
            listOf(),
            listOf(),
        ),
        "custom/basic/back" to Golden(
            """<div class="kb-card kb-custom kb-kind-basic kb-back"><span class="kb-field-front">What is <b>2+2</b>?</span><hr class="kb-divider"><span class="kb-field-back">4</span><div class="kb-mnemonic">食 = person + good<br>food is &lt;good&gt;</div></div>""",
            listOf(),
            listOf(),
        ),
)
