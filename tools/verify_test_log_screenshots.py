#!/usr/bin/env python3
"""测试 log 全量截图的离线识别验收探针（Medium / Lite 两档）。

对 /workspace/测试log 下每一张截图，按 Android 端的真实判定链验收：

  1. 整屏推理 → 解码（含同类 NMS）→ 棋盘映射（含 DetectionBoardMapper.refineBoardGrid 的网格拟合）；
  2. 不安全时按模型 board 框放大复核一次（对应 Android 的定向复核 / BOARD_ZOOM）；
  3. 仍不安全时按阶梯阈值逐级下降（与 DetectionThresholdRecoveryPolicy 同口径：0.45*0.72^level，下限 0.16）。

判定“这张图能不能被接受”的规则与 Android 相同（AssistBoard.validate / invalidPiecePlacement /
kingsFacing / engineUnsafeReason + 同格类别冲突为 0 + 至少 MIN_RECOGNIZED_PIECES 子），
不使用“子数 >= 20”这类额外发明的要求——残局（帅仕 vs 将）同样必须被接受。

这是离线 Python 复现，不是 Android 运行时，也不等于人工逐格真值。
"""
from __future__ import annotations

import hashlib
import json
import os
from math import ceil
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

ROOT = Path("/workspace/测试log")
PROJECT = Path("/workspace/XQDK")
MODELS = {
    "Medium": PROJECT / "app/src/main/assets/yolov5m_xq_fp32.tflite",
    "Lite": PROJECT / "app/src/main/assets/yolov5n_xq_fp16.tflite",
}

# ---- Piece 常量（与 gamelogic/Piece.java 一致） ----
WSHUAI, WSHI, WXIANG, WMA, WJU, WPAO, WBING = 1, 2, 3, 4, 5, 6, 7
BJIANG, BSHI, BXIANG, BMA, BJU, BPAO, BZU = 8, 9, 10, 11, 12, 13, 14
# 模型类别 0..13 → Piece；14 = board
PIECE_OF_LABEL = [BMA, BXIANG, BSHI, BJIANG, BJU, BPAO, BZU,
                  WJU, WMA, WSHI, WSHUAI, WXIANG, WPAO, WBING, 0]
LABEL_BOARD = 14
MIN_RECOGNIZED_PIECES = 3

# 与 DetectionThresholdRecoveryPolicy 同口径：0.45 * 0.72^level，下限 0.16
BASE_CONF = 0.45
BASE_MARGIN = 0.05
BACKOFF = 0.72
FLOOR = 0.16
LEVELS = 5


def thresholds(level: int) -> tuple[float, float]:
    return (max(FLOOR, BASE_CONF * BACKOFF ** level), max(0.01, BASE_MARGIN * BACKOFF ** level))


def iou(a, b) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    aa = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    bb = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    return inter / (aa + bb - inter) if aa + bb - inter else 0.0


def decode(raw, fw, fh, conf, margin, offset=(0.0, 0.0), board_conf=None):
    scale = min(640 / fw, 640 / fh)
    nw, nh = round(fw * scale), round(fh * scale)
    px, py = (640 - nw) // 2, (640 - nh) // 2
    cands = []
    for row in raw:
        obj = float(row[4])
        floor = conf if board_conf is None else min(conf, board_conf)
        if obj < floor:
            continue
        scores = obj * row[5:]
        cls = int(np.argmax(scores))
        score = float(scores[cls])
        cls_conf = board_conf if cls == LABEL_BOARD and board_conf is not None else conf
        if score < cls_conf:
            continue
        second = float(np.max(np.delete(scores, cls)))
        if score - second < margin:
            continue
        cx, cy, bw, bh = map(float, row[:4])
        box = [(cx - bw / 2 - px) / scale, (cy - bh / 2 - py) / scale,
               (cx + bw / 2 - px) / scale, (cy + bh / 2 - py) / scale]
        if bw <= 0 or bh <= 0 or not (0.5 <= bw / bh <= 1.6):
            continue
        cands.append({"c": cls, "s": score, "x": (box[0] + box[2]) / 2 + offset[0],
                      "y": (box[1] + box[3]) / 2 + offset[1],
                      "w": box[2] - box[0], "h": box[3] - box[1], "b": box})
    cands.sort(key=lambda d: -d["s"])
    keep = []
    for d in cands:
        if any(k["c"] == d["c"] and iou(d["b"], k["b"]) > 0.45 for k in keep):
            continue
        keep.append(d)
    return filter_geometry(keep)


