const fs = require("fs");
const path = require("path");
const ROOT = path.join(__dirname, "..", "container", "app", "src", "main", "java", "lobos");

const spec = fs.readFileSync(path.join(ROOT, "bridge", "ApiSpec.kt"), "utf8");
const broker = fs.readFileSync(path.join(ROOT, "bridge", "CapabilityBroker.kt"), "utf8");

const methodNames = new Set();
for (const blk of [
  broker.slice(broker.indexOf("OS_METHODS"), broker.indexOf("private val METHODS")),
  broker.slice(broker.indexOf("private val METHODS")),
]) {
  for (const m of blk.matchAll(/"([\w.]+)"\s+to\s+MethodDef/g)) methodNames.add(m[1]);
}

const CANONICAL = new Map([...spec.matchAll(/"([\w.]+)"\s+to\s+"(lobos\.[\w.]+)"/g)].map((m) => [m[1], m[2]]));
const scopeKeys = [...spec.matchAll(/"(lobos\.[\w.]+)"\s+to\s+SCOPE_\w+/g)].map((m) => m[1]);
const idemKeys = [...spec.matchAll(/"(lobos\.[\w.]+)"\s+to\s+(?:IDEMPOTENT|READONLY)/g)].map((m) => m[1]);

const norm = (m) => CANONICAL.get(m) || m;

const longToShort = new Map();
for (const [short, long] of CANONICAL) longToShort.set(long, short);
const reachable = (long) => longToShort.has(long);

let bad = 0;
console.log("=== 方法表 " + methodNames.size + " 个 / 别名 " + CANONICAL.size + " 条 ===\n");

console.log("--- ① 每个已注册方法的 scope/idempotence 标注必须可达 ---");
const SCOPES = new Map([...spec.matchAll(/"(lobos\.[\w.]+)"\s+to\s+(SCOPE_\w+)/g)].map((m) => [m[1], m[2]]));
const IDEM = new Map([...spec.matchAll(/"(lobos\.[\w.]+)"\s+to\s+(IDEMPOTENT|READONLY)/g)].map((m) => [m[1], m[2]]));

for (const [short, long] of CANONICAL) {
  if (!methodNames.has(short)) {
    console.log("  ✗ 别名 " + short + " 指向未注册方法（CANONICAL 是它自己的 key，但方法表没有）");
    bad++;
  }
}

console.log("\n--- ② SCOPES / IDEMPOTENCE 的每个 key 必须有对应注册方法 ---");
const s0 = bad;
for (const k of scopeKeys) {
  if (!reachable(k) && !methodNames.has(k)) {
    console.log("  ✗ SCOPES  key " + k + " 无对应注册方法 -> scopeOf 恒 miss");
    bad++;
  }
}
if (bad === s0) console.log("  ✓ SCOPES  " + scopeKeys.length + " 条全部可达");

const s1 = bad;
for (const k of idemKeys) {
  if (!reachable(k) && !methodNames.has(k)) {
    console.log("  ✗ IDEMPOTENCE key " + k + " 无对应注册方法 -> 恒返回 MUTATING");
    bad++;
  }
}
if (bad === s1) console.log("  ✓ IDEMPOTENCE " + idemKeys.length + " 条全部可达");

console.log("\n--- ③ 反向：声明为 SCOPE_SYSTEM 的方法，其门禁必须真的是 SYSTEM ---");
const s2 = bad;
for (const m of broker.matchAll(/"([\w.]+)"\s+to\s+MethodDef\(listOf\(ApiSpec\.GROUP_SYS\)/g)) {
  const short = m[1];
  const long = norm(short);
  if (SCOPES.get(long) !== "SCOPE_SYSTEM") {
    console.log("  ✗ " + short + " 硬编码 GROUP_SYS 门禁，但 ApiSpec.SCOPES 未标 SCOPE_SYSTEM（两处口径不一致）");
    bad++;
  }
}
if (bad === s2) console.log("  ✓ 硬编码 GROUP_SYS 的方法与 SCOPES 标注一致");

console.log("\n" + (bad === 0 ? "三表与方法表自洽" : bad + " 处不自洽"));
process.exit(bad === 0 ? 0 : 1);
