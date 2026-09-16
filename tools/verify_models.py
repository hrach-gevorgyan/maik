#!/usr/bin/env python3
"""Check every model the app offers, without downloading gigabytes.

Earlier releases were broken by model files nobody inspected: the wrong format, a
GPU-only build, one never checked because a request failed and was shrugged off.

Every LiteRT-LM bundle opens with the eight ASCII bytes `LITERTLM`, so a range
request for the first few bytes proves the file is what it claims to be, and a HEAD
request confirms its size. Loading and answering is proven separately, on an
emulator, by the golden test.

Run from the repository root:  python tools/verify_models.py
"""
import re
import sys
import urllib.request

SOURCE = "app/src/main/java/com/maik/app/ModelStore.kt"
TIMEOUT = 240


def read_catalogue():
    """Pull every model URL and its declared size out of the Kotlin source."""
    with open(SOURCE, encoding="utf-8") as handle:
        text = handle.read()

    specs = []
    for block in re.findall(r"ModelSpec\((.*?)\n    \)", text, re.S):
        url = re.search(r'url\s*=\s*"([^"]+)"\s*\+\s*\n\s*"([^"]+)"', block)
        if not url:
            continue
        label = re.search(r'label\s*=\s*"([^"]+)"', block)
        size = re.search(r"approxBytes\s*=\s*([\d_]+)L", block)
        context = re.search(r"contextTokens\s*=\s*(\d+)", block)
        specs.append({
            "label": label.group(1) if label else "?",
            "url": url.group(1) + url.group(2),
            "bytes": int(size.group(1).replace("_", "")) if size else 0,
            "context": int(context.group(1)) if context else 0,
        })
    return specs


def ranged(url, start=None, end=None, last=None):
    request = urllib.request.Request(url)
    request.add_header(
        "Range", "bytes=-%d" % last if last else "bytes=%d-%d" % (start, end)
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read(), response.headers.get("Content-Range", "")


def check(spec):
    problems = []
    url = spec["url"]
    name = url.rsplit("/", 1)[-1].lower()

    if not url.endswith(".litertlm"):
        problems.append("not a .litertlm bundle")
    if "-gpu." in name or "_gpu." in name:
        problems.append("GPU-only build: cannot load on the CPU fallback")
    if "web" in name:
        problems.append("web build: made for browsers")
    if any(chip in name for chip in ("qualcomm", "tensor", "intel", "mediatek")):
        problems.append("chip-specific build: only loads on one SoC")
    if not url.startswith("https://huggingface.co/litert-community/"):
        problems.append("not an ungated litert-community source")

    head, content_range = ranged(url, 0, 15)
    total = int(content_range.rsplit("/", 1)[-1]) if "/" in content_range else 0

    if head[:8] != b"LITERTLM":
        problems.append("does not start with the LITERTLM magic bytes (got %r)" % head[:8])
    if total and spec["bytes"] and abs(total - spec["bytes"]) > 1024:
        problems.append("declared %d bytes, server says %d" % (spec["bytes"], total))
    if total > 2_700_000_000:
        problems.append("%.2f GB is too big for a phone" % (total / 1024 ** 3))

    return problems, total


def main():
    specs = read_catalogue()
    if not specs:
        print("no models found in %s" % SOURCE)
        return 1

    print("checking %d model(s)\n" % len(specs))
    failed = False
    for spec in specs:
        print("=" * 66)
        print(spec["label"])
        try:
            problems, total = check(spec)
        except Exception as error:  # noqa: BLE001 - report, do not mask
            print("  ERROR  %s" % error)
            failed = True
            continue

        print("  size     %d bytes (%.2f GB)" % (total, total / 1024 ** 3))

        if problems:
            failed = True
            for problem in problems:
                print("  FAIL     %s" % problem)
        else:
            print("  OK")

    print("\n" + ("some models are not shippable" if failed else "all models verified"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
