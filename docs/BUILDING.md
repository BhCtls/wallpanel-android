# InfoPanel 编译与发布

本文记录当前工作区内 InfoPanel APK 的可复现构建、签名和电脑端下载部署流程。目标产物支持 Android 7.0 平板；工程的 `minSdkVersion` 为 19。

## 工作区路径

- 工程：`work/wallpanel-android`
- Android SDK：`work/android-sdk`
- JDK 11：`work/jdk/jdk-11.0.31+11/Contents/Home`
- Gradle 用户目录：`work/gradle-home`
- Java/Android 工具家目录：`work/home`
- 发布文件：`outputs`

以下命令均从工作区根目录 `/Users/aoi/Documents/Codex/2026-07-17/chan` 执行。

## 构建环境

```bash
export WORKSPACE="$PWD"
export PROJECT="$WORKSPACE/work/wallpanel-android"
export JAVA_HOME="$WORKSPACE/work/jdk/jdk-11.0.31+11/Contents/Home"
export ANDROID_SDK_ROOT="$WORKSPACE/work/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="$WORKSPACE/work/gradle-home"
export HOME="$WORKSPACE/work/home"
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/30.0.1:$PATH"
mkdir -p "$HOME/.android/cache" "$GRADLE_USER_HOME" "$WORKSPACE/outputs"
```

`JAVA_TOOL_OPTIONS=-Duser.home=...` 是必要项：旧版 `sdkmanager` 可能忽略 shell 的 `HOME`，直接读取 JVM 的 `user.home` 并把缓存写到工作区外。

## 编译 release

发布前先更新 `WallPanelApp/build.gradle` 中的版本号，然后执行：

```bash
cd "$PROJECT"
./gradlew assembleProdRelease
```

未签名产物位于：

```text
WallPanelApp/build/outputs/apk/prod/release/WallPanelApp-prod-release-unsigned.apk
```

## 对齐与签名

项目当前使用工作区私有签名文件 `work/infopanel-release.jks`。为了保证已安装版本能够直接覆盖升级，后续版本必须继续使用同一个 keystore 和 alias。

示例（将 `VERSION` 替换为本次版本号）：

```bash
cd "$WORKSPACE"
VERSION=1.2.1
UNSIGNED="$PROJECT/WallPanelApp/build/outputs/apk/prod/release/WallPanelApp-prod-release-unsigned.apk"
ALIGNED="$WORKSPACE/outputs/InfoPanel-$VERSION-prod-release-aligned.apk"
FINAL="$WORKSPACE/outputs/InfoPanel-$VERSION-prod-release.apk"

"$ANDROID_SDK_ROOT/build-tools/30.0.1/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
"$ANDROID_SDK_ROOT/build-tools/30.0.1/apksigner" sign \
  --ks "$WORKSPACE/work/infopanel-release.jks" \
  --ks-key-alias infopanel \
  --ks-pass pass:infopanel \
  --key-pass pass:infopanel \
  --out "$FINAL" \
  "$ALIGNED"
```

签名口令仅适用于当前本地项目，不应把 keystore 或口令发布到公开仓库。

## 验证产物

```bash
"$ANDROID_SDK_ROOT/build-tools/30.0.1/apksigner" verify --verbose --print-certs "$FINAL"
"$ANDROID_SDK_ROOT/build-tools/30.0.1/aapt" dump badging "$FINAL" | head -n 8
shasum -a 256 "$FINAL"
```

至少确认：包名为 `com.chan.infopanel`、版本号正确、`sdkVersion` 不高于目标平板、APK v1/v2 签名通过。Android 7 安装验证命令：

```bash
adb install -r "$FINAL"
```

若设备中同包名 APK 使用了另一把签名密钥，必须先卸载旧包；卸载会清除应用设置。

## 电脑端 8765 空中下载

TCP 8765 仅由电脑提供，不属于 APK 内部服务，也不得在 Android 代码或 Manifest 中监听该端口。

在电脑上从工作区根目录执行：

```bash
python3 -m http.server 8765 --bind 0.0.0.0 --directory outputs
```

平板与电脑处于同一局域网时，在平板浏览器访问：

```text
http://电脑的局域网IP:8765/
```

`127.0.0.1` 只能供电脑自身访问，不能用于平板下载。下载完成后由 Android 安装器确认升级；应用不会自行静默安装。
