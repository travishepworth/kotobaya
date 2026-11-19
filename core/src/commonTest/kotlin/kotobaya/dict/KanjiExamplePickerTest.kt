package kotobaya.dict

import kotobaya.contracts.WordStatus
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.dict.KanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.dict.KanjiReadingType.KUN
import kotobaya.contracts.dict.KanjiReadingType.ON
import kotobaya.contracts.dict.KanjiReadingType.OTHER
import kotobaya.contracts.dict.KanjiReadings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * C-25 goldens of [DefaultKanjiExamplePicker], run on jvm and js (commonTest): the expected values are literals, so
 * both platforms are held to the same results. Readings are KANJIDIC's.
 */
class KanjiExamplePickerTest {
    private val picker: KanjiExamplePicker = DefaultKanjiExamplePicker

    private fun kanji(literal: String, on: String, kun: String) =
        KanjiReadings(literal, on.split(' ').filter { it.isNotEmpty() }, kun.split(' ').filter { it.isNotEmpty() })

    private val shoku = kanji("食", "ショク ジキ", "く.う く.らう た.べる は.む")
    private val sei = kanji(
        "生",
        "セイ ショウ",
        "い.きる い.かす い.ける う.まれる うま.れる う.まれ うまれ う.む お.う は.える は.やす き なま なま- な.る な.す む.す -う",
    )
    private val nichi = kanji("日", "ニチ ジツ", "ひ -び -か")
    private val jin = kanji("人", "ジン ニン", "ひと -り -と")
    private val dai = kanji("大", "ダイ タイ", "おお- おお.きい -おお.いに")
    private val kou = kanji("行", "コウ ギョウ アン", "い.く ゆ.く -ゆ.き -ゆき -い.き -いき おこな.う おこ.なう")
    private val jou = kanji(
        "上",
        "ジョウ ショウ シャン",
        "うえ -うえ うわ- かみ あ.げる -あ.げる あ.がる -あ.がる あ.がり -あ.がり のぼ.る のぼ.り のぼ.せる のぼ.す たてまつ.る",
    )
    private val shu = kanji("取", "シュ", "と.る と.り と.り- とり -ど.り")
    private val go = kanji("語", "ゴ", "かた.る かた.らう")
    private val gaku = kanji("学", "ガク", "まな.ぶ")
    private val ichi = kanji("一", "イチ イツ", "ひと- ひと.つ")
    private val te = kanji("手", "シュ ズ", "て て- -て た-")
    private val ji = kanji("時", "ジ", "とき -どき")
    private val ima = kanji("今", "コン キン", "いま")
    private val ju = kanji("受", "ジュ", "う.ける -う.け う.かる")

