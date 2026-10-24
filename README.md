# 琉光（原生）：基于民间 4.5UI 开发的原生卡片（v0.3）

比亚迪车机上的 3D 歌词应用：放进车机的多窗口（双画中画）或悬浮窗口里用，显示正在放的歌——封面、歌名歌手、整首歌词（当前句亮起）、上一首 / 播放暂停 / 下一首、进度。

- 画面：封面主色做成的极光背景、侧着的玻璃封面（换歌时翻面）、贴在滚轮上的歌词、水面倒影、浮在水上的玻璃控制条、玻璃上的雨珠。
- 歌和歌词来自宿主（见下面"对接宿主"）；雨珠"自动"时读车机自带的比亚迪天气应用（`com.byd.weatherdata` 的数据接口，不经过宿主）。
- 用 Java + OpenGL ES 3.0 直接画，不用浏览器内核；最低 Android 9，车机是 Android 10。

主要文件（`src/com/x3card/liuguang/`）：

| 文件 | 做什么 |
|---|---|
| `MainActivity.java` | 入口：连宿主、授权、订阅媒体和日夜模式 |
| `Uiplus.java` | 宿主的外部应用接口（Binder），文件开头有完整说明 |
| `MediaState.java` | 媒体快照、整首歌词、播放位置、封面的处理 |
| `Scene.java` | 3D 场景、着色器、手势、出帧 |
| `Rain.java` / `RainPick.java` / `BydWeather.java` | 雨珠、要不要下、车机天气 |
| `G.java` / `Txt.java` / `Palette.java` / `Spring.java` | GL 工具、歌词贴图和折行、封面取色、弹簧 |
| `Ui.java` / `Settings.java` / `InfoView.java` / `FullActivity.java` | 设置面板、设置存取、连不上时的说明、全屏页 |

assets 里是歌词字体得意黑（官方 ttf 原样）和它的许可。

## 目录

- `AndroidManifest.xml`、`res/`、`src/`：工程本身（包名 `com.x3card.liuguang`）。
- `assets/`：打进 APK 的资源，已经做好，直接用：
  - `LICENSE-SmileySans.txt`（4 KB）
  - `SmileySans-Oblique.ttf`（2.5 MB）
- `build.mjs`、`apkbuild.mjs`：打包脚本（只用 Node 自带的模块）。

## 打包

不用 Gradle、Android Studio，脚本直接调 Android 的构建工具。要装：

- Node.js 18 以上；
- JDK 17（`javac`、`java`、`keytool`）；
- Android SDK 里的 build-tools（34 测过，要带 aapt、aapt2、zipalign、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`）和 `platforms;android-30`（用它的 android.jar）。
  用 sdkmanager 装：`sdkmanager "build-tools;34.0.0" "platforms;android-30"`。

告诉脚本工具在哪：设 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）和 `JAVA_HOME`；也可以直接设 `ANDROID_BUILD_TOOLS`（build-tools 目录）、`ANDROID_JAR`（android.jar）。JDK 在 PATH 里也行。

```
node build.mjs            → out/琉光（原生） v0.3.apk
node build.mjs --install  → 打完用 adb install -r 装上（adb 要在 PATH 里）
```

中间产物在 `build/`。改版本：`AndroidManifest.xml` 的 `versionCode`（每发一版加 1）和 `versionName`。

## 签名

第一次打包会在这个目录生成签名钥匙 `release.keystore`（密码 android），之后一直用它，**要备份、别外传**：

- 宿主按应用的签名认人，授权记在签名上。换了钥匙，车上要重新授权。
- 安卓不让不同签名的包覆盖安装：车上装着别人签的同名应用（包名一样）时，要先卸载才能装你签的。
- 改了代码自己发布时，最好把包名改掉（`AndroidManifest.xml` 的 `package`、`src/` 下的目录和每个文件的 `package` 那一行），免得和原版冲突。

## 对接宿主

车况、歌这些数据来自宿主应用 BydModule（包名 `com.wzw.bydmodule`，网页卡片也是它提供的）给外部安卓应用开的 Binder 接口：绑定 `com.wzw.bydmodule/.uiplus.UiplusExternalService`，开会话、申请授权，再按方法名发请求、收订阅推送；方法名、参数和网页卡片的 `window.uiplus` 一样。接口、消息格式、授权的坑都写在 `src/` 里 `Uiplus.java` 的开头。

接口文档：uiplus Android 接口 https://u223v6sxpn.apifox.cn （网页卡片的 uiplus JS 接口是 https://1yquh73bv5.apifox.cn ）。

在车上用：

1. 装上以后，先在比亚迪「系统加速 → 应用加速管理」里给这个应用打开"允许在后台运行"。不然车机内存紧张时，宿主的授权页一盖上来，系统就把这个应用结束了，授权申请跟着作废。
2. 在宿主的应用中心里把它加进多窗口（小格子、大格子各有一份名单，只在大格子的名单里时拖不到小格子），打开后点"申请授权"，在宿主弹出的确认页里允许（勾上"记住选择"）。
3. 实车用过的宿主版本是 2.2.1.6 到 2.2.1.14；更早的宿主可能还没有这个接口。

## 许可

- 资源的许可在 `assets/LICENSE-SmileySans.txt`。
