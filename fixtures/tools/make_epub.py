"""Build fixtures/media/sample.epub (self-written text, ruby furigana, 2 chapters).

Run: python3 -I fixtures/tools/make_epub.py
"""
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "media" / "sample.epub"

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="ja">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:00000000-0000-4000-8000-0000000000e1</dc:identifier>
    <dc:title>サンプル小説</dc:title>
    <dc:language>ja</dc:language>
    <dc:creator>Kotobaya fixtures</dc:creator>
    <meta property="dcterms:modified">2026-11-12T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ja">
<head><title>目次</title></head>
<body><nav epub:type="toc"><ol>
<li><a href="ch1.xhtml">第一章</a></li>
<li><a href="ch2.xhtml">第二章</a></li>
</ol></nav></body></html>
"""


def chapter(title: str, paras: list[str]) -> str:
    body = "\n".join(f"<p>{p}</p>" for p in paras)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja">\n'
        f"<head><title>{title}</title></head>\n<body>\n<h1>{title}</h1>\n{body}\n</body></html>\n"
    )


CH1 = chapter(
    "第一章",
    [
        "<ruby>朝<rt>あさ</rt></ruby>、<ruby>私<rt>わたし</rt></ruby>は<ruby>駅<rt>えき</rt></ruby>まで<ruby>歩<rt>ある</rt></ruby>いた。",
        "<ruby>空<rt>そら</rt></ruby>はよく<ruby>晴<rt>は</rt></ruby>れていて、<ruby>風<rt>かぜ</rt></ruby>が<ruby>心地<rt>ここち</rt></ruby>よかった。",
        "「おはよう」と<ruby>友達<rt>ともだち</rt></ruby>が<ruby>言<rt>い</rt></ruby>った。",
    ],
)
CH2 = chapter(
    "第二章",
    [
        "<ruby>夜<rt>よる</rt></ruby>になって、<ruby>雨<rt>あめ</rt></ruby>が<ruby>降<rt>ふ</rt></ruby>り<ruby>始<rt>はじ</rt></ruby>めた。",
        "<ruby>私<rt>わたし</rt></ruby>は<ruby>家<rt>いえ</rt></ruby>で<ruby>静<rt>しず</rt></ruby>かに<ruby>本<rt>ほん</rt></ruby>を<ruby>読<rt>よ</rt></ruby>んだ。",
    ],
)


def main() -> None:
    fixed = (2020, 1, 1, 0, 0, 0)

    def add(z: zipfile.ZipFile, name: str, data: str, deflate: bool = True) -> None:
        info = zipfile.ZipInfo(name, fixed)
        info.compress_type = zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED
        z.writestr(info, data.encode("utf-8"))

    with zipfile.ZipFile(OUT, "w") as z:
        add(z, "mimetype", "application/epub+zip", deflate=False)  # must be first, stored
        add(z, "META-INF/container.xml", CONTAINER)
        add(z, "OEBPS/content.opf", OPF)
        add(z, "OEBPS/nav.xhtml", NAV)
        add(z, "OEBPS/ch1.xhtml", CH1)
        add(z, "OEBPS/ch2.xhtml", CH2)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