def _median(values):
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def filter_geometry(dets):
    """YoloPostprocessor 的比例/尺寸一致性过滤（与 Android 同口径）。"""
    def aspect_ok(d):
        return d["h"] > 0 and 0.50 <= d["w"] / d["h"] <= 1.60

    pieces = [d for d in dets if d["c"] != LABEL_BOARD and d["w"] > 0 and d["h"] > 0 and aspect_ok(d)]
    if not pieces:
        return [d for d in dets if d["c"] == LABEL_BOARD]
    median_w, median_h = _median([d["w"] for d in pieces]), _median([d["h"] for d in pieces])
    boards = [d for d in dets if d["c"] == LABEL_BOARD]
    board = max(boards, key=lambda d: d["s"], default=None)
    geom = None
    if board is not None and board["h"] > 0:
        ratio = board["w"] / board["h"]
        if 0.70 <= ratio <= 1.30 and board["w"] >= median_w * 7.0 and board["h"] >= median_h * 8.0:
            geom = (board["x"] - board["w"] / 2, board["y"] - board["h"] / 2,
                    board["x"] + board["w"] / 2, board["y"] + board["h"] / 2,
                    board["w"] / 8.0, board["h"] / 9.0)
    fallback_min = max(0.40, 0.55) if len(pieces) < 8 else 0.40
    out = []
    for d in dets:
        if d["c"] == LABEL_BOARD:
            out.append(d)
            continue
        if not aspect_ok(d):
            continue
        if geom is not None:
            cell_w, cell_h = geom[4], geom[5]
            in_board = (geom[0] - cell_w * 0.60 <= d["x"] <= geom[2] + cell_w * 0.60 and
                        geom[1] - cell_h * 0.60 <= d["y"] <= geom[3] + cell_h * 0.60)
            size_ok = cell_w * 0.25 <= d["w"] <= cell_w * 2.20 and cell_h * 0.25 <= d["h"] <= cell_h * 2.20
            if in_board and size_ok:
                out.append(d)
        elif (median_w * fallback_min <= d["w"] <= median_w * 2.20 and
              median_h * fallback_min <= d["h"] <= median_h * 2.20):
            out.append(d)
    return out


