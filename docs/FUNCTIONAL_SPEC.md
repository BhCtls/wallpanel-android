# InfoPanel 功能定义与验收标准

## 1. 系统组成

APK 内部由四部分协作：

1. Android Activity 和 WebView：全屏显示内置 InfoPanel。
2. Android 原生控制中心：统一处理屏幕、白噪音和媒体摘要页 action。
3. APK 内 HTTP 服务（TCP 8080）：向本机 WebView提供内置页面，并向局域网提供控制页/API。
4. APK 内 UDP 服务（UDP 40404）：接收白噪音等控制包。后续版本对其进行扩展。

电脑端的 TCP 8765 是独立的静态文件下载服务，不是上述系统的组成部分，不随 APK 启动。

## 2. 启动和默认页面

开启设置项 `Built-in InfoPanel` 时：

- 应用先启动 8080 HTTP 服务和 40404 UDP 服务。
- WebView 默认加载 `http://127.0.0.1:8080/panel/`。
- JavaScript bridge 仅用于该可信本机页面。
- 初始状态为辉光管时钟页。

关闭 `Built-in InfoPanel` 时，保留原 WallPanel 外部 URL/playlist 行为，不向外部网页暴露 InfoPanel bridge。

## 3. action 状态机

所有来源——平板触摸、HTTP API、UDP——进入同一个原生控制中心。HTTP 和平板触摸支持以下 action：

| action | 原生行为 | 平板表现 | 控制网页表现 |
| --- | --- | --- | --- |
| `left` | `screen=weather` | 切到 mock 天气页 | 可同步天气状态 |
| `clock` | `screen=clock` | 天气屏幕点击左1/3时回到时钟 | 可同步时钟状态 |
| `center` / `noise_toggle` | 切换白噪音 | 音频播放状态改变 | 可同步状态 |
| `noise_start` | 幂等启动白噪音 | 音频开始/保持播放 | 可同步状态 |
| `noise_stop` | 幂等停止白噪音 | 音频停止/保持停止 | 可同步状态 |
| `right` | `screen=media` | 打开平板 WebCamera/WebMic 摘要页 | 仅显示平板处于媒体检测页 |

平板时钟主屏的三分区由 Android 原生触摸监听器实现。遥控 action 完成后 HTTP 才返回更新后的状态；Activity 无条件把新状态注入 built-in 面板，避免控制网页先变而平板未变。

天气页复用原生三分区：左=`clock`，中=`center`，右=`right`，页面不显示操作文字。控制网页在 `weather` 或 `media` 状态下把左侧点击解释为 `clock`。

## 4. 白噪音

- 使用需求指定的远程 MP3 URL，由 Android 原生 `MediaPlayer` 播放。
- 每次从停止状态启动，在已知音频时长内选择随机起点。
- 循环播放，直到收到停止或切换指令。
- 不保存播放进度；应用重启或停止后重新启动会再次随机选择。
- 首次准备完成后，普通停止只暂停播放器；再次启动在同一已准备播放器上随机跳转，避免反复请求带时效限制的远程 MP3。Activity 销毁时才释放播放器。
- 已释放/已替换播放器的迟到 prepared/error 回调不得修改当前状态。
- preparing 状态再次 toggle 等同停止，避免并发创建播放器。
- URL 失效、网络异常或播放器错误时，停止 preparing/playing 并把错误写入 `/api/state` 的 `lastError`。

## 5. 摄像头/麦克风摘要

- `screen=media` 时，仅平板 localhost WebView 调用 `getUserMedia({video:true,audio:true})`，兼容标准和 `webkitGetUserMedia` 接口。
- WebView 媒体请求只接受 `http://127.0.0.1:8080` 来源，并映射到 Android `CAMERA`、`RECORD_AUDIO` 运行时权限。
- 图像摘要由低分辨率量化像素计算，音频摘要由 RMS 和量化频谱计算；两个摘要在输入产生明显变化时应随之变化。
- 页面明确区分“设备轨道存在”和“当前检测到声音/图像”。权限拒绝、无设备、API 不支持和采样错误均显示在平板上。
- 这是本地实时变化检测，不上传、不保存、不进行人脸或语音识别。

## 6. HTTP 控制

监听 TCP `8080`，绑定局域网可访问接口。接口无认证，仅限可信局域网。

```text
GET /api/state
GET /api/action?name=left|center|right|clock|noise_start|noise_stop|noise_toggle
```

- 未知 action 返回 HTTP 400 和 JSON 错误。
- 状态响应至少包含 `screen`、`noisePlaying`、`noisePreparing`、`lastError`、`httpPort`、`udpPort`。
- `/api/action` 在原生 action 执行后返回更新状态，控制页不得先行伪造成功状态。
- 控制页右侧点击不访问控制端的摄像头或麦克风。
- 控制页发送 action 后显示 `connecting...`，仅在相应 JSON 响应到达时隐藏；失败或超时则保持可见。

## 7. UDP 控制

监听 UDP `40404`，接收 UTF-8 文本，不区分大小写并折叠多余空格：

```text
INFOPANEL NOISE START
INFOPANEL NOISE STOP
INFOPANEL NOISE TOGGLE
```

有效指令回复 `INFOPANEL OK <ACTION>`；无效指令回复预期格式。UDP 不负责页面切换。

## 8. 生命周期和故障处理

- Activity 创建时启动控制中心，销毁时停止 HTTP/UDP 服务并释放 `MediaPlayer`。
- 8080 端口启动失败时记录错误；WebView 不得无限跳转到普通网络错误页，应允许用户重启应用后恢复。
- 页面刷新继续加载 localhost 内置页。
- 离开媒体摘要页或销毁页面时停止摄像头/麦克风轨道。
- 局域网断开不影响本机时钟；只影响远程控制和远程 MP3 获取。

## 9. 安全边界

- JavaScript bridge 只服务 APK 自带的 localhost 面板。
- 8080/40404 无认证、无 TLS，不映射到公网。
- APK 不监听、不引用 TCP 8765。
- 电脑端 8765 仅用于人工下载 APK；不会触发静默安装。

## 10. 本版本验收清单

- [ ] `Built-in InfoPanel` 默认加载 `http://127.0.0.1:8080/panel/`。
- [ ] 飞行模式下，辉光管时钟页面仍正常显示和走时。
- [ ] 1280×800 横屏中时钟无裁切，且没有日期、天气摘要和操作提示。
- [ ] 平板左、中、右三个区域由 Android 原生触摸分别触发天气、白噪音、媒体摘要页。
- [ ] 媒体摘要页显示摄像头/麦克风可用性、图像/声音存在性、摘要和变化状态。
- [ ] 遮挡/移动摄像头时图像摘要变化；安静与发声切换时声音检测和音频摘要变化。
- [ ] 外部浏览器右侧点击只让平板进入媒体摘要页，不申请浏览器自身媒体权限。
- [ ] 白噪音连续启停至少 10 次仍可工作，每次启动重新选择随机位置且不会重复下载 MP3。
- [ ] 天气页无文字操作提示，左/中/右分别返回时钟、切换白噪音、进入媒体摘要。
- [ ] 网页 action 请求期间右下角显示 `connecting...`，平板响应后消失。
- [ ] 外部浏览器左/中点击能同步驱动平板页面/音频。
- [ ] UDP START/STOP/TOGGLE 可控制白噪音。
- [ ] APK 中没有 8765 监听器或下载路由。
- [ ] release APK 使用既有 keystore 签名，Android 7 可覆盖安装。
