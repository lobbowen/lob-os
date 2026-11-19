# 件分发基础设施：自建服务方案

背景：发布时上传极慢（每块 4MB 间隔 2-4 分钟，500MB 要 4+ 小时）。
定位证据：本机（国内）到七牛上传节点延迟 11ms，而 GitHub Actions runner（海外）
到七牛（国内）每块 4MB 耗时 3 分钟。瓶颈是**海外 CI 到国内对象存储的跨境链路**，
不是七牛本身慢，也不是代码 bug。

目标：让 CI 产出的件能以可接受速度到达设备能下载的公开地址，且支持断点续传。

---

## 一、先明确需求（下载侧契约，不能破）

`ResumableDownloader.kt` 要求源站支持：
- HTTP Range 请求（`Range: bytes=<offset>-`）
- 响应 206 + Content-Range / Content-Length
- 全量 GET 返回 200 + Content-Length
- sha256 校验（客户端已有，源站只需提供正确字节）
- HTTPS（清单 url 是 https，Android 明文 HTTP 受限）

清单 url 形态：`https://<baseUrl>/component/<文件名>`，
baseUrl 来自 channel.json（当前 `https://lobcdn.zll.ink`）。
换源站只要改 baseUrl 与清单生成的 COMPONENT_BASE_URL，不动客户端。

---

## 二、三种方案

### 方案一：自建对象存储源站（治本，但要一台国内服务器）

架构：
- 一台国内云服务器（有公网 IP、带宽足够）
- 装 MinIO（S3 兼容）或纯 Nginx 静态文件服务
- 域名 + TLS（letsencrypt）
- CI 通过 S3 API 或 rsync/scp 上传

上传链路对比：
- 现状：海外 GitHub runner -> 七牛国内上传节点（跨境，慢）
- 自建：海外 GitHub runner -> 自建国内服务器（同样跨境，未必更快！）

关键判断：**自建国内服务器不解决跨境上传慢**——因为 CI 还是在海外，
到国内服务器的跨境链路一样慢。除非把构建也搬到国内。

### 方案二：CI 产物先到海外对象存储，再回源同步（推荐）

架构：
- 构建 workflow 把件传到**海外对象存储**（GitHub Releases 本身就是，或 Cloudflare R2）
  —— 海外到海外，快
- 国内源站（自建 Nginx 或 CDN）从海外对象存储**回源拉取**
  —— 回源是"国内主动拉"，可以走 CDN 回源优化，或定时同步
- 设备从国内源站下载（快）

但这里有个更简单的现实：**件已经固化在 GitHub Release 了**（rt-python 等 tag）。
Release asset 上传到 GitHub（海外）本来就快（构建 workflow 里完成）。
慢的只是"从 GitHub Release 搬到七牛 CDN"这一步。

所以方案二的简化版：
- 不把件主动上传到七牛
- 改清单 url 直接指向一个能代理 GitHub Release 的国内镜像/CDN
- 或者：一台国内小服务器做反向代理，按需从 GitHub Release 拉取并缓存

### 方案三：云厂商 CDN 回源 GitHub Release（最省事，如果可行）

- Cloudflare / 又拍云 / 七牛 都支持"回源到自定义源站"
- 源站填 GitHub Release 的 raw 下载 URL
- 第一次请求时 CDN 从 GitHub 拉，之后缓存
- CI 完全不做上传，只更新清单

限制：GitHub Release 大文件（>100MB）下载可能有限制；回源成功率/速度要实测。

---

## 三、推荐

分两步走：

### 第一步（立即缓解，不改架构）：把构建搬到国内或加上传缓存

具体：在 GitHub Actions 之外，用一台国内机器定期从 GitHub Release 增量拉取
新 asset，上传到七牛。国内到七牛 11ms，上传 500MB 只要几分钟。
CI 只负责构建+固化到 Release，不再直接传七牛。

这是最小改动：CI workflow 里删掉"发布件包到对象存储"步骤，
加一个国内侧的同步脚本（cron 或手动触发），从 Release 拉 -> 传七牛。

优点：不改清单格式、不改客户端、不改下载域名。
缺点：多一个国内同步环节。

### 第二步（治本，如果需要）：自建源站 + DNS 切换

如果长期要看自建服务：
- 国内服务器 + Nginx 静态服务（或 MinIO）
- 上传：国内机器从 GitHub Release 拉取（快，因为 Release 在海外但国内拉取单连接，
  或用代理加速），存到本地
- 下载：nginx 开 `Accept-Ranges`，配 HTTPS
- 切 baseUrl：channel.json 的 baseUrl 改到新域名，重新签名清单
- 灰度：先用一个测试 channel 验证，再切正式

---

## 四、自建服务的最小可行配置（nginx）

```
server {
    listen 443 ssl http2;
    server_name pieces.<domain>;

    root /data/pieces;              # component/ 目录放件包
    ssl_certificate ...;
    ssl_certificate_key ...;

    location /component/ {
        types { } default_type application/octet-stream;
        add_header Accept-Ranges bytes;
        try_files $uri =404;
    }
}
```

必须满足：
- `Accept-Ranges: bytes`（ResumableDownloader 靠 Range 断点）
- 206 响应正确（nginx 静态文件默认支持 Range）
- HTTPS + 有效证书
- 文件权限可读