    /** kanji → (headword, reading, expected type). */
    internal val goldens: List<Pair<KanjiReadings, List<Triple<String, String, KanjiReadingType>>>> = listOf(
        shoku to listOf(
            Triple("食べる", "たべる", KUN), Triple("食べ物", "たべもの", KUN), Triple("食う", "くう", KUN),
            Triple("食事", "しょくじ", ON), Triple("食堂", "しょくどう", ON), Triple("食品", "しょくひん", ON),
            Triple("朝食", "ちょうしょく", ON), Triple("食器", "しょっき", ON), Triple("断食", "だんじき", ON),
        ),
        sei to listOf(
            Triple("生きる", "いきる", KUN), Triple("生まれる", "うまれる", KUN), Triple("生", "なま", KUN),
            Triple("生ビール", "なまビール", KUN), Triple("生地", "きじ", KUN), Triple("生える", "はえる", KUN),
            Triple("生活", "せいかつ", ON), Triple("先生", "せんせい", ON), Triple("学生", "がくせい", ON),
            Triple("一生", "いっしょう", ON), Triple("誕生日", "たんじょうび", ON), Triple("生憎", "あいにく", OTHER),
        ),
        nichi to listOf(
            Triple("日", "ひ", KUN), Triple("二日", "ふつか", KUN), Triple("誕生日", "たんじょうび", KUN), Triple("日々", "ひび", KUN),
            Triple("日曜日", "にちようび", ON), Triple("毎日", "まいにち", ON), Triple("休日", "きゅうじつ", ON), Triple("日記", "にっき", ON),
            Triple("今日", "きょう", OTHER), Triple("明日", "あした", OTHER), Triple("昨日", "きのう", OTHER), Triple("日本", "にほん", OTHER),
        ),
        jin to listOf(
            Triple("人", "ひと", KUN), Triple("一人", "ひとり", KUN), Triple("人々", "ひとびと", KUN), Triple("恋人", "こいびと", KUN),
            Triple("日本人", "にほんじん", ON), Triple("人間", "にんげん", ON), Triple("大人", "おとな", OTHER),
        ),
        dai to listOf(
            Triple("大きい", "おおきい", KUN), Triple("大阪", "おおさか", KUN), Triple("大学", "だいがく", ON),
            Triple("大変", "たいへん", ON), Triple("大丈夫", "だいじょうぶ", ON), Triple("大人", "おとな", OTHER),
        ),
        kou to listOf(
            Triple("行く", "いく", KUN), Triple("行う", "おこなう", KUN), Triple("行き先", "いきさき", KUN), Triple("行方", "ゆくえ", KUN),
            Triple("銀行", "ぎんこう", ON), Triple("旅行", "りょこう", ON), Triple("行動", "こうどう", ON), Triple("行列", "ぎょうれつ", ON),
        ),
        jou to listOf(
            Triple("上", "うえ", KUN), Triple("上げる", "あげる", KUN), Triple("上着", "うわぎ", KUN), Triple("上る", "のぼる", KUN),
            Triple("年上", "としうえ", KUN), Triple("上手", "じょうず", ON), Triple("以上", "いじょう", ON),
        ),
        shu to listOf(
            Triple("取る", "とる", KUN), Triple("取引", "とりひき", KUN), Triple("受け取る", "うけとる", KUN), Triple("取材", "しゅざい", ON),
        ),
        go to listOf(
            Triple("語る", "かたる", KUN), Triple("物語", "ものがたり", KUN), Triple("日本語", "にほんご", ON), Triple("単語", "たんご", ON),
        ),
        gaku to listOf(
            Triple("学ぶ", "まなぶ", KUN), Triple("学校", "がっこう", ON), Triple("学生", "がくせい", ON), Triple("科学", "かがく", ON),
        ),
        ichi to listOf(
            Triple("一つ", "ひとつ", KUN), Triple("一人", "ひとり", KUN), Triple("一番", "いちばん", ON), Triple("一緒", "いっしょ", ON),
            Triple("唯一", "ゆいいつ", ON), Triple("一日", "いちにち", ON), Triple("一日", "ついたち", OTHER),
        ),
        te to listOf(
            Triple("手", "て", KUN), Triple("手紙", "てがみ", KUN), Triple("手伝う", "てつだう", KUN),
            Triple("上手", "じょうず", ON), Triple("選手", "せんしゅ", ON),
        ),
        ji to listOf(
            Triple("時", "とき", KUN), Triple("時々", "ときどき", KUN), Triple("時間", "じかん", ON), Triple("同時", "どうじ", ON),
            Triple("時計", "とけい", OTHER),
        ),
        ima to listOf(
            Triple("今", "いま", KUN), Triple("今度", "こんど", ON), Triple("今日", "きょう", OTHER), Triple("今年", "ことし", OTHER),
            Triple("今朝", "けさ", OTHER),
        ),
        ju to listOf(
            Triple("受ける", "うける", KUN), Triple("受付", "うけつけ", KUN), Triple("受験", "じゅけん", ON),
        ),
    )

    @Test
    fun classifyGoldens() {
        val got = goldens.flatMap { (k, words) -> words.map { (h, r, _) -> "${k.literal} $h $r " + picker.classify(k, h, r) } }
        val want = goldens.flatMap { (k, words) -> words.map { (h, r, t) -> "${k.literal} $h $r $t" } }
        assertEquals(want.joinToString("\n"), got.joinToString("\n"))
    }

