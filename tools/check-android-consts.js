const fs = require("fs");
const path = require("path");
const ROOT = "/data/user/0/lobos.app/files/work/lob-os/container/app/src/main/java/lobos";

const VERIFIED = new Set([
  "TYPE_WINDOW_STATE_CHANGED",
  "TYPE_WINDOW_CONTENT_CHANGED",
  "TYPE_WINDOWS_CHANGED",
  "TYPE_VIEW_CLICKED",
  "TYPE_VIEW_LONG_CLICKED",
  "TYPE_VIEW_FOCUSED",
  "TYPE_VIEW_SELECTED",
  "TYPE_VIEW_TEXT_CHANGED",
  "TYPE_VIEW_TEXT_SELECTION_CHANGED",
  "TYPE_NOTIFICATION_STATE_CHANGED",
]);

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".kt")) files.push(p);
  }
})(ROOT);

console.log("=== AccessibilityEvent.TYPE_* 白名单核查 ===\n");
console.log("已验证 " + VERIFIED.size + " 个：" + [...VERIFIED].join(", ") + "\n");

let bad = 0;
for (const f of files) {
  const rel = f.replace(ROOT + "/", "");
  const src = fs.readFileSync(f, "utf8");
  const used = new Set(src.match(/AccessibilityEvent\.(TYPE_[A-Z_]+)/g)?.map((x) => x.split(".")[1]) || []);
  const unknown = [...used].filter((t) => !VERIFIED.has(t));
  if (unknown.length) {
    bad++;
    console.log("  ✗ " + rel + "  用了未验证的: " + unknown.join(", "));
  } else if (used.size) {
    console.log("  ✓ " + rel + "  " + [...used].length + " 个常量全部已验证");
  }
}

console.log("\n=== 裸 TYPE_（缺类名限定）===");
let bare = 0;
for (const f of files) {
  const rel = f.replace(ROOT + "/", "");
  const src = fs.readFileSync(f, "utf8");
  src.split("\n").forEach((ln, i) => {
    if (/(^|[^.\w])TYPE_[A-Z_]+/.test(ln) && !/AccessibilityEvent\.TYPE_/.test(ln)) {
      if (/const val TYPE_/.test(ln)) return;
      console.log("  ✗ " + rel + ":" + (i + 1) + "  " + ln.trim().slice(0, 80));
      bare++;
    }
  });
}
if (!bare) console.log("  无裸引用");

console.log(bad === 0 && bare === 0 ? "\n全部合规" : "\n" + (bad + bare) + " 处需修");
