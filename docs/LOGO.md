# 象棋辅助 Logo 设计记录

## 设计方案

新 Logo 采用“棋盘＋马头＋印章”的组合：

- 深蓝底色：对应棋盘与工具属性，缩小后仍保持清晰；
- 金色圆形棋盘：表达中国象棋和稳定、专业的辅助工具定位；
- 金色马头：取自中国象棋的“马”，避免直接使用复杂棋子照片或过多文字；
- 红色小印章：增加中国象棋文化识别度，并作为视觉重心。

图标使用纯绘制 PNG，不依赖外部图片或第三方版权素材。已生成 `ldpi`、`mdpi`、`hdpi`、`xhdpi`、`xxhdpi`、`xxxhdpi` 六组尺寸，同时替换普通图标、圆形图标和 Play Store 图标。

## 资源位置

```text
app/src/main/res/drawable-ldpi/ic_launcher.png
app/src/main/res/drawable-mdpi/ic_launcher.png
app/src/main/res/drawable-hdpi/ic_launcher.png
app/src/main/res/drawable-xhdpi/ic_launcher.png
app/src/main/res/drawable-xxhdpi/ic_launcher.png
app/src/main/res/drawable-xxxhdpi/ic_launcher.png
app/src/main/res/playstore-icon.png
```

`AndroidManifest.xml` 仍通过 `@drawable/ic_launcher` 和 `@drawable/ic_launcher_round` 引用，因此该替换会进入正式 APK 资源链。

## 当前限制

本轮没有连接真机，尚未在不同 Android 启动器、圆形裁切规则和深色主题下进行视觉实测。安装新 APK 后应继续检查启动器图标、最近任务图标和应用设置页显示效果。