同步脚本（国内机器）：
```
# 从 GitHub Release 拉三个 python asset
gh release download rt-python --repo lobbowen/lob-os --dir /data/pieces/.incoming
# 解 gzip 容器得到 component-*.zip，放进 /data/pieces/component/
```
复用现有 `unpack-piece.sh` 做解包与 sha 校验。

---

## 五、代价与风险

- 自建服务器要运维：磁盘（件包累计可能几十 GB）、带宽、证书续期、可用性
- 跨境拉取 GitHub Release 可能也不稳定（但有缓存，只需拉一次）
- 切 baseUrl 要重新签名所有清单（客户端校验公钥已固定，签名私钥不变即可）
- 单点：自建源站没有 CDN 分发，多地访问延迟可能不均

结论：如果只是为了解决"发布慢"，**第一步（国内同步机）足够**，不用自建完整源站。
自建源站是长期去七牛依赖时才做。

---

## 六、实测数据（决定性）

| 链路 | 实测 |
|---|---|
| 本机(国内) -> 七牛上传节点延迟 | 11ms |
| GitHub Actions runner(海外) -> 七牛上传 4MB | 2-4 分钟 |
| 本机(国内) -> GitHub Release 下载大件 | 82 KB/s |

三条链路都慢在**跨境**：
- 海外 CI -> 国内七牛：跨境，慢（发布瓶颈）
- 国内 -> GitHub Release：跨境，慢（82KB/s，反向同步也慢）

推论：香港服务器方案的收益取决于"GitHub -> 香港"和"香港 -> 七牛"两段。
- GitHub -> 香港：比国内快（香港国际出口好），但仍是跨境，需实测
- 香港 -> 七牛(国内)：跨境入华，通常比海外 CI 直连国内七牛好，但未必快很多

关键：香港服务器不能消除 GitHub 到中国的跨境，只是把两段跨境合并成
"一段到香港(较好) + 一段香港入华(中等)"。是否有收益必须实测这两段。

---

## 七、香港服务器实测（候选方案验证）

测试机：103.247.28.83，Ubuntu 22.04，1 核 1GB，6.6GB 可用（配置偏小，做中转够用）。

| 链路 | 实测 | 对比 |
|---|---|---|
| 国内 -> GitHub Release | 82 KB/s | 基准（极慢） |
| 香港 -> GitHub Release | 9833 KB/s (9.8 MB/s) | 快约 120 倍 |
| 香港 -> 七牛上传节点 | 延迟 318ms，丢包 20% | 国内直连七牛 11ms |
| 香港 -> 七牛 CDN | 延迟 350ms | 跨境入华 |

结论：香港方案的核心收益成立——“GitHub -> 香港下载”从 82KB/s 提升到 9.8MB/s。
“香港 -> 七牛”虽是跨境入华（318ms/20%丢包），但比海外 CI 直连七牛
（4MB/2-4分钟）预期好很多。需实测香港上传七牛的吞吐确认（需要七牛凭证）。

推荐链路调整：
GitHub Actions 构建完 -> 直接推件到香港服务器（海外到香港，快）
-> 香港服务器上传七牛（跨境入华，318ms，但比海外直连好）
-> 国内设备从七牛 CDN 下载（快）

注意：香港机只有 6.6GB 磁盘，不能长期存件；只做中转，传完即删。

---

## 八、香港中转实测最终结论（2026-11-20）

用 CI 有效 secrets 在香港机（103.247.28.83）完成真实上传验证：

| 链路 | 实测 | 结论 |
|---|---|---|
| GitHub -> 香港机下载 160MB | 约 11 秒（9.8MB/s） | 快 |
| 香港机 -> 七牛上传 160MB | 约 22 分钟（约 0.12MB/s） | 慢，但比海外CI直连快约 5 倍 |
| 对比：海外 CI -> 七牛 | 4MB/2-4 分钟（约 0.02MB/s） | 最慢 |

结论：
1. 香港中转确实改善上传（5 倍），但不是数量级的 solve。瓶颈从"海外跨境入华"
   变成"香港跨境入华"，后者仍慢（七牛入华链路对香港同样限速/丢包 20%）。
2. 真正快的是"国内 -> 七牛"（11ms）。所以最优链路是让**国内机器**从 GitHub
   拉件（国内拉 GitHub 只有 82KB/s，慢）或从香港机中转（香港拉 GitHub 快），
   再由**国内机器**上传七牛（11ms，快）。

更优架构（三段拆分，每段走最快的链路）：
```
GitHub Actions(海外构建,固化到Release)
  --快--> 香港机(9.8MB/s拉GitHub) 
  --快?--> 国内机(香港到国内传输)
  --快(11ms)--> 七牛
```
但"香港 -> 国内机"这一段还没测，可能是新的瓶颈。

关键判断：单纯"加香港机传七牛"只快 5 倍（500MB 约 1 小时），不如预期。
要真正快，必须让上传七牛这一步发生在国内（11ms）。所以推荐：
- 方案：香港机只做 GitHub 拉件 + 中转，国内机做上传七牛
- 或：干脆国内机直接从 GitHub 拉（82KB/s 太慢）不行，必须经香港中转

待验证：香港 -> 国内机的传输速率（若快，则三段方案成立）。

清理状态：临时 workflow、临时 secret、香港机凭证与测试件均已删除。
测试期上传的 test-piece.zip（160MB）留在七牛，不被清单引用，无害。
