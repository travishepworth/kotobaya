package kotobaya.search

import kotobaya.contracts.search.SearchErrorCode

/**
 * Generated from contracts/core/search-examples.tsv. `canonical` is `check_search.py`'s `fmt(tree_of(query))`
 * (the reference printer); regenerate both lists when the tsv changes. Error codes of the `-` lines are
 * the ones their notes imply.
 */
internal object SearchExamples {
    val valid: List<Pair<String, String>> = listOf(
        "deck:\"Frieren\" is:due tag:leech lapses>3 kanji:\u7E3E rated:7:1 kind:reading" to "deck:Frieren is:due tag:leech lapses>3 kanji:\u7E3E rated:7:1 kind:reading",
        "is:leech" to "is:leech",
        "" to "",
        "   " to "",
        "\u98DF\u3079\u308B" to "\u98DF\u3079\u308B",
        "\"to eat\"" to "\"to eat\"",
        "\u65E5\u672C\u8A9E\u3000is:new" to "\u65E5\u672C\u8A9E is:new",
        "deck:Frieren* -is:suspended" to "deck:Frieren* -is:suspended",
        "deck:\"Frieren: Beyond Journey's End\"" to "deck:\"Frieren: Beyond Journey's End\"",
        "deck:Anime:S1" to "deck:Anime:S1",
        "(tag:anime or tag:drama) is:due" to "(tag:anime or tag:drama) is:due",
        "tag:anime OR tag:drama and is:new" to "tag:anime or tag:drama is:new",
        "-(is:new or is:suspended)" to "-(is:new or is:suspended)",
        "--is:new" to "--is:new",
        "field:meaning:*eat*" to "field:meaning:*eat*",
        "rated:1 OR added:3" to "rated:1 or added:3",
        "rated:36500:4" to "rated:36500:4",
        "interval<21 stability>10.5 difficulty<=9 due<=0 reps=0 lapses!=0" to "interval<21 stability>10.5 difficulty<=9 due<=0 reps=0 lapses!=0",
        "due>=-3" to "due>=-3",
        "type:vocab kind:listening status:known" to "type:vocab kind:listening status:known",
        "status:never_forget or status:blacklisted" to "status:never_forget or status:blacklisted",
        "nid:0193a1b2-0001-7000-8000-000000000001" to "nid:0193a1b2-0001-7000-8000-000000000001",
        "cid:0193a1b2-0002-7000-8000-000000000001 flag:3 is:flagged" to "cid:0193a1b2-0002-7000-8000-000000000001 flag:3 is:flagged",
        "entry:1358280" to "entry:1358280",
        "preset:\"Hard words\" notetype:\"My cards\"" to "preset:\"Hard words\" notetype:\"My cards\"",
        "kanji:\u7E3E or kanji:\u8CAC" to "kanji:\u7E3E or kanji:\u8CAC",
        "kanji:\uD842\uDF9F" to "kanji:\uD842\uDF9F",
        "tag:jlpt\\*" to "tag:jlpt\\*",
        "\"say \\\"hi\\\"\"" to "\"say \\\"hi\\\"\"",
        "Or" to "Or",
        "\"or\"" to "\"or\"",
        "a b c" to "a b c",
        "( is:due )" to "is:due",
        "is:review -is:buried is:hot is:relearning is:learning" to "is:review -is:buried is:hot is:relearning is:learning",
        "-(a b)" to "-(a b)",
        "-(a or b) c" to "-(a or b) c",
        "-(a b) or c" to "-(a b) or c",
        "(a or b) (c or d)" to "(a or b) (c or d)",
        "(a b) c" to "a b c",
        "x -(y -(z or w)) or -v" to "x -(y -(z or w)) or -v",
        "-(-a or b)" to "-(-a or b)",
        "\"or\" \"-a\" \"a:b\" \"a b\" OR \"x=y\"" to "\"or\" \"-a\" \"a:b\" \"a b\" or \"x=y\"",
        "deck:a\\(b\\) tag:\"x y\" field:reading:\"*\"" to "deck:a\\(b\\) tag:\"x y\" field:reading:*",
        "stability>10.50 due>=-0 difficulty<0.0000001" to "stability>10.5 due>=-0 difficulty<0.0000001",
    )

    val invalid: List<Pair<String, SearchErrorCode>> = listOf(
        "dek:Frieren" to SearchErrorCode.UNKNOWN_KEY,
        "is:newer" to SearchErrorCode.BAD_VALUE,
        "lapses>" to SearchErrorCode.MISSING_VALUE,
        "(is:due" to SearchErrorCode.UNBALANCED_PAREN,
        "is:due)" to SearchErrorCode.UNBALANCED_PAREN,
        "rated:0" to SearchErrorCode.BAD_VALUE,
        "rated:7:5" to SearchErrorCode.BAD_VALUE,
        "kanji:abc" to SearchErrorCode.BAD_VALUE,
        "flag:8" to SearchErrorCode.BAD_VALUE,
        "\"\"" to SearchErrorCode.BAD_QUOTE,
        "\"open" to SearchErrorCode.BAD_QUOTE,
        "- is:new" to SearchErrorCode.DANGLING_OPERATOR,
        "or" to SearchErrorCode.DANGLING_OPERATOR,
        "a or" to SearchErrorCode.DANGLING_OPERATOR,
        "and b" to SearchErrorCode.DANGLING_OPERATOR,
        "deck:" to SearchErrorCode.MISSING_VALUE,
        "a=b" to SearchErrorCode.UNKNOWN_KEY,
        "Deck:Frieren" to SearchErrorCode.UNKNOWN_KEY,
        "is:DUE" to SearchErrorCode.BAD_VALUE,
        "nid:0193A1B2-0001-7000-8000-000000000001" to SearchErrorCode.BAD_VALUE,
        "(a)(b)" to SearchErrorCode.UNBALANCED_PAREN,
        "type:verb" to SearchErrorCode.BAD_VALUE,
        "entry:0123" to SearchErrorCode.BAD_VALUE,
    )
}
