#!/usr/bin/env python3
"""Extract probe KEY=VALUE lines from a LobOS terminal UI dump.

The terminal renders probe stdout as uiautomator text nodes. This module turns
that into the same structured tuple the host/android targets produce, so the
diff harness can compare them. Terminal rendering is lossy for long lines and
some punctuation; extraction is deliberately conservative.
"""
import re, sys, json

def extract(xml_text):
    texts = re.findall(r'text="([^"]*)"', xml_text)
    out = {}
    section = None
    for t in texts:
        m = re.match(r'===(\w+)===', t)
        if m:
            section = m.group(1)
            continue
        if re.match(r'^rc=\d+$', t):
            continue
        kv = re.match(r'^([A-Za-z_][A-Za-z0-9_\[\]:/]*)=(.*)$', t)
        if kv and section:
            out.setdefault(section, {})[kv.group(1)] = kv.group(2)
    return out

if __name__ == "__main__":
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 else sys.stdin.read()
    print(json.dumps(extract(xml), indent=2, ensure_ascii=False))
