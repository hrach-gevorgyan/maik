#!/usr/bin/env python3
"""Check every model the app offers, without downloading gigabytes.

Four shipped releases were broken by model bundles that were never inspected: one
was LiteRT-LM rather than a task archive, one was a GPU-only build, one was never
checked at all because a range request failed and the failure was shrugged off.

A `.task` bundle is a ZIP. Its central directory sits at the end, so a range request
for the tail lists every member, and a second one pulls out `METADATA` — which is
where each model keeps the prompt template the runtime applies. Total cost is a few
hundred kilobytes per model.

Run from the repository root:  python tools/verify_models.py
"""
import re
import struct
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


def central_directory(tail):
    """Yield (name, compressed_size, local_offset) for every member."""
    for match in re.finditer(b"PK\x01\x02", tail):
        at = match.start()
        if at + 46 > len(tail):
            continue
        name_len = struct.unpack_from("<H", tail, at + 28)[0]
        extra_len = struct.unpack_from("<H", tail, at + 30)[0]
        name = tail[at + 46:at + 46 + name_len].decode("utf-8", "ignore")
        size = struct.unpack_from("<I", tail, at + 20)[0]
        offset = struct.unpack_from("<I", tail, at + 42)[0]

        # 0xFFFFFFFF means the real offset lives in a ZIP64 extra field. Missing
        # this is what made Phi-4-mini look unverifiable.
        if offset == 0xFFFFFFFF:
            extra = tail[at + 46 + name_len: at + 46 + name_len + extra_len]
            cursor = 0
            while cursor + 4 <= len(extra):
                header_id, header_size = struct.unpack_from("<HH", extra, cursor)
                if header_id == 0x0001:
                    offset = struct.unpack_from("<Q", extra, cursor + 4)[0]
                    break
                cursor += 4 + header_size
        yield name, size, offset


def member_bytes(url, size, offset):
    """These archives carry four bytes before the ZIP header, so try both."""
    for shift in (4, 0):
        head, _ = ranged(url, offset + shift, offset + shift + 29)
        if head[:4] == b"PK\x03\x04":
            name_len = struct.unpack_from("<H", head, 26)[0]
            extra_len = struct.unpack_from("<H", head, 28)[0]
            start = offset + shift + 30 + name_len + extra_len
            body, _ = ranged(url, start, start + size - 1)
            return body
    return None


def check(spec):
    problems = []
    url = spec["url"]

    if not url.endswith(".task"):
        problems.append("not a .task bundle")
    if "-gpu." in url or "_gpu." in url:
        problems.append("GPU-only build: cannot load on the CPU fallback")
    if "-web" in url or "_web" in url:
        problems.append("web build: raw tflite, not a task archive")

    tail, content_range = ranged(url, last=400_000)
    total = int(content_range.rsplit("/", 1)[-1]) if "/" in content_range else 0

    if total and spec["bytes"] and abs(total - spec["bytes"]) > 1024:
        problems.append("declared %d bytes, server says %d" % (spec["bytes"], total))

    members = {name: (size, offset) for name, size, offset in central_directory(tail)}
    for required in ("TOKENIZER_MODEL", "TF_LITE_PREFILL_DECODE", "METADATA"):
        if required not in members:
            problems.append("missing %s" % required)

    template = None
    if "METADATA" in members:
        size, offset = members["METADATA"]
        blob = member_bytes(url, size, offset)
        if blob is None:
            problems.append("METADATA could not be read")
        else:
            # The template is stored as text among the protobuf fields; a bundle
            # without one leaves the app guessing, which is how replies got garbled.
            readable = blob.decode("utf-8", "replace")
            if not re.search(r"<\|?[A-Za-z_｜]+\|?>|<｜", readable):
                problems.append("METADATA carries no prompt template")
            template = readable

    ekv = re.search(r"ekv(\d+)", url)
    if ekv and spec["context"] and int(ekv.group(1)) != spec["context"]:
        problems.append(
            "context %d does not match the bundle's ekv%s" % (spec["context"], ekv.group(1))
        )

    return problems, total, template


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
            problems, total, template = check(spec)
        except Exception as error:  # noqa: BLE001 - report, do not mask
            print("  ERROR  %s" % error)
            failed = True
            continue

        print("  size     %d bytes (%.2f GB)" % (total, total / 1024 ** 3))
        if template:
            snippet = template.replace("\n", " ")[:150]
            print("  template %s" % snippet)

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
