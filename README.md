# 琉光（原生）：基于民间 4.5UI 开发的原生卡片

比亚迪车机上的 3D 歌词应用。放进车机的多窗口（双画中画）或悬浮窗口里，显示正在放的歌：封面、歌名歌手、整首歌词、播放控制和进度。

歌、歌词、封面都来自 4.5UI（宿主应用 BydModule），不用自己去接播放器。画面用 Java + OpenGL ES 3.0 直接画，不经过浏览器内核，比同样画面的网页卡片省很多处理器。

## 画面

- 封面的几种主色做成流动的极光背景，场景最深处挂着一张大封面。
- 封面是一块侧着的厚玻璃，换歌时翻面。
- 歌词贴在一个横放的滚轮上：当前句亮起、浮起来，别的句子变暗，离得越远越虚。横屏时整列歌词侧过身朝着封面。
- 下面是一片水面，倒映着整个场景。
- 玻璃控制条浮在水面上：收藏红心、上一首、播放暂停、下一首，播放键外圈是进度。
- 开场和换歌时，光点从封面流出来，聚成歌词。
- 歌单（车机自带的「多媒体」在放时）：整个播放队列排成一排立在水面上的玻璃砖，中间那块正对着、两边的侧过来往后叠；打开时歌词散成光点流进封面，封面滑到中间、两边的砖从它身后展开。
- 玻璃上的雨珠（可选）：水珠挂在屏幕上，偶尔滑落。设成"自动"时跟着实时天气（读车机自带的比亚迪天气，不经过宿主），并按歌词判断要不要下。
- 深浅色跟着车机的日夜模式走。

## 操作

- 点播放键：暂停 / 播放。
- 点封面：打开歌单（车机自带的「多媒体」在放时；没有歌单时点封面是暂停 / 播放）。
- 上下拖：翻看歌词，松手 3 秒后转回正在唱的那句。
- 按在封面上左右划：上一首 / 下一首。
- 在别处左右拖：转着看场景，松手弹回。
- 右上角是设置和全屏键。全屏键会在主屏上开一个全屏页，再点一下回到原来的小窗口。
- 歌单里按在封面那一行上左右划着翻（别处左右拖是转场景），点哪块放哪首，点正在放的那块（底下水面亮着光）回到歌词；控制条变成刻度条，拖着直接跳，翻远了点"正在放"回到正在放的那首。
- 歌单里搜索：点刻度条左头的放大镜，键盘从底下滑上来，打拼音首字母（qlx → 七里香）、全拼或英文，边打边筛，点一首就放。

## 设置

歌词字体（得意黑 / 系统字体）、深浅色（跟随车机 / 浅色 / 深色）、抗锯齿、帧率（自动 / 60 帧 / 30 帧）、水面倒影、背景封面、光带、玻璃上的雨珠（自动 / 开 / 关）、开场动画、换歌动画、全屏时收起状态栏。

## 在车上用

要有：比亚迪车机（Android 9 以上），装好 4.5UI（宿主应用 BydModule，包名 `com.wzw.bydmodule`）。实车用过的 4.5UI 版本是 2.2.1.6 到 2.2.1.18，更早的版本可能还没有给外部应用的接口。

1. 把 APK 装到车机上，比如 `adb install -r xxx.apk`。
2. 在比亚迪「系统加速 → 应用加速管理」里给它打开"允许在后台运行"。不开的话，车机内存紧张时宿主的授权页一盖上来，系统就会把应用结束，授权申请跟着作废。
3. 在 4.5UI 的应用中心里把它加进多窗口。小格子、大格子各有一份名单，只加进大格子的名单时，拖不到小格子里。
4. 打开后点"申请授权"，在宿主弹出的确认页里允许，并勾上"记住选择"。

## 接口文档

- uiplus Android 接口（这个应用用的）：https://u223v6sxpn.apifox.cn
- uiplus JS 接口（网页卡片用的）：https://1yquh73bv5.apifox.cn