    @Test
    fun classifyEdges() {
        // no kanji in the headword, empty input, readings missing
        assertEquals(OTHER, picker.classify(shoku, "たべる", "たべる"))
        assertEquals(OTHER, picker.classify(shoku, "", ""))
        assertEquals(OTHER, picker.classify(shoku, "食べる", ""))
        assertEquals(OTHER, picker.classify(kanji("食", "", ""), "食べる", "たべる"))
        // katakana in the stored reading and in the word's reading are folded
        assertEquals(ON, picker.classify(shoku, "食事", "ショクジ"))
        assertEquals(KUN, picker.classify(kanji("食", "", "タ.ベル"), "食べる", "たべる"))
        // the okurigana written in the headword must be in the reading
        assertEquals(OTHER, picker.classify(shoku, "食べる", "たへる"))
        // a neighbour's reading cannot start with a small kana, ん, っ or ー: し + ょたい is no alignment
        assertEquals(OTHER, picker.classify(kanji("死", "シ", "し.ぬ"), "死体", "しょたい"))
        assertEquals(ON, picker.classify(kanji("死", "シ", "し.ぬ"), "死体", "したい"))
        // every other kanji takes at least one kana
        assertEquals(OTHER, picker.classify(nichi, "日本語", "にち"))
        // no gemination at the end of a word, no rendaku at its start
        assertEquals(OTHER, picker.classify(gaku, "大学", "だいがっ"))
        assertEquals(OTHER, picker.classify(kanji("語", "", "かた.る"), "語り", "がたり"))
        // an ON and a KUN form of the same length: ON
        assertEquals(ON, picker.classify(kanji("仮", "カ", "か.りる"), "仮名", "かな"))
        assertEquals(ON, picker.classify(kanji("仮", "カ", "か.りる").let { it.copy(onReadings = it.onReadings.reversed()) }, "仮名", "かな"))
        // a kanji outside the BMP is one code point
        assertEquals(KUN, picker.classify(kanji("𠮟", "シツ", "しか.る"), "𠮟る", "しかる"))
        assertEquals(ON, picker.classify(kanji("𠮟", "シツ", "しか.る"), "𠮟責", "しっせき"))
        // a prefix marker and an empty stem are harmless
        assertEquals(OTHER, picker.classify(kanji("食", "-", ". .る -"), "食べる", "たべる"))
    }

    private fun c(
        seq: Long,
        headword: String,
        reading: String,
        rank: Int?,
        status: WordStatus = WordStatus.NEW,
        note: Boolean = false,
        decks: Boolean = false,
    ) = KanjiExampleCandidate(seq, headword, reading, rank, status, note, decks)

    private fun pick(k: KanjiReadings, cs: List<KanjiExampleCandidate>, limit: Int = 3) =
        picker.pick(k, cs, limit).map { "${it.candidate.headword}:${it.readingType.wire}" }

    private fun pickSeqs(k: KanjiReadings, cs: List<KanjiExampleCandidate>) =
        picker.pick(k, cs, 3).map { "${it.candidate.seq}:${it.readingType.wire}" }

    private val taberu = c(1358280, "食べる", "たべる", 312)
    private val shokuji = c(1358500, "食事", "しょくじ", 480)
    private val shokudou = c(1358600, "食堂", "しょくどう", 1500)
    private val shokuhin = c(1358700, "食品", "しょくひん", 900)

    @Test
    fun pickTheContractExample() {
        val mine = shokudou.copy(hasNote = true)
        assertEquals(listOf("食堂:on", "食べる:kun", "食事:on"), pick(shoku, listOf(taberu, shokuji, mine, shokuhin)))
        // input order does not matter
        assertEquals(listOf("食堂:on", "食べる:kun", "食事:on"), pick(shoku, listOf(shokuhin, mine, shokuji, taberu)))
        // nobody's words: frequency only
        assertEquals(listOf("食べる:kun", "食事:on", "食品:on"), pick(shoku, listOf(taberu, shokuji, shokudou, shokuhin)))
    }

    @Test
    fun pickKeepsOneOnAndOneKunEvenWhenTheyRankLow() {
        val on = listOf(shokuji, shokuhin, shokudou, c(1358800, "食欲", "しょくよく", 2000))
        val kun = c(1358900, "食う", "くう", 9000)
        assertEquals(listOf("食事:on", "食品:on", "食う:kun"), pick(shoku, on + kun))
        // the KUN words rank first: the best ON word still gets a place
        val kuns = listOf(taberu, c(2, "食べ物", "たべもの", 400), c(3, "食う", "くう", 500), c(4, "食べ過ぎ", "たべすぎ", 600))
        assertEquals(listOf("食べる:kun", "食べ物:kun", "食堂:on"), pick(shoku, kuns + shokudou))
        // only ON words exist: the top three
        assertEquals(listOf("食事:on", "食品:on", "食堂:on"), pick(shoku, on))
        // OTHER words fill but are never forced
        val words = listOf(c(5, "今日", "きょう", 10), c(6, "日", "ひ", 20), c(7, "毎日", "まいにち", 30), c(8, "日曜日", "にちようび", 40))
        assertEquals(listOf("今日:other", "日:kun", "毎日:on"), pick(nichi, words))
    }

