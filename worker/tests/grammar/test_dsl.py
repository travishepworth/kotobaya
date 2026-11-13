"""T506a: rule parser — valid rules, invalid rules with the column of the problem."""

from __future__ import annotations

import pytest

from kotobaya_worker.grammar.dsl import (
    Alt,
    AnyToken,
    GrammarRuleError,
    Literal,
    Repeat,
    Seq,
    TokenTest,
    parse_rule,
)


def test_spec_example_parses_to_token_tests() -> None:
    p = parse_rule("[verb:未然形] ざる を 得 ない")
    assert isinstance(p.root, Seq)
    first, *rest = p.root.items
    assert isinstance(first, TokenTest)
    assert [(c.field, c.values) for c in first.constraints] == [
        ("pos", (("動詞",),)),
        ("cform", (("未然形",),)),
    ]
    assert [c.constraints[0].values for c in rest if isinstance(c, TokenTest)] == [
        ("ざる",),
        ("を",),
        ("得",),
        ("ない",),
    ]
    assert all(c.constraints[0].field == "word" for c in rest if isinstance(c, TokenTest))


def test_japanese_pos_levels_and_keyed_tests() -> None:
    p = parse_rule("[助詞-接続助詞, lemma=て/で] [verb, lemma!=いる]")
    assert isinstance(p.root, Seq)
    a, b = p.root.items
    assert isinstance(a, TokenTest) and isinstance(b, TokenTest)
    assert a.constraints[0].values == (("助詞", "接続助詞"),)
    assert a.constraints[1].values == ("て", "で")
    assert b.constraints[1].negate


def test_any_pos_with_cform() -> None:
    p = parse_rule("[*:連用形] ながら")
    assert isinstance(p.root, Seq)
    t = p.root.items[0]
    assert isinstance(t, TokenTest)
    assert [c.field for c in t.constraints] == ["cform"]


def test_quoted_literal_and_escape() -> None:
    p = parse_rule('[verb] "にもかかわらず"')
    assert isinstance(p.root, Seq)
    assert p.root.items[1] == Literal("にもかかわらず", 8)
    q = parse_rule(r'"a\"b"')
    assert q.root == Literal('a"b', 1)


def test_gaps_and_quantifiers() -> None:
    for rule in ("ば … ほど", "ば ... ほど"):
        p = parse_rule(rule)
        assert isinstance(p.root, Seq)
        gap = p.root.items[1]
        assert isinstance(gap, Repeat) and isinstance(gap.inner, AnyToken)
        assert (gap.min, gap.max, gap.lazy) == (0, 5, True)
    p = parse_rule("ば *{1,3} ほど")
    assert isinstance(p.root, Seq)
    star = p.root.items[1]
    assert isinstance(star, Repeat) and (star.min, star.max, star.lazy) == (1, 3, True)
    p = parse_rule("て (い)? る{2}")
    assert isinstance(p.root, Seq)
    opt, two = p.root.items[1], p.root.items[2]
    assert isinstance(opt, Repeat) and (opt.min, opt.max, opt.lazy) == (0, 1, False)
    assert isinstance(two, Repeat) and (two.min, two.max) == (2, 2)


def test_alternatives_top_level_and_grouped() -> None:
    p = parse_rule("ざる を 得 ない | ず に は いられ ない")
    assert isinstance(p.root, Alt) and len(p.root.options) == 2
    q = parse_rule("(わけ | 訳) に は いか ない")
    assert isinstance(q.root, Seq)
    assert isinstance(q.root.items[0], Alt)


def test_english_aliases_for_pos_and_cform() -> None:
    p = parse_rule("[adj:renyou] て")
    assert isinstance(p.root, Seq)
    t = p.root.items[0]
    assert isinstance(t, TokenTest)
    assert [c.values for c in t.constraints] == [(("形容詞",),), (("連用形",),)]


@pytest.mark.parametrize(
    ("rule", "column", "fragment"),
    [
        ("", 1, "empty rule"),
        ("   ", 1, "empty rule"),
        ("[verbb] ない", 2, "unknown part of speech 'verbb'"),
        ("[verb:未然] ない", 7, "unknown conjugation form '未然'"),
        ("ざる [verb", 4, "unclosed '['"),
        ("ざる (を 得", 4, "unclosed '('"),
        ("ざる を) 得", 5, "unexpected ')'"),
        ('ざる "を 得', 4, "unclosed '\"'"),
        ("ざる | | ない", 6, "empty alternative"),
        ("(| ない)", 2, "empty alternative"),
        ("ない ??", 5, "quantifier cannot follow"),
        ("ない {3,1}", 4, "bounds"),
        ("ない {0}", 4, "bounds"),
        ("ない {1,20}", 4, "at most 10"),
        ("ない {a}", 4, "quantifier"),
        ("ざる … ? ない", 6, "gap"),
        ("… ない", 1, "cannot start with a gap"),
        ("ない *", 4, "cannot end with a gap"),
        ("ない?", 1, "can match no tokens"),
        ("[verb, colour=red]", 8, "unknown key 'colour'"),
        ("[verb, lemma=]", 8, "empty value"),
        ("[verb, lemma=a//b]", 8, "empty value"),
        ("[]", 1, "empty token test"),
        ("[verb, adj]", 8, "only the first item"),
        ("ない . ある", 4, "unexpected '.'"),
        ("? ない", 1, "quantifier without"),
        ("ない }", 4, "unexpected '}'"),
    ],
)
def test_invalid_rules_report_column(rule: str, column: int, fragment: str) -> None:
    with pytest.raises(GrammarRuleError) as ei:
        parse_rule(rule)
    assert ei.value.column == column, str(ei.value)
    assert fragment in ei.value.message, str(ei.value)
    assert str(ei.value).startswith(f"column {column}: ")


def test_overlong_rule_rejected() -> None:
    with pytest.raises(GrammarRuleError, match="too long"):
        parse_rule("ない " * 400)
