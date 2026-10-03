const fs = require("fs");
const path = require("path");
const ROOT = "/data/user/0/lobos.app/files/work/lob-os/container/app/src/main/java/lobos";

const files = [];
(function w(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) w(p);
    else if (e.name.endsWith(".kt")) files.push(p);
  }
})(ROOT);

const DEAD = ["ManifestSchema.ROLES", "ManifestSchema.NAMESPACE_SET", "ManifestSchema.DEFAULT_NAMESPACES", "ManifestSchema.DEFAULT_BACKOFF", "ManifestSchema.SC_ROLE"];

const DEAD_OS = ["ROLES", "NAMESPACE_SET", "DEFAULT_NAMESPACES"];

let bad = 0;
console.log("=== 已删符号的全仓引用 ===");
for (const f of files) {
  const rel = f.replace(ROOT + "/", "");
  const src = fs.readFileSync(f, "utf8");
  for (const sym of DEAD) {
    if (src.includes(sym)) {
      src.split("\n").forEach((ln, i) => {
        if (ln.includes(sym)) {
          console.log("  ✗ " + rel + ":" + (i + 1) + "  " + ln.trim().slice(0, 80));
          bad++;
        }
      });
    }
  }
  if (rel.startsWith("os/")) {
    for (const sym of DEAD_OS) {
      const re = new RegExp("\\b" + sym + "\\b");
      if (re.test(src)) {
        src.split("\n").forEach((ln, i) => {
          if (re.test(ln) && !/val DEAD|val ROLES/.test(ln)) {
            console.log("  ? " + rel + ":" + (i + 1) + "  " + ln.trim().slice(0, 80));
            bad++;
          }
        });
      }
    }
  }
}
console.log(bad === 0 ? "  零引用" : "  " + bad + " 处残留");
process.exit(bad === 0 ? 0 : 1);
