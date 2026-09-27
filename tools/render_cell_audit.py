#!/usr/bin/env python3
"""从主机探针 JSON 生成逐格核对材料（候选标注图 + 文本棋盘）。

目的：把「安全门通过」推进到「逐格可核对」。
本脚本只做可视化与导出，不改变任何模型判定结果，也不替代人工真值：
输出的每一格都是当前两模型流水线的候选结果，等待人工逐格确认或纠正。

用法（本机 venv 的 python 入口为零字节残留，改用系统解释器加 site-packages 路径）：
  PYTHONPATH=/workspace/xqdk-model-venv/lib/python3.12/site-packages /usr/bin/python3 \
    tools/render_cell_audit.py \
    --probe docs/evidence/xqdk-v5-medium-lite-host-probe-2026-09-27-rerun.json \
    --out docs/evidence/cell-audit

输出：
  README.md          逐样本的候选棋盘文本表与两候选差异（可提交）
  candidate_cells.json 逐样本候选逐格结果（可提交）
  *.png              逐样本标注图（体积大，不入版本库，供人工逐格核对）

输入约定：
  probe JSON 的 results[model][sample] 下含 mapped / zoom 两个候选，各带
  bbox=[x0,y0,x1,y1]（录屏帧坐标）、cells={"row,col": classId}、mapped、safe_geometry。
  录屏帧为 input_pipeline 中「最长边缩放到 1440」后的尺寸，截图为原始尺寸。
"""

import argparse
import json
import os

from PIL import Image, ImageDraw, ImageFont

# 类别 0..13 -> 棋子元字符（与 YoloDetections.PIECE_CODES 顺序一致）：
# 0..6 黑方 马象士将士(炮)卒；7..13 红方 车马仕帅相炮兵
CLASS_LETTER = "nbakrcpRNAKBCP"
CLASS_NAME = {
    "n": "黑马", "b": "黑象", "a": "黑士", "k": "黑将", "r": "黑车", "c": "黑炮", "p": "黑卒",
    "R": "红车", "N": "红马", "A": "红仕", "K": "红帅", "B": "红相", "C": "红炮", "P": "红兵",
}
COLS = 9
ROWS = 10


def load_font(size):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def pick_candidate(entry):
    """选取「几何安全候选」；优先级：棋子 ROI 复核(zoom) → 同格冲突阈值复核 → 整帧映射。

    三级都必须自身 safe_geometry 为真才被采用；否则回落到下一级，绝不伪造成通过。
    """
    zoom = entry.get("zoom")
    if zoom and zoom.get("safe_geometry"):
        return "zoom", zoom
    recovery = entry.get("threshold_recovery")
    if isinstance(recovery, dict):
        cand = recovery.get("mapped")
        if cand and cand.get("safe_geometry"):
            ptr = recovery.get("piece_threshold")
            btr = recovery.get("board_threshold")
            return f"threshold-recovery(piece={ptr},board={btr})", cand
    mapped = entry.get("mapped")
    if mapped and mapped.get("safe_geometry"):
        return "mapped", mapped
    return ("zoom", zoom) if zoom else ("mapped", mapped)


def grid_xy(bbox, row, col):
    x0, y0, x1, y1 = bbox
    x = x0 + (x1 - x0) * col / (COLS - 1)
    y = y0 + (y1 - y0) * row / (ROWS - 1)
    return x, y


