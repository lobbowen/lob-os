const fs = require("fs");
const path = require("path");
const ROOT = path.join(__dirname, "..", "container", "app", "src", "main", "java", "lobos");

const EXTERNAL = [
  ["Context", "android.content.Context"],
  ["JSONObject", "org.json.JSONObject"],
  ["JSONArray", "org.json.JSONArray"],
  ["JSONTokener", "org.json.JSONTokener"],
  ["JSONException", "org.json.JSONException"],
  ["File", "java.io.File"],
  ["IOException", "java.io.IOException"],
  ["Build", "android.os.Build"],
  ["Bundle", "android.os.Bundle"],
  ["Handler", "android.os.Handler"],
  ["Intent", "android.content.Intent"],
  ["View", "android.view.View"],
  ["Uri", "android.net.Uri"],
  ["Bitmap", "android.graphics.Bitmap"],
  ["Rect", "android.graphics.Rect"],
  ["Path", "android.graphics.Path"],
  ["Log", "android.util.Log"],
  ["SystemClock", "android.os.SystemClock"],
  ["Environment", "android.os.Environment"],
];

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".kt")) files.push(p);
  }
})(ROOT);

let bad = 0;
for (const f of files) {
  const rel = f.replace(ROOT + "/", "");
  const src = fs.readFileSync(f, "utf8");
  const body = src.split("\n").filter((l) => !l.startsWith("import ") && !l.startsWith("package ")).join("\n");
  const imported = new Set();
  for (const m of src.matchAll(/^import\s+([\w.]+)/gm)) imported.add(m[1]);

  for (const [simple, fqn] of EXTERNAL) {
    const used = new RegExp("(?<![.\\w])" + simple + "\\b").test(body);
    if (!used) continue;
    const has = imported.has(fqn) || imported.has(simple);
    if (!has) {
      bad++;
      console.log("  MISSING " + rel + "  用了 " + simple + " 但没 import " + fqn);
    }
  }
}
console.log(bad === 0 ? "  外部类型 import 齐备" : "  " + bad + " 处缺 import");
process.exit(bad === 0 ? 0 : 1);
