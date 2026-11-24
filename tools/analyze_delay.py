#!/usr/bin/env python3
"""
M0 样本分析：中行通知相对平台通知的延迟分布。

用法：
    python3 tools/analyze_delay.py bookkeeper-m0-20261201-210000.json
    python3 tools/analyze_delay.py export.json --before 30 --after 5 --pairs pairs.csv

思路：
  1. 从每条通知的 title / text / big_text / text_lines 里抽出金额（¥12.34、12.34元、人民币12.34 …）。
  2. 中行通知（com.chinamworld.main）里的每个金额，去找同金额、时间最近的平台通知
     （中行之前 --before 分钟到之后 --after 分钟内），一一配对。
  3. 延迟 = 中行 post_time − 平台 post_time（秒），输出分位数、直方图、按平台拆分，
     以及没配上的中行通知（将来的 bank_only）和没配上的平台付款通知（可能是零钱/余额/花呗）。

只用 Python 标准库。金额正则是通用的猜测，看完真实样本后按需修改 AMOUNT_PATTERNS。
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime

BOC_PACKAGE = "com.chinamworld.main"

LABELS = {
    "com.chinamworld.main": "中国银行",
    "com.tencent.mm": "微信",
    "com.eg.android.AlipayGphone": "支付宝",
    "com.ss.android.ugc.aweme": "抖音",
    "com.sankuai.meituan": "美团",
    "com.sankuai.meituan.takeoutnew": "美团外卖",
    "com.xingin.xhs": "小红书",
    "com.jingdong.app.mall": "京东",
}

NUM = r"([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)"
AMOUNT_PATTERNS = [
    re.compile(r"(?:¥|￥|RMB|CNY)\s*" + NUM),
    re.compile(r"人民币\s*" + NUM),
    re.compile(r"(?:金额|支出|消费|付款|支付|扣款|收入|退款|实付|支取|转出|转入|存入)[^0-9¥￥\n]{0,6}" + NUM + r"\s*元?"),
    re.compile(NUM + r"\s*元"),
]
PAYMENT_WORDS = re.compile(r"支付|付款|消费|扣款|支出|实付|交易|账单|订单")
REFUND_WORDS = re.compile(r"退款|退回|退货|已退")


@dataclass
class Sample:
    id: int
    pkg: str
    post_time: int  # ms
    text: str
    amounts: list[int] = field(default_factory=list)  # 分
    raw: dict = field(default_factory=dict)

    @property
    def label(self) -> str:
        return LABELS.get(self.pkg, self.raw.get("app_label") or self.pkg)


def to_cents(s: str) -> int | None:
    try:
        v = float(s.replace(",", ""))
    except ValueError:
        return None
    if v <= 0 or v > 10_000_000:
        return None
    return int(round(v * 100))


def extract_amounts(text: str) -> list[int]:
    found: list[int] = []
    for pat in AMOUNT_PATTERNS:
        for m in pat.finditer(text):
            # 中行通知里的“余额 xxx 元”不是交易金额
            if "余额" in text[max(0, m.start() - 6): m.start(1)]:
                continue
            c = to_cents(m.group(1))
            if c is not None and c not in found:
                found.append(c)
    return found


def load(path: str) -> tuple[list[dict], list[dict]]:
    if path.lower().endswith(".csv"):
        with open(path, encoding="utf-8-sig", newline="") as f:
            rows = list(csv.DictReader(f))
        for r in rows:
            r["post_time"] = int(r["post_time"])
            r["id"] = int(r["id"])
            r["is_group_summary"] = r.get("is_group_summary") == "true"
        return rows, []
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return data.get("samples", []), data.get("listener_events", [])


def build_samples(rows: list[dict], include_summaries: bool) -> list[Sample]:
    out = []
    for r in rows:
        if r.get("is_group_summary") and not include_summaries:
            continue
        parts = [r.get(k) for k in ("title", "text", "big_text", "text_lines", "sub_text")]
        seen, uniq = set(), []
        for p in parts:
            if p and p not in seen:
                seen.add(p)
                uniq.append(p)
        text = "\n".join(uniq)
        s = Sample(id=int(r["id"]), pkg=r["package_name"], post_time=int(r["post_time"]), text=text, raw=r)
        s.amounts = extract_amounts(text)
        out.append(s)
    out.sort(key=lambda s: s.post_time)
    return out


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return math.nan
    k = (len(sorted_vals) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return sorted_vals[lo]
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def ts(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M:%S")


def short(t: str, n: int = 60) -> str:
    t = t.replace("\n", " | ")
    return t if len(t) <= n else t[: n - 1] + "…"


def histogram(delays: list[float]) -> list[str]:
    edges = [-math.inf, 0, 10, 30, 60, 90, 120, 180, 300, 600, 1800, math.inf]
    names = ["<0s", "0-10s", "10-30s", "30-60s", "60-90s", "90-120s", "2-3min", "3-5min", "5-10min", "10-30min", ">30min"]
    counts = [0] * len(names)
    for d in delays:
        for i in range(len(names)):
            if edges[i] <= d < edges[i + 1]:
                counts[i] += 1
                break
    total = max(1, len(delays))
    width = 40
    peak = max(counts) or 1
    return [f"  {n:>9} {c:5d} {c / total:6.1%} {'█' * round(c / peak * width)}" for n, c in zip(names, counts)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export", help="App 导出的 JSON（推荐）或 CSV 文件")
    ap.add_argument("--before", type=float, default=30, help="平台通知最多比中行早多少分钟（默认 30）")
    ap.add_argument("--after", type=float, default=5, help="平台通知最多比中行晚多少分钟（默认 5）")
    ap.add_argument("--include-summaries", action="store_true", help="分组摘要通知也参与配对")
    ap.add_argument("--pairs", help="把配对结果写成 CSV")
    ap.add_argument("--show", type=int, default=15, help="每类未配对样本最多列出几条（默认 15）")
    args = ap.parse_args()

    rows, events = load(args.export)
    samples = build_samples(rows, args.include_summaries)
    if not samples:
        print("导出文件里没有样本。")
        return 1

    # ---- 1. 样本概况 ----
    print(f"样本 {len(samples)} 条，时间 {ts(samples[0].post_time)} ~ {ts(samples[-1].post_time)}\n")
    print("各 App 样本（带金额 / 含退款字样）：")
    by_pkg: dict[str, list[Sample]] = defaultdict(list)
    for s in samples:
        by_pkg[s.pkg].append(s)
    for pkg, ss in sorted(by_pkg.items(), key=lambda kv: -len(kv[1])):
        with_amt = sum(1 for s in ss if s.amounts)
        refunds = sum(1 for s in ss if REFUND_WORDS.search(s.text))
        print(f"  {ss[0].label:<8} {len(ss):5d} 条   带金额 {with_amt:4d}   退款字样 {refunds:3d}   ({pkg})")
    print()

    # ---- 2. 中行 vs 平台配对 ----
    boc = [s for s in samples if s.pkg == BOC_PACKAGE and s.amounts]
    plat = [s for s in samples if s.pkg != BOC_PACKAGE and s.amounts]
    before_ms, after_ms = args.before * 60_000, args.after * 60_000

    candidates = []  # (|delay|, delay_ms, boc_idx, plat_idx, amount)
    for bi, b in enumerate(boc):
        for pi, p in enumerate(plat):
            d = b.post_time - p.post_time
            if not (-after_ms <= d <= before_ms):
                continue
            common = set(b.amounts) & set(p.amounts)
            for amt in common:
                candidates.append((abs(d), d, bi, pi, amt))
    candidates.sort()
    used_b, used_p, pairs = set(), set(), []
    for _, d, bi, pi, amt in candidates:
        if bi in used_b or pi in used_p:
            continue
        used_b.add(bi)
        used_p.add(pi)
        pairs.append((boc[bi], plat[pi], d / 1000, amt))

    print(f"中行带金额通知 {len(boc)} 条，平台带金额通知 {len(plat)} 条，配对成功 {len(pairs)} 对"
          f"（窗口：平台早于中行 ≤{args.before:g} 分钟，晚于中行 ≤{args.after:g} 分钟）\n")

    if pairs:
        delays = sorted(p[2] for p in pairs)
        print("延迟 = 中行通知时间 − 平台通知时间（秒）：")
        for name, q in [("min", 0), ("p10", .1), ("p50", .5), ("p90", .9), ("p95", .95), ("p99", .99), ("max", 1)]:
            print(f"  {name:>4}: {percentile(delays, q):8.1f}")
        print(f"  mean: {sum(delays) / len(delays):8.1f}\n")
        print("直方图：")
        print("\n".join(histogram(delays)))
        print()

        per_ch: dict[str, list[float]] = defaultdict(list)
        for b, p, d, _ in pairs:
            per_ch[p.label].append(d)
        print("按平台拆分（p50 / p90 / max，秒）：")
        for ch, ds in sorted(per_ch.items(), key=lambda kv: -len(kv[1])):
            ds.sort()
            print(f"  {ch:<8} n={len(ds):3d}  p50={percentile(ds, .5):7.1f}  p90={percentile(ds, .9):7.1f}  max={ds[-1]:7.1f}")
        print()

        p99 = percentile(delays, .99)
        suggestion = max(120, math.ceil((max(p99, delays[-1]) * 1.5) / 60) * 60)
        print(f"建议 M1 去重窗口：平台通知后等待中行确认 ≈ {suggestion / 60:.0f} 分钟"
              f"（max(p99, max) × 1.5，向上取整到分钟，至少 2 分钟）。样本少时请人工复核。\n")

    unmatched_b = [b for i, b in enumerate(boc) if i not in used_b]
    unmatched_p = [p for i, p in enumerate(plat) if i not in used_p and PAYMENT_WORDS.search(p.text)]
    print(f"未配对的中行通知 {len(unmatched_b)} 条（将来的 bank_only：刷卡、自动扣款、平台没发通知）：")
    for b in unmatched_b[: args.show]:
        print(f"  {ts(b.post_time)}  {[a / 100 for a in b.amounts]}  {short(b.text)}")
    print()
    print(f"未配对、像付款的平台通知 {len(unmatched_p)} 条（可能是零钱/余额/花呗支付，或金额没抽对）：")
    for p in unmatched_p[: args.show]:
        print(f"  {ts(p.post_time)}  {p.label:<6} {[a / 100 for a in p.amounts]}  {short(p.text)}")
    print()

    no_amt_boc = [s for s in samples if s.pkg == BOC_PACKAGE and not s.amounts]
    if no_amt_boc:
        print(f"注意：{len(no_amt_boc)} 条中行通知没抽到金额，请检查 AMOUNT_PATTERNS：")
        for s in no_amt_boc[: args.show]:
            print(f"  {ts(s.post_time)}  {short(s.text)}")
        print()

    # ---- 3. 监听断线时段 ----
    if events:
        gaps, down_at = [], None
        for e in sorted(events, key=lambda e: e["at"]):
            if e["type"] in ("disconnected", "permission_lost") and down_at is None:
                down_at = e["at"]
            elif e["type"] == "connected" and down_at is not None:  # 只有真正重连才算恢复采样
                gaps.append((down_at, e["at"]))
                down_at = None
        if down_at is not None:
            gaps.append((down_at, None))
        if gaps:
            print("监听断线时段（这些时间内的通知可能漏采）：")
            for a, b in gaps:
                dur = "至今" if b is None else f"{(b - a) / 60000:.1f} 分钟"
                print(f"  {ts(a)} ~ {ts(b) if b else '…'}  ({dur})")
            print()

    if args.pairs:
        with open(args.pairs, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["amount", "delay_s", "platform", "platform_time", "platform_text", "boc_time", "boc_text",
                        "platform_id", "boc_id"])
            for b, p, d, amt in sorted(pairs, key=lambda x: x[0].post_time):
                w.writerow([amt / 100, round(d, 1), p.label, ts(p.post_time), p.text, ts(b.post_time), b.text, p.id, b.id])
        print(f"配对明细已写入 {args.pairs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