# ---- DetectionBoardMapper.fitGridAxis / refineBoardGrid 的等价移植 ----
def fit_axis(values, board0, board1, cell_count):
    base = (board1 - board0) / float(cell_count)
    if base <= 0 or len(values) < 3:
        return None
    min_support = max(3, ceil(len(values) * 0.50))
    best = None

    def consider(origin, step):
        nonlocal best
        if not np.isfinite(origin) or not np.isfinite(step) or step <= 0:
            return
        if origin > board1 + base * 0.75 or origin + cell_count * step < board0 - base * 0.75:
            return
        residuals, support = [], 0
        for value in values:
            idx = int(round((value - origin) / step))
            if idx < 0 or idx > cell_count:
                continue
            residual = abs(value - (origin + idx * step)) / step
            residuals.append(residual)
            if residual <= 0.32:
                support += 1
        if support < min_support or not residuals:
            return
        median = sorted(residuals)[len(residuals) // 2]
        penalty = abs(origin - board0) / base + abs(step - base) / base
        if best is None or support > best[2] or (
            support == best[2] and (median < best[3] - 1e-6 or
                                    (abs(median - best[3]) <= 1e-6 and penalty < best[4]))):
            best = (origin, step, support, median, penalty)

    for step_index in range(41):
        step = base * (0.80 + step_index * 0.01)
        for shift_index in range(45):
            consider(board0 + (-0.45 + shift_index * 0.025) * base, step)
        for value in values:
            for index in range(cell_count + 1):
                consider(value - index * step, step)
    return best


def refine_board_grid(x0, y0, x1, y1, pieces):
    base_w, base_h = (x1 - x0) / 8.0, (y1 - y0) / 9.0
    if base_w <= 0 or base_h <= 0 or len(pieces) < 3:
        return None
    fit = [p for p in pieces
           if x0 - base_w * 0.75 <= p["x"] <= x1 + base_w * 0.75
           and y0 - base_h * 0.75 <= p["y"] <= y1 + base_h * 0.75]
    if len(fit) < 3:
        return None
    xf = fit_axis([p["x"] for p in fit], x0, x1, 8)
    yf = fit_axis([p["y"] for p in fit], y0, y1, 9)
    if xf is None or yf is None:
        return None
    support_limit = max(3, ceil(len(pieces) * 0.65))
    residuals = []
    for p in fit:
        col = int(round((p["x"] - xf[0]) / xf[1]))
        row = int(round((p["y"] - yf[0]) / yf[1]))
        if not (0 <= col <= 8 and 0 <= row <= 9):
            continue
        rx = abs(p["x"] - (xf[0] + col * xf[1])) / xf[1]
        ry = abs(p["y"] - (yf[0] + row * yf[1])) / yf[1]
        residuals.append(max(rx, ry))
    close = sum(1 for r in residuals if r <= 0.32)
    median = sorted(residuals)[len(residuals) // 2] if residuals else float("inf")
    if close < support_limit or median > 0.32:
        return None
    if xf[0] > x1 + base_w * 0.75 or xf[0] + 8 * xf[1] < x0 - base_w * 0.75:
        return None
    if yf[0] > y1 + base_h * 0.75 or yf[0] + 9 * yf[1] < y0 - base_h * 0.75:
        return None
    return xf[0], yf[0], xf[0] + 8 * xf[1], yf[0] + 9 * yf[1]


def map_board(dets):
    pieces = [d for d in dets if d["c"] != LABEL_BOARD]
    if len(pieces) < MIN_RECOGNIZED_PIECES:
        return None
    board = max((d for d in dets if d["c"] == LABEL_BOARD), key=lambda d: d["s"], default=None)
    avg_w = sum(p["w"] for p in pieces) / len(pieces)
    avg_h = sum(p["h"] for p in pieces) / len(pieces)
    box = None
    if board:
        x0, y0 = board["x"] - board["w"] / 2, board["y"] - board["h"] / 2
        x1, y1 = board["x"] + board["w"] / 2, board["y"] + board["h"] / 2
        ratio = (x1 - x0) / (y1 - y0) if y1 > y0 else 0.0
        if 0.7 <= ratio <= 1.3 and (x1 - x0) >= avg_w * 7 and (y1 - y0) >= avg_h * 8:
            refined = refine_board_grid(x0, y0, x1, y1, pieces)
            box = refined if refined is not None else (x0, y0, x1, y1)
    if box is None:
        if len(pieces) < 12:
            return None
        x0 = min(p["x"] for p in pieces); x1 = max(p["x"] for p in pieces)
        y0 = min(p["y"] for p in pieces); y1 = max(p["y"] for p in pieces)
        if x1 <= x0 or y1 <= y0 or not (0.7 <= (x1 - x0) / (y1 - y0) <= 1.3):
            return None
        box = (x0, y0, x1, y1)
    x0, y0, x1, y1 = box
    gx, gy = (x1 - x0) / 8.0, (y1 - y0) / 9.0
    if gx <= 0 or gy <= 0:
        return None
    cells, scores, conflicts = {}, {}, []
    for d in pieces:
        col = int(round((d["x"] - x0) / gx))
        row = int(round((d["y"] - y0) / gy))
        if not (0 <= col < 9 and 0 <= row < 10):
            continue
        key = (row, col)
        piece = PIECE_OF_LABEL[d["c"]]
        if piece == 0:
            continue
        if key in cells and cells[key] != piece:
            conflicts.append((row, col))
        if key not in cells or d["s"] > scores[key]:
            cells[key], scores[key] = piece, d["s"]
    return {"board": board is not None, "cells": cells, "scores": scores,
            "conflicts": sorted(set(conflicts)), "bbox": [round(v) for v in box]}


def detect_orientation(raw):
    red_row = black_row = -1
    for y in range(10):
        for x in range(9):
            if raw[y][x] == WSHUAI:
                red_row = y
            elif raw[y][x] == BJIANG:
                black_row = y
    if red_row >= 0 and red_row > 4:
        return "STANDARD"
    if black_row >= 0 and black_row > 4:
        return "FLIPPED"
    if red_row >= 0 and red_row <= 4:
        return "FLIPPED"
    if black_row >= 0 and black_row <= 4:
        return "STANDARD"
    return None


def to_canonical(raw, orientation):
    out = [[0] * 9 for _ in range(10)]
    for y in range(10):
        for x in range(9):
            p = raw[y][x]
            if not p:
                continue
            if orientation == "STANDARD":
                out[y][x] = p
            else:
                out[9 - y][8 - x] = p
    return out


# ---- BoardSanitizer.sanitize 的等价移植（只修“多认”） ----
MAX_COUNT = {WSHUAI: 1, BJIANG: 1, WSHI: 2, WXIANG: 2, WMA: 2, WJU: 2, WPAO: 2,
             BSHI: 2, BXIANG: 2, BMA: 2, BJU: 2, BPAO: 2, WBING: 5, BZU: 5}


def sanitize(cells, scores, orientation):
    out = dict(cells)
    notes = []
    for (y, x), piece in list(out.items()):
        cy = y if orientation == "STANDARD" else 9 - y
        if piece == WBING and cy > 6:
            del out[(y, x)]; notes.append("丢弃位置不可能的红兵")
        elif piece == BZU and cy < 3:
            del out[(y, x)]; notes.append("丢弃位置不可能的黑卒")
    for piece, limit in MAX_COUNT.items():
        holders = sorted([k for k, v in out.items() if v == piece],
                         key=lambda k: scores.get(k, 0.0))
        while len(holders) > limit:
            victim = holders.pop(0)
            del out[victim]
            notes.append(f"剔除超限棋子:{piece}")
    return out, notes


# ---- AssistBoard.validate / invalidPiecePlacement / kingsFacing / kingAttacked 的等价移植 ----
def validate(board):
    issues = []
    counts = {}
    for row in board:
        for p in row:
            if p:
                counts[p] = counts.get(p, 0) + 1
    if counts.get(WSHUAI, 0) != 1:
        issues.append(f"红帅数量异常:{counts.get(WSHUAI, 0)}")
    if counts.get(BJIANG, 0) != 1:
        issues.append(f"黑将数量异常:{counts.get(BJIANG, 0)}")
    limits = {WSHI: 2, WXIANG: 2, WMA: 2, WJU: 2, WPAO: 2, WBING: 5,
              BSHI: 2, BXIANG: 2, BMA: 2, BJU: 2, BPAO: 2, BZU: 5}
    for k, v in limits.items():
        if counts.get(k, 0) > v:
            issues.append(f"数量超限:{k}={counts[k]}")
    if sum(counts.values()) > 32:
        issues.append("总子数超限")
    return issues


RED_ADVISOR = {(3, 9), (5, 9), (4, 8), (3, 7), (5, 7)}
BLACK_ADVISOR = {(3, 0), (5, 0), (4, 1), (3, 2), (5, 2)}
RED_ELEPHANT = {(2, 9), (6, 9), (0, 7), (4, 7), (8, 7), (2, 5), (6, 5)}
BLACK_ELEPHANT = {(2, 0), (6, 0), (0, 2), (4, 2), (8, 2), (2, 4), (6, 4)}


def invalid_placement(board):
    for y in range(10):
        for x in range(9):
            p = board[y][x]
            if not p:
                continue
            if p == WSHUAI and not (3 <= x <= 5 and 7 <= y <= 9):
                return "红帅不在九宫内"
            if p == BJIANG and not (3 <= x <= 5 and 0 <= y <= 2):
                return "黑将不在九宫内"
            if p == WSHI and (x, y) not in RED_ADVISOR:
                return "红仕不在合法点位"
            if p == BSHI and (x, y) not in BLACK_ADVISOR:
                return "黑士不在合法点位"
            if p == WXIANG and (x, y) not in RED_ELEPHANT:
                return "红相不在合法点位"
            if p == BXIANG and (x, y) not in BLACK_ELEPHANT:
                return "黑象不在合法点位"
            if p == WBING and y > 6:
                return "红兵出现在不可能的位置"
            if p == BZU and y < 3:
                return "黑卒出现在不可能的位置"
    return None


def find_king(board, red):
    target = WSHUAI if red else BJIANG
    for y in range(10):
        for x in range(9):
            if board[y][x] == target:
                return x, y
    return None


def kings_facing(board):
    r = find_king(board, True)
    b = find_king(board, False)
    if r is None or b is None or r[0] != b[0]:
        return False
    x = r[0]
    for y in range(min(r[1], b[1]) + 1, max(r[1], b[1])):
        if board[y][x]:
            return False
    return True


def king_attacked(board, red):
    king = find_king(board, red)
    if king is None:
        return True
    kx, ky = king
    foe_red = not red
    rook = WJU if foe_red else BJU
    cannon = WPAO if foe_red else BPAO
    horse = WMA if foe_red else BMA
    pawn = WBING if foe_red else BZU

    def in_board(x, y):
        return 0 <= x < 9 and 0 <= y < 10

    def is_foe(p):
        if not p:
            return False
        return (p <= WBING) == foe_red

    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        x, y, screen = kx + dx, ky + dy, 0
        while in_board(x, y):
            p = board[y][x]
            if p:
                if screen == 0 and is_foe(p) and p == rook:
                    return True
                if screen == 1 and is_foe(p) and p == cannon:
                    return True
                screen += 1
                if screen >= 2:
                    break
            x += dx; y += dy
    for dx, dy in ((-1, -2), (1, -2), (-1, 2), (1, 2), (-2, -1), (2, -1), (-2, 1), (2, 1)):
        hx, hy = kx + dx, ky + dy
        if not in_board(hx, hy) or board[hy][hx] != horse:
            continue
        tx, ty = kx - hx, ky - hy
        if abs(tx) == 2:
            leg_x, leg_y = hx + tx // 2, hy
        else:
            leg_x, leg_y = hx, hy + ty // 2
        if in_board(leg_x, leg_y) and board[leg_y][leg_x] == 0:
            return True
    if foe_red:
        if in_board(kx, ky + 1) and board[ky + 1][kx] == WBING:
            return True
        if ky <= 4:
            if in_board(kx - 1, ky) and board[ky][kx - 1] == WBING:
                return True
            if in_board(kx + 1, ky) and board[ky][kx + 1] == WBING:
                return True
    else:
        if in_board(kx, ky - 1) and board[ky - 1][kx] == BZU:
            return True
        if ky >= 5:
            if in_board(kx - 1, ky) and board[ky][kx - 1] == BZU:
                return True
            if in_board(kx + 1, ky) and board[ky][kx + 1] == BZU:
                return True
    return False


def engine_unsafe(board, red_go):
    """AssistBoard.engineUnsafeReason 的等价移植。"""
    v = validate(board)
    if v:
        return v[0]
    if find_king(board, True) is None:
        return "未识别到红帅"
    if find_king(board, False) is None:
        return "未识别到黑将"
    if kings_facing(board):
        return "将帅照面"
    p = invalid_placement(board)
    if p:
        return p
    if king_attacked(board, not red_go):
        return "非当前走子方王被将军"
    return None


def attempt(dets):
    """一次推理结果的映射 + Android 安全门判定；不做任何恢复。返回 (结果, 诊断)。"""
    mapped = map_board(dets)
    if mapped is None:
        return None, {"reason": "无棋盘映射", "cells": None, "pieceCount": 0, "conflicts": 0}
    info = {"reason": None, "cells": mapped["cells"], "pieceCount": len(mapped["cells"]),
            "conflicts": len(mapped["conflicts"])}
    if mapped["conflicts"]:
        info["reason"] = f"同格冲突{len(mapped['conflicts'])}"
        return None, info
    raw = [[0] * 9 for _ in range(10)]
    for (y, x), piece in mapped["cells"].items():
        raw[y][x] = piece
    orientation = detect_orientation(raw)
    if orientation is None:
        info["reason"] = "朝向未知"
        return None, info
    cells, _notes = sanitize(mapped["cells"], mapped["scores"], orientation)
    raw2 = [[0] * 9 for _ in range(10)]
    for (y, x), piece in cells.items():
        raw2[y][x] = piece
    board = to_canonical(raw2, orientation)
    count = sum(1 for row in board for p in row if p)
    if count < MIN_RECOGNIZED_PIECES:
        info["reason"] = "子数不足"
        return None, info
    # Android 会用 forcedTurnFromCheck 兜住“只有一方走子才合法”的局面，因此这里按“存在
    # 一个可安全发送的走子方”判定，而不是死守某一侧。
    reasons = [r for r in (engine_unsafe(board, True), engine_unsafe(board, False)) if r]
    if len(reasons) == 2:
        info["reason"] = reasons[0]
        return None, info
    return {"board": board, "orientation": orientation, "mapped": count, "conflicts": 0}, info


def load_frame(path: Path):
    im = Image.open(path).convert("RGB")
    sw, sh = im.size
    scale = min(1.0, 1440 / max(sw, sh))
    cw, ch = max(1, round(sw * scale)), max(1, round(sh * scale))
    return (im.resize((cw, ch), Image.Resampling.BILINEAR) if (cw, ch) != (sw, sh) else im), cw, ch


def infer(interp, di, do, frame, crop=None, offset=(0.0, 0.0)):
    src = frame if crop is None else frame.crop(crop)
    w, h = src.size
    scale = min(640 / w, 640 / h)
    nw, nh = round(w * scale), round(h * scale)
    px, py = (640 - nw) // 2, (640 - nh) // 2
    canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
    canvas[py:py + nh, px:px + nw] = np.asarray(src.resize((nw, nh), Image.Resampling.BILINEAR))
    interp.set_tensor(di["index"], canvas.astype(np.float32)[None] / 255)
    interp.invoke()
    return interp.get_tensor(do["index"])[0], w, h, offset


def _crop_of(box, w, h, margin_cells):
    x0, y0, x1, y1 = box
    cell_w, cell_h = (x1 - x0) / 8.0, (y1 - y0) / 9.0
    crop = (max(0, int(x0 - cell_w * margin_cells)), max(0, int(y0 - cell_h * margin_cells)),
            min(w, int(x1 + cell_w * margin_cells)), min(h, int(y1 + cell_h * margin_cells)))
    if crop[2] - crop[0] < 64 or crop[3] - crop[1] < 64:
        return None
    return crop


def try_board(interp, di, do, frame) -> dict:
    """复现 Android 的恢复链：整屏 → 冲突复核 → 低阈值救援 → 棋盘框放大 → 棋子包围盒放大，
    并在每一档阶梯阈值上重复整套流程。返回该图该模型的最佳结果。"""
    raw, w, h, off = infer(interp, di, do, frame)
    best = {"via": "none", "mapped": 0, "reason": "无候选"}

    for level in range(LEVELS):
        conf, margin = thresholds(level)
        dets = decode(raw, w, h, conf, margin, off)
        result, info = attempt(dets)

        if result:
            result["via"] = f"full@level{level}"
            return result

        # 棋盘框放大复核（Android BOARD_ZOOM：棋盘框存在且当前棋面不完整时再做一次高分辨率推理）
        board = max((d for d in dets if d["c"] == LABEL_BOARD), key=lambda d: d["s"], default=None)
        if board:
            crop = _crop_of(board["b"], w, h, 0.6)
            if crop:
                raw2, w2, h2, _ = infer(interp, di, do, frame, crop=crop, offset=(crop[0], crop[1]))
                result2, info2 = attempt(decode(raw2, w2, h2, conf, margin, (crop[0], crop[1])))
                if result2:
                    result2["via"] = f"zoom@level{level}"
                    return result2
                info["reason"] = f"{info['reason']}|zoom:{info2['reason']}"

        # 同格冲突：按 0.47 / 棋盘 0.20 复核（Android 的口径），复核结果必须与基线格子完全一致
        if info["conflicts"] > 0 and info["cells"] is not None:
            conservative = decode(raw, w, h, 0.47, 0.05, off, board_conf=0.20)
            result3, info3 = attempt(conservative)
            if result3 and info3["cells"] == info["cells"]:
                result3["via"] = f"conflict-recheck@0.47(lv{level})"
                return result3

        # 缺王/非法落点/无棋面：低置信度救援（0.24 / margin 0.02），子数不得减少
        if info["conflicts"] == 0:
            rescue = decode(raw, w, h, 0.24, 0.02, off)
            result4, info4 = attempt(rescue)
            if result4 and result4["mapped"] >= info["pieceCount"]:
                result4["via"] = f"rescue@0.24(lv{level})"
                return result4
            info["reason"] = f"{info['reason']}|rescue:{info4['reason']}"

        # 棋盘类别漏检：按棋子包围盒放大复核（DetectionRecoveryCropPolicy.fromPieceDetections）
        if board is None:
            pieces = [d for d in dets if d["c"] != LABEL_BOARD]
            if len(pieces) >= 12:
                px0 = min(d["x"] for d in pieces); px1 = max(d["x"] for d in pieces)
                py0 = min(d["y"] for d in pieces); py1 = max(d["y"] for d in pieces)
                if px1 > px0 and py1 > py0 and 0.70 <= (px1 - px0) / (py1 - py0) <= 1.30:
                    crop = _crop_of((px0, py0, px1, py1), w, h, 1.5)
                    if crop:
                        raw5, w5, h5, _ = infer(interp, di, do, frame, crop=crop,
                                                offset=(crop[0], crop[1]))
                        result5, info5 = attempt(decode(raw5, w5, h5, conf, margin, (crop[0], crop[1])))
                        if result5:
                            result5["via"] = f"piecegrid-zoom@level{level}"
                            return result5
                        info["reason"] = f"{info['reason']}|piecegrid:{info5['reason']}"

        if best["via"] == "none":
            best["reason"] = info["reason"]
            best["mapped"] = len(dets)
    return best


def main() -> None:
    scope = os.environ.get("XQDK_SCOPE", "all")
    if scope == "all":
        images = sorted(ROOT.rglob("*.png")) + sorted(ROOT.rglob("*.jpg"))
    else:
        images = sorted((ROOT / scope).glob("*.png"))
    only = os.environ.get("XQDK_MODEL")
    models = {k: v for k, v in MODELS.items() if only is None or k == only}
    report = {"total": len(images), "scope": scope, "models": {}}
    for name, path in models.items():
        interp = Interpreter(model_path=str(path), num_threads=1)
        interp.allocate_tensors()
        di = interp.get_input_details()[0]
        do = interp.get_output_details()[0]
        rows = {}
        for img in images:
            frame, _cw, _ch = load_frame(img)
            result = try_board(interp, di, do, frame)
            ok = result.get("via", "none") != "none"
            rel = str(img.relative_to(ROOT))
            rows[rel] = {
                "pass": ok,
                "via": result.get("via"),
                "mapped": result.get("mapped"),
                "orientation": result.get("orientation"),
                "conflicts": result.get("conflicts"),
                "reason": result.get("reason"),
                "sha256": hashlib.sha256(img.read_bytes()).hexdigest()[:16],
            }
            print(f"[{name}] {'PASS' if ok else 'FAIL'} {img.name} via={result.get('via')} "
                  f"mapped={result.get('mapped')} orientation={result.get('orientation')} "
                  f"conf={result.get('conflicts')}"
                  + ("" if ok else f" reason={result.get('reason')}"))
        passed = sum(1 for r in rows.values() if r["pass"])
        report["models"][name] = {"passed": passed, "total": len(images), "rows": rows}
    out = PROJECT / "docs/evidence/test-log-screenshot-acceptance.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, data in report["models"].items():
        print(f"== {name}: {data['passed']}/{data['total']} PASS")
    print(f"report={out}")


if __name__ == "__main__":
    main()
