# Built-in InfoPanel 网页定义

## 页面角色与入口

InfoPanel 包含两类网页，它们都随 APK 打包在 `WallPanelApp/src/main/assets/infopanel/`，但用途不同：

| 页面 | 访问入口 | 使用者 | 职责 |
| --- | --- | --- | --- |
| 平板内置面板 | `http://127.0.0.1:8080/panel/` | APK 内的 WebView | 显示辉光管时钟、mock 天气、摄像头/麦克风摘要 |
| 局域网控制页 | `http://平板IP:8080/` | 手机或电脑浏览器 | 用网页点击调用 HTTP API，远程驱动平板状态 |

8080 是 APK 内部的 HTTP 服务端口。8765 不出现在上述页面、Android 路由或应用配置中；它只用于电脑分发 APK。

## 平板内置面板

### 时钟主屏

- 横屏全屏，基准分辨率为 1280×800。
- 只显示 `HH:MM:SS` 24 小时时间。
- 数字使用纯 HTML/CSS 实现辉光管外观，不依赖远程字体、图片或现代 JavaScript 语法。
- 不显示日期、天气摘要、操作提示或其他文字。
- 页面资源全部来自 APK assets，由应用内 HTTP 服务输出；断开互联网后时钟仍应显示和走时。

### 三个透明触摸区

主屏按可视宽度平均分成左、中、右三个透明区域。触摸区不改变辉光管布局，也不显示边框。

- 左 1/3：调用原生 action `left`，平板 WebView 切换到 mock 天气页。
- 中 1/3：调用原生 action `center`，由 Android `MediaPlayer` 播放或停止白噪音。
- 右 1/3：调用原生 action `right`，平板进入 WebCamera/WebMic 实时摘要页。

三分区由 Android `WebView.OnTouchListener` 按物理触摸横坐标直接判断，不依赖 HTML 透明按钮或旧 WebView 的 CSS 命中测试。JavaScript 只负责根据原生状态渲染页面。

### mock 天气页

固定显示：

- 城市：杭州
- 天气：微雨
- 温度：32—37°C

天气页继续使用三个不可见的 Android 原生触摸区，不增加文字注释：

- 左 1/3：`clock`，返回辉光管时钟。
- 中 1/3：`center`，切换白噪音。
- 右 1/3：`right`，进入媒体摘要页。

### WebCamera/WebMic 摘要页

- 只使用平板的摄像头和麦克风；遥控浏览器不申请自身媒体权限。
- Android 正确申请 `CAMERA` 和 `RECORD_AUDIO` 运行时权限，再把 WebView 媒体权限授予受信任的 localhost 页面。
- 页面显示摄像头预览，并每 750ms 对 32×18 降采样图像计算量化 FNV-1a 指纹、平均亮度和帧间差异。
- 页面通过 Web Audio `AnalyserNode` 计算麦克风 RMS 电平、量化频谱指纹和采样间差异。
- 分别显示设备/轨道是否存在、是否取得图像、是否检测到可听声音、当前摘要值，以及相对上一有效采样是否变化。
- 摘要是轻量变化检测指纹，不是加密哈希，也不是语义/AI 内容识别。
- 离开该页时停止媒体轨道并释放 AudioContext；再次进入时重新申请并采样。

## 局域网控制页

控制页表现为普通网页点击，通过同源 `/api/action?name=...` 请求平板。点击结果应传导到平板，但控制浏览器不冒充平板 UI：

- 左侧点击：平板切换到天气页；控制页可以同步显示状态。
- 中间点击：平板播放或停止白噪音；控制页可以同步显示播放状态。
- 右侧点击：平板进入 WebCamera/WebMic 摘要页；控制网页仅显示平板所处页面状态，不访问控制端摄像头/麦克风。
- 当控制网页不在时钟页时，左侧点击改为发送 `clock`，用于返回时钟；回到时钟后左侧恢复为进入天气。
- action 发出后右下角显示白色 `connecting...`；收到该 action 的平板 HTTP JSON 响应后撤除。未收到响应时保持显示，明确暴露连接故障。

控制页可以轮询 `/api/state` 来显示平板当前的 `screen` 和白噪音状态。它不得依赖 Android JavaScript bridge，因为它运行在外部浏览器中。

## 兼容性约束

- 目标为 Android 7.0 系统 WebView，同时保持工程 `minSdkVersion 19` 可加载。
- JavaScript 使用 ES5 风格，避免 optional chaining、async/await、module script 等旧 WebView 不稳定特性。
- CSS 对关键 flex 属性保留 `-webkit-` 前缀。
- 不使用旧 Android WebView 不支持的 `inset` 简写；固定定位明确写出 `top/right/bottom/left`。
- 所有静态资源响应提供正确 MIME 类型；HTML/JS/CSS 禁止缓存或使用可控短缓存，以便应用升级后立即加载新资源。
- JavaScript bridge 只挂载到受信任的 `127.0.0.1:8080/panel/` 页面，不向任意外部网页开放。

## 资源和 HTTP 路由

应用内服务至少提供：

```text
GET /                     -> remote.html
GET /panel                -> 重定向或返回内置面板
GET /panel/               -> index.html
GET /panel/index.html     -> index.html
GET /panel/style.css      -> style.css
GET /panel/app.js         -> app.js
GET /api/state            -> JSON 状态
GET /api/action?name=...  -> 执行动作并返回 JSON
```

路径不存在时应返回 404，资源读取失败时应返回 500 或明确的降级页。