def draw_board(img, bbox, cells, scale, font, out_path):
    x0, y0, x1, y1 = [v * scale for v in bbox]
    mx = (x1 - x0) * 0.05
    my = (y1 - y0) * 0.05
    box = (
        max(0, int(x0 - mx)), max(0, int(y0 - my)),
        min(img.width, int(x1 + mx)), min(img.height, int(y1 + my)),
    )
    crop = img.crop(box)
    up = 1.5
    crop = crop.resize((int(crop.width * up), int(crop.height * up)), Image.LANCZOS)
    canvas = crop.convert("RGB")
    d = ImageDraw.Draw(canvas)
    ox, oy = (x0 - box[0]) * up, (y0 - box[1]) * up
    w, h = (x1 - x0) * up, (y1 - y0) * up

    for c in range(COLS):
        cx = ox + w * c / (COLS - 1)
        d.line([(cx, oy), (cx, oy + h)], fill=(0, 180, 255), width=2)
    for r in range(ROWS):
        cy = oy + h * r / (ROWS - 1)
        d.line([(ox, cy), (ox + w, cy)], fill=(0, 180, 255), width=2)

    for key, cls in cells.items():
        row, col = [int(v) for v in key.split(",")]
        if not (0 <= row < ROWS and 0 <= col < COLS):
            continue
        px = ox + w * col / (COLS - 1)
        py = oy + h * row / (ROWS - 1)
        letter = CLASS_LETTER[cls] if 0 <= cls < len(CLASS_LETTER) else "?"
        red = letter.isupper()
        d.ellipse([px - 30, py - 30, px + 30, py + 30], outline=(255, 0, 0) if red else (0, 0, 0), width=3)
        d.text((px - 12, py - 18), letter, font=font, fill=(200, 0, 0) if red else (20, 20, 20),
               stroke_width=2, stroke_fill=(255, 255, 255))

    canvas.save(out_path)
    return canvas.size


def board_text(cells):
    grid = [["." for _ in range(COLS)] for _ in range(ROWS)]
    for key, cls in cells.items():
        row, col = [int(v) for v in key.split(",")]
        if 0 <= row < ROWS and 0 <= col < COLS:
            grid[row][col] = CLASS_LETTER[cls] if 0 <= cls < len(CLASS_LETTER) else "?"
    lines = ["   " + " ".join(str(c) for c in range(COLS))]
    for r in range(ROWS):
        lines.append(f"{r}  " + " ".join(grid[r]))
    return "\n".join(lines), sum(1 for r in grid for v in r if v != ".")


def cell_diff(a, b):
    keys = set(a) | set(b)
    out = []
    for k in sorted(keys):
        va, vb = a.get(k), b.get(k)
        if va != vb:
            fa = CLASS_LETTER[va] if va is not None else "-"
            fb = CLASS_LETTER[vb] if vb is not None else "-"
            out.append(f"{k}: {fa} vs {fb}")
    return out


