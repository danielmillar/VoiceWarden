#!/usr/bin/env python3

import re
import sys
from pathlib import Path

CODE_MESSAGE = (
    "This comment is code that no longer runs. Delete it. "
    "Do not turn it back into live code in this change."
)
BANNER_MESSAGE = (
    "This comment is a divider. Delete it. "
    "A section name belongs in the code, not in a banner."
)

BANNER_PATTERNS = (
    r"^[ \t]*[=\-*#~_]{4,}[ \t]*$",
    r"^[ \t]*[=\-*#~_]{3,}[ \t]+\S.*\S[ \t]+[=\-*#~_]{3,}[ \t]*$",
    r"^[ \t]*[=\-*#~_]{3,}(?:[ \t]+[=\-*#~_]{3,})+[ \t]*$",
)
BANNER_LINE = re.compile("|".join(f"(?:{pattern})" for pattern in BANNER_PATTERNS))
SKIP_PREFIXES = (
    "eslint-disable",
    "eslint-enable",
    "eslint-env",
    "@ts-ignore",
    "@ts-expect-error",
    "@ts-nocheck",
    "@ts-check",
    "selene:",
    "!nocheck",
    "!nolint",
    "noinspection",
    "ktlint-disable",
    "ktlint-enable",
)
CODE_SUFFIXES = {".kt", ".kts", ".java", ".lua", ".luau"}
# Detekt ForbiddenComment reports these. Scanning them here would report the same line twice.
DETEKT_BANNER_SUFFIXES = {".kt", ".kts"}
SKIP_DIRS = {"build", ".gradle", "node_modules", "dist", "Packages", ".git"}


def classify(body: str):
    """Return 'code', 'banner', or None for one comment body, markers already removed."""
    text = body.strip()
    if not text or _skipped(text):
        return None
    if _is_banner(text):
        return "banner"
    if _is_code(text):
        return "code"
    return None


def _skipped(text: str) -> bool:
    low = text.lower()
    if "copyright" in low or "spdx-license" in low:
        return True
    if text.startswith(("http://", "https://")):
        return True
    head = text.lstrip("*").strip()
    return head.startswith(SKIP_PREFIXES) or text.startswith(SKIP_PREFIXES)


def _body_line(line: str) -> str:
    stripped = line.strip()
    if re.match(r"^\*(?!\*)(?:\s+|$)", stripped):
        return stripped[1:].strip()
    return stripped


def _is_banner(text: str) -> bool:
    for line in text.splitlines():
        stripped = _body_line(line)
        if stripped and BANNER_LINE.fullmatch(stripped):
            return True
    return False


def _is_code(text: str) -> bool:
    for line in text.splitlines():
        stripped = _body_line(line)
        if stripped and _is_code_line(stripped):
            return True
    return False


