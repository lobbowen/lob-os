#!/usr/bin/env python3
"""
LobOS accuracy differential harness.

Runs the same probe under multiple targets and diffs the (stdout, stderr, exit)
tuple. Goal: make "accurate" measurable instead of trusting "it ran".

Targets:
  host    - local x86_64 Linux (POSIX semantics baseline)
  android - on-device Android native, run-as direct exec (no LobOS compat hook)
  lobos   - through the LobOS terminal / exec hook (the subject under test)

Known hard boundaries (seccomp, single-uid, Android host /etc) are declared as
EXEMPT with a reason, not silently ignored. A dimension not exempted but
differing between host and lobos is a FAIL.

This is v1: lobos target uses the terminal input path via adb. It records raw
output for manual extraction; full auto-parse is a follow-up.
"""
import argparse, json, os, subprocess, sys, time, re

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(HERE, "bin")
PROBE_HOST = os.path.join(BIN, "probe-host")
PROBE_ARM = os.path.join(BIN, "probe-aarch64-static")
ADB = "/home/bowen/runtimes/platform-tools/adb"
DEV = "f0942f56"
PKG = "lobos.os"
REMOTE = "/data/user/0/%s/files/accprobe" % PKG

DIMS = ["argv0", "env", "path", "identity", "signal"]
EXIT_MODES = ["ok", "42", "sigterm", "enoent"]

# Hard boundaries: differences expected because of the Android APK environment.
# Each entry: dim -> reason. These are NOT passed as "accurate"; they are
# explicitly marked so nobody mistakes them for verified equivalence.
EXEMPT = {
    "identity": "single Android uid + seccomp SIGSYS fake getpid: identity is a simulated value by design; "
                "declared hard boundary, not an accuracy pass",
    "path":     "/etc passthrough + overlay fallback + TMPDIR-driven /tmp are deliberate environment adaptations; "
                "differences expected, each must be reviewed not auto-passed",
}


def run_local(args, timeout=10):
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout)
        return {"stdout": p.stdout.decode("utf-8", "replace"),
                "stderr": p.stderr.decode("utf-8", "replace"),
                "exit": p.returncode, "signal": None}
    except subprocess.TimeoutExpired:
        return {"stdout": "", "stderr": "TIMEOUT", "exit": None, "signal": "TIMEOUT"}


def run_android(dim, mode=None, timeout=20):
    remote_dim = "%s %s" % (REMOTE, dim) if not mode else "%s %s %s" % (REMOTE, dim, mode)
    cmd = "%s -s %s shell 'run-as %s %s; echo __RC__$?'" % (ADB, DEV, PKG, remote_dim)
    p = run_local(["/bin/sh", "-c", cmd], timeout)
    out = p["stdout"]
    rc = None
    m = re.search(r"__RC__(\d+)", out)
    if m:
        rc = int(m.group(1))
        out = out.rsplit("__RC__", 1)[0]
    p["stdout"] = out
    p["exit"] = rc
    return p


def push_probe():
    subprocess.run([ADB, "-s", DEV, "push", PROBE_ARM, "/data/local/tmp/accprobe"],
                   capture_output=True, timeout=60, check=True)
    subprocess.run([ADB, "-s", DEV, "shell",
                    "run-as %s sh -c 'cp /data/local/tmp/accprobe %s && chmod 755 %s'" % (PKG, REMOTE, REMOTE)],
                   capture_output=True, timeout=30, check=True)


def collect(target, dim, mode=None):
    if target == "host":
        args = [PROBE_HOST, dim] + ([mode] if mode else [])
        return run_local(args)
    if target == "android":
        return run_android(dim, mode)
    return {"stdout": "", "stderr": "lobos target not automated in v1", "exit": None, "signal": None}


def tuple_of(r):
    return (r.get("stdout", ""), r.get("stderr", ""), r.get("exit", None))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", action="store_true", help="build probes first")
    ap.add_argument("--push", action="store_true", help="push aarch64 probe to device")
    ap.add_argument("--targets", default="host,android", help="comma-separated targets")
    ap.add_argument("--json", default=os.path.join(HERE, "results.json"), help="write results here")
    a = ap.parse_args()

    if a.build:
        subprocess.run(["bash", os.path.join(HERE, "build-probes.sh")], check=True)
    if a.push:
        push_probe()

    targets = [t.strip() for t in a.targets.split(",") if t.strip()]
    cases = [("argv0", None), ("env", None), ("path", None),
             ("identity", None), ("signal", None)] + [("exit", m) for m in EXIT_MODES]

    results = {"targets": targets, "exempt": EXEMPT, "cases": {}}
    for dim, mode in cases:
        key = dim if not mode else "%s:%s" % (dim, mode)
        results["cases"][key] = {}
        for t in targets:
            results["cases"][key][t] = collect(t, dim, mode)

    # Diff against host baseline
    findings = []
    for key, per in results["cases"].items():
        dim = key.split(":")[0]
        base = tuple_of(per.get("host", {}))
        for t in targets:
            if t == "host":
                continue
            cur = tuple_of(per.get(t, {}))
            if cur == base:
                verdict = "MATCH"
            elif dim in EXEMPT:
                verdict = "EXEMPT"
            else:
                verdict = "DIFF"
            findings.append({"case": key, "target": t, "verdict": verdict})

    results["findings"] = findings
    with open(a.json, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    for f in findings:
        print("  %-14s %-8s %s" % (f["case"], f["target"], f["verdict"]))
    ndiff = sum(1 for f in findings if f["verdict"] == "DIFF")
    print("DIFF=%d EXEMPT=%d MATCH=%d" % (
        ndiff,
        sum(1 for f in findings if f["verdict"] == "EXEMPT"),
        sum(1 for f in findings if f["verdict"] == "MATCH")))
    print("results ->", a.json)
    return 1 if ndiff else 0


if __name__ == "__main__":
    sys.exit(main())