应用绑定宿主的 `com.wzw.bydmodule/.uiplus.UiplusExternalService`，开会话、申请授权，再按方法名发请求、收订阅推送；方法名和参数同网页卡片的 `window.uiplus`。对接时踩过的坑（授权页、宿主后端晚好、会话超时等）写在 `src/` 里 `Uiplus.java` 的开头。

## 从源码打包

不用 Gradle、Android Studio，脚本直接调 Android 的构建工具。要装：

- Node.js 18 以上；
- JDK 17（`javac`、`java`、`keytool`）；
- Android SDK 里的 build-tools（测过 34，要带 aapt、aapt2、zipalign、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`）和 `platforms;android-30`（用它的 android.jar）。用 sdkmanager 装：`sdkmanager "build-tools;34.0.0" "platforms;android-30"`。

设好 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）和 `JAVA_HOME`，脚本自己去找工具；也可以直接设 `ANDROID_BUILD_TOOLS`（build-tools 目录）和 `ANDROID_JAR`（android.jar）。JDK 在 PATH 里也行。

```
node build.mjs            # → out/琉光（原生） v<版本>.apk
node build.mjs --install  # 打完用 adb install -r 装上（adb 要在 PATH 里）
```

中间产物在 `build/`。版本号在 `AndroidManifest.xml`：`versionCode` 每发一版加 1，`versionName` 是显示的版本。

## 签名

第一次打包会在这个目录生成签名钥匙 `release.keystore`（密码 android），之后一直用它。**要备份，别外传。**

- 宿主按应用的签名认人，授权记在签名上。换了钥匙，车上要重新授权。
- 安卓不让不同签名的包覆盖安装：车上装着别人签的同名应用（包名一样）时，要先卸载才能装你签的。
- 改了代码自己发布时，最好把包名改掉（`AndroidManifest.xml` 的 `package`、`src/` 下的目录、每个文件的 `package` 那一行），免得和原版冲突。

## 代码结构

工程是 `AndroidManifest.xml`、`res/`、`src/`（包名 `com.x3card.liuguang`）；`assets/` 里是打进 APK 的资源，已经做好，直接用；`build.mjs`、`apkbuild.mjs` 是打包脚本，只用 Node 自带的模块。

`src/com/x3card/liuguang/`：

| 文件 | 做什么 |
|---|---|
| `MainActivity.java` | 入口：连宿主、授权、订阅媒体和日夜模式 |
| `Uiplus.java` | 宿主给外部应用的接口（Binder），文件开头有完整说明 |
| `MediaState.java` | 媒体快照、整首歌词、播放位置、封面 |
| `Player.java` | 发给车机「多媒体」的导出入口（零权限）：跳到歌词某一句、收藏、放歌单里的某一首 |
| `BydMedia.java` / `SongCovers.java` | 车机的媒体控制器（零权限）：收藏状态、整个歌单；歌单里的封面 |
| `Shelf.java` / `SearchPad.java` / `SongIndex.java` | 3D 歌单；歌单里搜索的键盘；按拼音搜歌 |
| `Mock.java` | 模拟器里用车上录的假数据（数据不在源码里） |
| `Scene.java` | 3D 场景、着色器、手势、出帧 |
| `Rain.java` / `RainPick.java` / `BydWeather.java` | 雨珠、要不要下、读车机天气 |
| `G.java` / `Txt.java` / `Palette.java` / `Spring.java` | GL 工具、歌词贴图和折行、封面取色、弹簧动画 |
| `Ui.java` / `Settings.java` / `InfoView.java` / `FullActivity.java` | 设置面板、设置存取、连不上宿主或没授权时的说明、全屏页 |

`assets/`：歌词字体得意黑（官方 ttf 原样）和它的许可。

## 许可

- 资源的许可：`assets/LICENSE-SmileySans.txt`。
