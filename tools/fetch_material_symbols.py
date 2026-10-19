#!/usr/bin/env python3
"""
从 Google 官方 CDN 拉 Material Symbols，转成 Android vector drawable。

为什么要有这个脚本
------------------
图标最怕的是「今天手画一个、明天截图抠一个」，三个月后整个 app 像拼贴的。
Material Symbols 是 Google 自己的图标系统（Apache 2.0），和 Material 3 同一套网格
（24dp 画布、960 单位视窗、同样的笔画重量），所以只要所有图标都从这一个源头来，
风格天然统一。

用法
----
    # 下载到默认位置
    python3 tools/fetch_material_symbols.py filter_alt open_in_new add check

    # 换输出目录
    python3 tools/fetch_material_symbols.py --out /tmp/icons search delete

    # 看某个图标存不存在（名字错了会 404）
    python3 tools/fetch_material_symbols.py --dry-run cloud_off

图标名去这里查：https://fonts.google.com/icons
（勾选 Material Symbols → Outlined → 24dp，复制到的名字就是这里要填的）
"""

import argparse
import pathlib
import re
import sys
import urllib.error
import urllib.request

BASE = (
    "https://fonts.gstatic.com/s/i/short-term/release/"
    "materialsymbolsoutlined/{name}/default/24px.svg"
)

# 注意：脚本在项目根目录（~/Desktop/11-荷包App）下运行，Gradle 工程在 HoBao/ 子目录
DEFAULT_OUT = pathlib.Path("HoBao/app/src/main/res/drawable")

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Material Symbols Outlined · {name}
  来源 https://fonts.google.com/icons · Apache License 2.0

  ⚠️ 自动生成，不要手改。
     要换图标：改调用脚本的参数，重新跑 tools/fetch_material_symbols.py
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
    <!--
      Material Symbols 的 SVG 坐标系是 y ∈ [{ymin}, {ymax}]，而 Android 的 viewport
      从 0 开始 —— 不整体下移的话图标会画在画布外面，看起来就是"图标没了"。
    -->
    <group android:translateY="{translate}">
{paths}
    </group>
</vector>
"""


def fetch(name: str, timeout: int = 20) -> str:
    url = BASE.format(name=name)
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.read().decode("utf-8")


def to_vector(name: str, svg: str) -> str:
    box = re.search(r'viewBox="([-\d.\s]+)"', svg)
    if not box:
        raise ValueError("SVG 里没有 viewBox")
    parts = [float(v) for v in box.group(1).split()]
    min_x, min_y, vw, vh = parts

    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise ValueError("SVG 里没有 <path d=...>")

    body = "\n".join(
        '        <path\n'
        '            android:fillColor="#FF000000"\n'
        f'            android:pathData="{d}" />'
        for d in paths
    )

    return TEMPLATE.format(
        name=name,
        vw=_fmt(vw),
        vh=_fmt(vh),
        ymin=_fmt(min_y),
        ymax=_fmt(min_y + vh),
        translate=_fmt(-min_y),
        paths=body,
    )


def _fmt(x: float) -> str:
    return str(int(x)) if float(x).is_integer() else str(x)


def main() -> int:
    ap = argparse.ArgumentParser(description="下载 Material Symbols 并转成 Android vector XML")
    ap.add_argument("names", nargs="+", help="图标名，例如 filter_alt open_in_new")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="输出目录（默认 app/src/main/res/drawable）")
    ap.add_argument("--dry-run", action="store_true", help="只检查名字对不对，不写文件")
    args = ap.parse_args()

    out = pathlib.Path(args.out)
    if not args.dry_run:
        out.mkdir(parents=True, exist_ok=True)

    ok, bad = 0, []
    for name in args.names:
        try:
            svg = fetch(name)
            xml = to_vector(name, svg)
        except urllib.error.HTTPError as e:
            bad.append((name, f"HTTP {e.code}（名字写错了？去 fonts.google.com/icons 查）"))
            continue
        except Exception as e:  # noqa: BLE001
            bad.append((name, str(e)))
            continue

        if args.dry_run:
            print(f"  ✓ {name}  (可下载)")
        else:
            target = out / f"ic_{name}.xml"
            target.write_text(xml, encoding="utf-8")
            print(f"  ✓ {name} → {target}")
        ok += 1

    for name, why in bad:
        print(f"  ✗ {name}: {why}", file=sys.stderr)

    print(f"\n{ok}/{len(args.names)} 成功" + (f"，{len(bad)} 失败" if bad else ""))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
