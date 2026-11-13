"""Text extractors: TXT books and text-hooker logs."""

from kotobaya_worker.extract.text.texthooker import extract_texthooker
from kotobaya_worker.extract.text.txt import extract_txt

__all__ = ["extract_texthooker", "extract_txt"]