def crop_focus(img, bbox, row, col, scale, out_path, pad_cells=0.75, out_px=420):
    """裁剪某个交叉点附近的局部放大图，供人工判断该格到底是哪个棋子。"""
    x0, y0, x1, y1 = [v * scale for v in bbox]
    cw = (x1 - x0) / (COLS - 1)
    ch = (y1 - y0) / (ROWS - 1)
    cx = x0 + cw * col
    cy = y0 + ch * row
    half_w = cw * (0.5 + pad_cells)
    half_h = ch * (0.5 + pad_cells)
    box = (max(0, int(cx - half_w)), max(0, int(cy - half_h)),
           min(img.width, int(cx + half_w)), min(img.height, int(cy + half_h)))
    crop = img.crop(box)
    k = max(1.0, out_px / float(max(1, min(crop.width, crop.height))))
    crop = crop.resize((int(crop.width * k), int(crop.height * k)), Image.LANCZOS)
    crop.convert("RGB").save(out_path)
    return box, crop.size


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--focus-row", type=int, default=None, help="另存该交叉点附近的局部放大图")
    ap.add_argument("--focus-col", type=int, default=None)
    args = ap.parse_args()

    data = json.load(open(args.probe, encoding="utf-8"))
    os.makedirs(args.out, exist_ok=True)
    font = load_font(34)
    report = ["# 逐格核对材料（候选，等待人工真值）", "",
              f"来源探针：`{os.path.relpath(args.probe)}`",
              f"生成时间（探针记录）：{data.get('generated_at_utc')}", "",
              "说明：图为当前两模型流水线的候选结果，网格为映射后的 9×10 交叉点，"
              "圆点标注该交叉点被判定的棋子。棋子字母：红方大写 R 车 / N 马 / A 仕 / K 帅 / B 相 / C 炮 / P 兵，"
              "黑方小写 r 车 / n 马 / a 士 / k 将 / b 象 / c 炮 / p 卒；下表 0 行是屏幕最上一行。",
              "核对方法：逐格比对实际棋子与标注；任何不一致格即为识别错误，请在下方表格记录。", ""]

    summary = {"samples": [], "per_model": {}}
    per_model_cells = {"Medium": {}, "Lite": {}}
    for model in ("Medium", "Lite"):
        per_model = data["results"].get(model, {})
        ok = 0
        for name, entry in per_model.items():
            kind, cand = pick_candidate(entry)
            if cand is None:
                continue
            cells = cand.get("cells") or {}
            path = next((s["path"] for s in data["sample_files"] if s["path"].endswith(name)), None)
            if not path or not os.path.exists(path):
                report.append(f"- {name}（{model}）：找不到原图，跳过")
                continue
            img = Image.open(path)
            cap = entry.get("capture") or [img.width, img.height]
            scale = img.width / float(cap[0])
            focus_lines = []
            if args.focus_row is not None and args.focus_col is not None:
                focus_dir = os.path.join(args.out, "focus")
                os.makedirs(focus_dir, exist_ok=True)
                fpath = os.path.join(focus_dir, f"{model}_r{args.focus_row}c{args.focus_col}_{name}")
                box, size = crop_focus(img, cand["bbox"], args.focus_row, args.focus_col, scale, fpath)
                focus_lines.append(
                    f"- 局部放大 r{args.focus_row}c{args.focus_col}：`{os.path.relpath(fpath, args.out)}`"
                    f"（原图区域 {box[0]},{box[1]}-{box[2]},{box[3]}，输出 {size[0]}×{size[1]}）"
                )
            out_png = os.path.join(args.out, f"{model}_{name}")
            size = draw_board(img, cand["bbox"], cells, scale, font, out_png)
            text, count = board_text(cells)
            other_kind = "mapped" if kind == "zoom" else "zoom"
            other = entry.get(other_kind)
            diff = cell_diff(cells, (other or {}).get("cells") or {}) if other else []
            if cand.get("safe_geometry"):
                ok += 1
            report += [
                f"## {model} · {name}", "",
                f"- 采用候选：`{kind}`；安全几何：{cand.get('safe_geometry')}；"
                f"棋子数：{cand.get('piece_n')}；映射：{cand.get('mapped')}；"
                f"红帅 {cand.get('redK')} / 黑将 {cand.get('blackK')}；标注图：`{os.path.relpath(out_png, args.out)}`（{size[0]}×{size[1]}）",
                f"- 两候选逐格差异（{other_kind} 对照）：{'；'.join(diff) if diff else '无'}",
                *focus_lines,
                "", "```text", text, "```", "",
            ]
            summary["samples"].append({
                "model": model, "sample": name, "candidate": kind,
                "safe_geometry": bool(cand.get("safe_geometry")), "pieces": count,
                "cells": cells, "overlay": out_png,
            })
            per_model_cells[model][name] = cells
        summary["per_model"][model] = {"safe_geometry_count": ok, "total": len(per_model)}

    report += ["## 两模型候选一致性（人工核对可据此减半）", "",
               "同一张图上两模型候选完全一致时，人工只需核对一次即可同时覆盖两个模型；"
               "不一致处即两模型分歧格，必须逐格看原图确认。", ""]
    names = [s["sample"] for s in summary["samples"] if s["model"] == "Medium"]
    agree = 0
    for name in names:
        diff = cell_diff(per_model_cells["Medium"].get(name, {}), per_model_cells["Lite"].get(name, {}))
        if diff:
            report.append(f"- {name}：{len(diff)} 处分歧 → {'；'.join(diff)}")
        else:
            agree += 1
            report.append(f"- {name}：一致")
    report += ["", f"一致 {agree}/{len(names)} 张；分歧张数 {len(names) - agree}。", ""]

    report += ["## 汇总", "",
               f"- Medium 几何安全候选：{summary['per_model'].get('Medium', {})}",
               f"- Lite 几何安全候选：{summary['per_model'].get('Lite', {})}",
               "- 该汇总仍是候选结果；逐格真值须由人工对上方棋盘逐格确认后回填。", ""]

    with open(os.path.join(args.out, "README.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(report))
    with open(os.path.join(args.out, "candidate_cells.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=1)
    print(json.dumps(summary["per_model"], ensure_ascii=False))
    print("images:", len(summary["samples"]))


if __name__ == "__main__":
    main()
