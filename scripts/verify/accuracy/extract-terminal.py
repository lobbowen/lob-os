#!/usr/bin/env python3
"""Extract probe KEY=VALUE lines from a LobOS terminal UI dump.

The terminal renders probe stdout as uiautomator text nodes. This module turns
that into the same structured tuple the host/android targets produce, so the
diff harness can compare them. Terminal rendering is lossy for long lines and
some punctuation; extraction is deliberately conservative.
"""
import re, sys, json

def extract(xml_text, only=None):
    texts = re.findall(r'text="([^"]*)"', xml_text)
    out = {}
    section = None
    # Only keep the LAST occurrence per section: the terminal UI accumulates
    # historical text nodes, and a re-run appends a fresh block after the old.
    seen = {}
    for t in texts:
        m = re.match(r'===(\w+)===', t)
        if m:
            section = m.group(1)
            seen[section] = seen.get(section, 0) + 1
            # reset on the latest block of this section
            out.pop(section, None)
            continue
        if re.match(r'^rc=\d+$', t) or re.match(r'^__RUN_', t):
            continue
        kv = re.match(r'^([A-Za-z_][A-Za-z0-9_\[\]:/]*)=(.*)$', t)
        if kv and section:
            if only and section != only:
                continue
            out.setdefault(section, {})[kv.group(1)] = kv.group(2)
    return out

if __name__ == "__main__":
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 else sys.stdin.read()
    only = sys.argv[2] if len(sys.argv) > 2 else None
    print(json.dumps(extract(xml, only), indent=2, ensure_ascii=False))
