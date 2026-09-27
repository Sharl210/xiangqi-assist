# 逐格核对材料（候选，等待人工真值）

来源探针：`docs/evidence/xqdk-v5-medium-lite-host-probe-2026-09-27-rerun.json`
生成时间（探针记录）：2026-09-27T06:37:02.527048+00:00

说明：图为当前两模型流水线的候选结果，网格为映射后的 9×10 交叉点，圆点标注该交叉点被判定的棋子。棋子字母：红方大写 R 车 / N 马 / A 仕 / K 帅 / B 相 / C 炮 / P 兵，黑方小写 r 车 / n 马 / a 士 / k 将 / b 象 / c 炮 / p 卒；下表 0 行是屏幕最上一行。
核对方法：逐格比对实际棋子与标注；任何不一致格即为识别错误，请在下方表格记录。

## Medium · Screenshot_2026-09-26-20-13-29-85_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`zoom`；安全几何：True；棋子数：32；映射：32；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-13-29-85_cn.jj.chess.nearme.gamecenter.png`（2086×2241）
- 两候选逐格差异（mapped 对照）：2,0: - vs P；2,2: - vs P；2,4: C vs P；2,6: N vs P；2,8: - vs P；3,0: P vs -；3,2: P vs -；3,4: P vs -；3,6: P vs -；3,8: P vs -；4,7: - vs c；5,0: - vs p；5,1: - vs c；5,2: - vs p；5,4: - vs p；5,6: - vs p；5,7: c vs -；5,8: - vs p；6,0: p vs -；6,2: p vs -；6,4: p vs -；6,6: p vs -；6,8: p vs -；7,0: - vs r；7,1: c vs n；7,2: - vs b；7,3: - vs a；7,4: - vs k；7,5: - vs a；7,6: - vs b；7,7: - vs n；7,8: - vs r；9,0: r vs -；9,1: n vs -；9,2: b vs -；9,3: a vs -；9,5: a vs -；9,6: b vs -；9,7: n vs -；9,8: r vs -

```text
   0 1 2 3 4 5 6 7 8
0  R N B A K A B . R
1  . . . . . . . . .
2  . C . . C . N . .
3  P . P . P . P . P
4  . . . . . . . . .
5  . . . . . . . c .
6  p . p . p . p . p
7  . c . . . . . . .
8  . . . . . . . . .
9  r n b a k a b n r
```

## Medium · Screenshot_2026-09-26-20-13-33-00_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`zoom`；安全几何：True；棋子数：32；映射：32；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-13-33-00_cn.jj.chess.nearme.gamecenter.png`（2073×2220）
- 两候选逐格差异（mapped 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R N B A K A B . R
1  . . . . . . . . .
2  . C . . C . N . .
3  P . P . P . P . P
4  . . . . . . . . .
5  . . . . . . . c .
6  p . p . p . p . p
7  . c . . . . . . .
8  . . . . . . . . .
9  r n b a k a b n r
```

## Medium · Screenshot_2026-09-26-20-15-44-32_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：28；映射：28；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-15-44-32_cn.jj.chess.nearme.gamecenter.png`（2052×2238）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . K . . . .
2  . C c . . . . . B
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . N . . . . .
6  p . p . p . . r p
7  . . . . b . C . .
8  . . . . . . . . .
9  r n . a k a b . .
```

## Medium · Screenshot_2026-09-26-20-16-11-74_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：28；映射：28；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-16-11-74_cn.jj.chess.nearme.gamecenter.png`（2055×2232）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . . . . r .
2  . C c . K . . . B
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . N . . . . .
6  p . p . p . . . p
7  . . . . b . C . .
8  . . . . . . . . .
9  r n . a k a b . .
```

## Medium · Screenshot_2026-09-26-20-16-48-74_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：28；映射：28；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-16-48-74_cn.jj.chess.nearme.gamecenter.png`（2056×2235）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . . . . r .
2  . C c . K . . . B
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . . . . . . .
6  p . p . p N . . p
7  . . . . b . C . .
8  r . . . . . . . .
9  . n . a k a b . .
```

## Medium · Screenshot_2026-09-26-20-18-28-25_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：25；映射：25；红帅 1 / 黑将 1；标注图：`Medium_Screenshot_2026-09-26-20-18-28-25_cn.jj.chess.nearme.gamecenter.png`（2070×2253）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . B A . A . . R
1  . . c . . . . . .
2  . R . . K . . . B
3  P . P . . . . . P
4  . . . . . . . . .
5  . . . . c . . . .
6  p C p . p r . . p
7  . . . . b . C . .
8  . . . . . . . . .
9  . n . a k a b . .
```

## Medium · 一直说局面异常即使点击更新棋谱也没用.png

- 采用候选：`mapped`；安全几何：True；棋子数：8；映射：8；红帅 1 / 黑将 1；标注图：`Medium_一直说局面异常即使点击更新棋谱也没用.png`（2089×2250）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . . . . . R
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . . .
8  . . . . r p . . c
9  . . . . K . . C .
```

## Medium · 帅字都识别到错行了.png

- 采用候选：`mapped`；安全几何：True；棋子数：8；映射：8；红帅 1 / 黑将 1；标注图：`Medium_帅字都识别到错行了.png`（2074×2319）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . . . . . R
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . . .
8  . . . . r p . . c
9  . . . . K . . C .
```

## Medium · 识别出错.png

