const fs = require("fs");
const path = require("path");
const DIR = path.join(__dirname, "..", "tools");

let bad = 0;
for (const f of fs.readdirSync(DIR).filter((x) => x.endsWith(".js"))) {
  const p = path.join(DIR, f);
  const src = fs.readFileSync(p, "utf8");
  src.split("\n").forEach((ln, i) => {
    if (ln.includes("__dirname")) return;
    if (/\/data\/user\/|\/home\/|[A-Z]:\\\\/.test(ln)) {
      console.log("  ✗ " + f + ":" + (i + 1) + "  硬编码绝对路径: " + ln.trim().slice(0, 70));
      bad++;
    }
  });
}
console.log(bad === 0 ? "  tools/ 无硬编码路径（全部用 __dirname 推导）" : "  " + bad + " 处需改为 __dirname");
process.exit(bad === 0 ? 0 : 1);