    @Test
    fun pickOrderTiersRanksAndSeq() {
        val learning = c(10, "食堂", "しょくどう", null, status = WordStatus.LEARNING)
        val known = c(11, "食品", "しょくひん", 5000, status = WordStatus.KNOWN)
        val never = c(12, "食欲", "しょくよく", 4000, status = WordStatus.NEVER_FORGET)
        val inDeck = c(13, "食器", "しょっき", 4500, decks = true)
        // mine first (by rank, unranked last), then the rest by rank
        assertEquals(
            listOf("食欲:on", "食器:on", "食品:on", "食堂:on", "食べる:kun", "食事:on"),
            pick(shoku, listOf(taberu, shokuji, learning, known, never, inDeck), limit = 6),
        )
        // equal rank and both unranked: seq ascending
        val ties = listOf(c(4, "食欲", "しょくよく", null), c(3, "食堂", "しょくどう", null), c(2, "食品", "しょくひん", 7), c(1, "食事", "しょくじ", 7))
        assertEquals(listOf("食事:on", "食品:on", "食堂:on", "食欲:on"), pick(shoku, ties, limit = 4))
    }

    @Test
    fun pickDropsIneligibleCandidates() {
        val blacklisted = c(20, "食う", "くう", 1, status = WordStatus.BLACKLISTED, note = true)
        val ignored = c(21, "食らう", "くらう", 2, status = WordStatus.IGNORED, decks = true)
        val variant = c(22, "喰らう", "くらう", 3) // an entry indexed under 食 whose headword is another form
        val duplicate = taberu.copy(freqRank = 1, headword = "食べる (dup)")
        assertEquals(listOf("食べる:kun", "食事:on"), pick(shoku, listOf(blacklisted, ignored, variant, taberu, duplicate, shokuji)))
        assertEquals(emptyList(), pick(shoku, listOf(blacklisted, ignored, variant)))
        assertEquals(emptyList(), pick(shoku, emptyList()))
    }

    @Test
    fun pickLimits() {
        val all = listOf(taberu, shokuji, shokudou, shokuhin)
        assertEquals(emptyList(), pick(shoku, all, limit = 0))
        assertEquals(emptyList(), pick(shoku, all, limit = -1))
        // the ON word is taken first, then the KUN word
        assertEquals(listOf("食事:on"), pick(shoku, all, limit = 1))
        assertEquals(listOf("食べる:kun", "食事:on"), pick(shoku, all, limit = 2))
        assertEquals(listOf("食べる:kun", "食事:on", "食品:on", "食堂:on"), pick(shoku, all, limit = 10))
        assertEquals(3, KanjiExamplePicker.DEFAULT_LIMIT)
        assertEquals(listOf("食べる:kun", "食事:on", "食品:on"), picker.pick(shoku, all).map { "${it.candidate.headword}:${it.readingType.wire}" })
    }

    @Test
    fun pickLooksOnlyAtTheWindow() {
        // 50 ON words, then a KUN word: outside the window, not found; as the 50th it is.
        val ons = (1..50).map { c(1000L + it, "食事", "しょくじ", it) }
        val kun = c(5000, "食べる", "たべる", 51)
        assertEquals(50, KanjiExamplePicker.CANDIDATE_LIMIT)
        assertEquals(listOf("1001:on", "1002:on", "1003:on"), pickSeqs(shoku, ons + kun))
        assertEquals(listOf("1001:on", "1002:on", "5000:kun"), pickSeqs(shoku, ons.take(49) + kun))
        // ineligible candidates do not use up the window
        val blocked = (1..50).map { c(2000L + it, "食事", "しょくじ", it, status = WordStatus.IGNORED) }
        assertEquals(listOf("5000:kun"), pickSeqs(shoku, blocked + kun))
        // a host that pre-limits to the window gets the same answer as one passing everything
        val many = (1..200).map {
            val isKun = it % 7 == 0
            c(3000L + it, if (isKun) "食べる" else "食事", if (isKun) "たべる" else "しょくじ", 201 - it, decks = it % 5 == 0)
        }
        val order = compareBy<KanjiExampleCandidate>({ if (it.inDecks) 0 else 1 }, { it.freqRank }, { it.seq })
        val limited = many.sortedWith(order).take(50).shuffled(Random(7))
        assertEquals(pickSeqs(shoku, many), pickSeqs(shoku, limited))
        assertEquals(listOf("3200:on", "3195:on", "3175:kun"), pickSeqs(shoku, many))
    }
}