def _is_code_line(line: str) -> bool:
    if _skipped(line) or BANNER_LINE.fullmatch(line):
        return False
    if re.match(r"^return\b\s*[\w.]+\s*;?\s*$", line):
        return True
    if re.match(r"^return\b\s*[\w.]+\s*\(", line):
        return True
    if re.match(r"^return\b\s*[\[{\"'\d]", line):
        return True
    if re.match(r"^package\s+[\w.]+(?:\s*;\s*)?$", line):
        return True
    if re.match(r"^import\s+(?:type\s+|static\s+)?[\w.*]+\.[\w*{]+", line):
        return True
    if re.match(r"^import\s+[\w.]+\s*;", line):
        return True
    if re.match(r"^import\s*[{*]", line):
        return True
    if not re.search(r"[=(;{}]", line):
        return False
    patterns = (
        r"^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+\w+",
        r"^(?:export\s+)?(?:const|let|var)\s+\w+\s*[=:]",
        r"^(?:export\s+)?class\s+\w+\s*[{(:]",
        r"^(?:export\s+)?interface\s+\w+\s*[{<]",
        r"^(?:export\s+)?type\s+\w+\s*[=<{]",
        r"^(?:export\s+)?enum\s+\w+\s*[{<]",
        r"^(?:(?:public|private|protected|internal|override|open|abstract|suspend|data|sealed|static|final)\s+)+\w+\b",
        r"^fun\s+\w+\s*[(=]",
        r"^(?:val|var)\s+\w+\s*[=:]",
        r"^local\s+\w+\s*=",
        r"^(?:if|for|while|switch|when|catch)\s*\(",
        r"^(?:throw|await|yield)\s+\S",
        r"^(?:break|continue)\s*;",
        r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+\s*\(",
        r"^[A-Za-z_]\w*\([^)]*\)\s*;?\s*$",
        r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\s*=[^=]",
        r"^[})\]]",
    )
    return any(re.match(pattern, line) for pattern in patterns)


def findings_in_source(path: str, text: str):
    """Return (line, message) hits. path is used for the suffix, not read."""
    suffix = Path(path).suffix.lower()
    if suffix not in CODE_SUFFIXES:
        return []
    banners = suffix not in DETEKT_BANNER_SUFFIXES
    hits = []
    for line_no, body in _comment_bodies(text, suffix):
        kind = classify(body)
        if kind == "code":
            hits.append((line_no, CODE_MESSAGE))
        elif kind == "banner" and banners:
            hits.append((line_no, BANNER_MESSAGE))
    return hits


def _comment_bodies(text: str, suffix: str):
    if suffix in {".lua", ".luau"}:
        yield from _lua_comments(text)
    else:
        yield from _c_comments(text)


def _c_comments(text: str):
    i = 0
    n = len(text)
    line = 1
    while i < n:
        if text[i] == "\n":
            line += 1
            i += 1
            continue
        if text[i] in "\"'":
            i = _skip_string(text, i)
            continue
        if text.startswith("/*", i):
            full = _opening_is_full_line(text, i)
            start = line
            i += 2
            body = []
            while i < n and not text.startswith("*/", i):
                body.append(text[i])
                if text[i] == "\n":
                    line += 1
                i += 1
            if i < n:
                i += 2
            if full:
                yield start, "".join(body)
            continue
        if text.startswith("//", i):
            full = _opening_is_full_line(text, i)
            i += 2
            body = []
            while i < n and text[i] != "\n":
                body.append(text[i])
                i += 1
            if full:
                yield line, "".join(body)
            continue
        i += 1


def _lua_comments(text: str):
    i = 0
    n = len(text)
    line = 1
    while i < n:
        if text[i] == "\n":
            line += 1
            i += 1
            continue
        if text[i] in "\"'":
            i = _skip_string(text, i)
            continue
        if text.startswith("--", i):
            full = _opening_is_full_line(text, i)
            eq = 0
            j = i + 2
            if j < n and text[j] == "[":
                j += 1
                while j < n and text[j] == "=":
                    eq += 1
                    j += 1
                if j < n and text[j] == "[":
                    start = line
                    i = j + 1
                    close = "]" + ("=" * eq) + "]"
                    body = []
                    while i < n and not text.startswith(close, i):
                        body.append(text[i])
                        if text[i] == "\n":
                            line += 1
                        i += 1
                    if i < n:
                        i += len(close)
                    if full:
                        yield start, "".join(body)
                    continue
            i += 2
            body = []
            while i < n and text[i] != "\n":
                body.append(text[i])
                i += 1
            if full:
                yield line, "".join(body)
            continue
        i += 1


def _opening_is_full_line(text: str, offset: int) -> bool:
    start = text.rfind("\n", 0, offset) + 1
    return text[start:offset].strip() == ""


def _skip_string(text: str, i: int) -> int:
    if text.startswith('"""', i):
        end = text.find('"""', i + 3)
        return len(text) if end < 0 else end + 3
    quote = text[i]
    i += 1
    n = len(text)
    while i < n and text[i] != quote and text[i] != "\n":
        i += 2 if text[i] == "\\" else 1
    return i + 1 if i < n and text[i] == quote else i


def scan(paths):
    hits = []
    for raw in paths:
        path = Path(raw)
        files = [path] if path.is_file() else _files(path)
        for file in files:
            try:
                text = file.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                print(f"{file}: cannot read ({exc})", file=sys.stderr)
                hits.append((str(file), 0, "cannot read"))
                continue
            for line, message in findings_in_source(str(file), text):
                hits.append((str(file), line, message))
    return hits


def _files(root: Path):
    if not root.exists():
        return []
    found = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in CODE_SUFFIXES:
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        found.append(path)
    return found


def main(argv):
    paths = argv[1:] or ["src"]
    hits = scan(paths)
    for path, line, message in hits:
        print(f"{path}:{line}: {message}")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