- 采用候选：`mapped`；安全几何：True；棋子数：10；映射：10；红帅 1 / 黑将 1；标注图：`Medium_识别出错.png`（2083×2305）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . b . . . .
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . C C
8  . . . r . p . . .
9  R . . . K . . . c
```

## Medium · 错误的识别结果。.png

- 采用候选：`mapped`；安全几何：True；棋子数：10；映射：10；红帅 1 / 黑将 1；标注图：`Medium_错误的识别结果。.png`（424×480）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . b . . . .
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . C C
8  R . . r . p . . c
9  . . . . K . . . .
```

## Lite · Screenshot_2026-09-26-20-13-29-85_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：33；映射：32；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-13-29-85_cn.jj.chess.nearme.gamecenter.png`（2061×2199）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R N B A K A B . R
1  . . . . . . . . .
2  . C . . C . N . .
3  P . P . P . P . P
4  . . . . . . . . .
5  . . . . . . . c .
6  p . p . p . p . p
7  . c . . . . . . .
8  . . . . . . . . .
9  r n b a k a b n r
```

## Lite · Screenshot_2026-09-26-20-13-33-00_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：33；映射：32；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-13-33-00_cn.jj.chess.nearme.gamecenter.png`（2044×2175）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R N B A K A B . R
1  . . . . . . . . .
2  . C . . C . N . .
3  P . P . P . P . P
4  . . . . . . . . .
5  . . . . . . . c .
6  p . p . p . p . p
7  . c . . . . . . .
8  . . . . . . . . .
9  r n b a k a b n r
```

## Lite · Screenshot_2026-09-26-20-15-44-32_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：27；映射：27；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-15-44-32_cn.jj.chess.nearme.gamecenter.png`（2064×2232）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . K . . . .
2  . C c . . . . . .
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . N . . . . .
6  p . p . p . . r p
7  . . . . b . C . .
8  . . . . . . . . .
9  r n . a k a b . .
```

## Lite · Screenshot_2026-09-26-20-16-11-74_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`threshold-recovery(piece=0.47,board=0.2)`；安全几何：True；棋子数：28；映射：28；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-16-11-74_cn.jj.chess.nearme.gamecenter.png`（2064×2214）
- 两候选逐格差异（zoom 对照）：2,8: C vs B

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . . . . r .
2  . C c . K . . . C
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . N . . . . .
6  p . p . p . . . p
7  . . . . b . C . .
8  . . . . . . . . .
9  r n . a k a b . .
```

## Lite · Screenshot_2026-09-26-20-16-48-74_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：28；映射：28；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-16-48-74_cn.jj.chess.nearme.gamecenter.png`（2073×2247）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  R . B A . A . . R
1  . . . . . . . r .
2  . C c . K . . . B
3  P . P . . . . . P
4  . . . . c . P . .
5  . . . . . . . . .
6  p . p . p N . . p
7  . . . . b . C . .
8  r . . . . . . . .
9  . n . a k a b . .
```

## Lite · Screenshot_2026-09-26-20-18-28-25_cn.jj.chess.nearme.gamecenter.png

- 采用候选：`mapped`；安全几何：True；棋子数：25；映射：25；红帅 1 / 黑将 1；标注图：`Lite_Screenshot_2026-09-26-20-18-28-25_cn.jj.chess.nearme.gamecenter.png`（2070×2226）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . B A . A . . R
1  . . c . . . . . .
2  . R . . K . . . C
3  P . P . . . . . P
4  . . . . . . . . .
5  . . . . c . . . .
6  p C p . p r . . p
7  . . . . b . C . .
8  . . . . . . . . .
9  . n . a k a b . .
```

## Lite · 一直说局面异常即使点击更新棋谱也没用.png

- 采用候选：`mapped`；安全几何：True；棋子数：7；映射：7；红帅 1 / 黑将 1；标注图：`Lite_一直说局面异常即使点击更新棋谱也没用.png`（2104×2317）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . . . . . .
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . . .
8  . . . . r p . . c
9  . . . . K . . C .
```

## Lite · 帅字都识别到错行了.png

- 采用候选：`mapped`；安全几何：True；棋子数：8；映射：8；红帅 1 / 黑将 1；标注图：`Lite_帅字都识别到错行了.png`（2059×2319）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . . . . . R
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . . .
8  . . . . r p . . c
9  . . . . K . . C .
```

## Lite · 识别出错.png

- 采用候选：`mapped`；安全几何：True；棋子数：10；映射：10；红帅 1 / 黑将 1；标注图：`Lite_识别出错.png`（2061×2305）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . b . . . .
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . C C
8  . . . r . p . . .
9  R . . . K . . . c
```

## Lite · 错误的识别结果。.png

- 采用候选：`mapped`；安全几何：True；棋子数：10；映射：10；红帅 1 / 黑将 1；标注图：`Lite_错误的识别结果。.png`（427×480）
- 两候选逐格差异（zoom 对照）：无

```text
   0 1 2 3 4 5 6 7 8
0  . . . k . . . . .
1  . . . . . . . . .
2  . . . . b . . . .
3  . . . . . . . . .
4  . . . . . . . . .
5  . . . . R . . . .
6  . . . . . . . . .
7  . . . . . . . C C
8  R . . r . p . . c
9  . . . . K . . . .
```

## 汇总

- Medium 几何安全候选：{'safe_geometry_count': 10, 'total': 10}
- Lite 几何安全候选：{'safe_geometry_count': 10, 'total': 10}
- 该汇总仍是候选结果；逐格真值须由人工对上方棋盘逐格确认后回填。
