# 灵动岛 1:1 还原项目

本目录是「按参考 APK + 三段真机录屏，开发一个 1:1 还原的灵动岛 Android App」的完整工作区。

## 交付物

| 路径 | 内容 |
|---|---|
| **`app/`** | **Android 工程（可直接编译）**，说明见 `app/README.md` |
| **`dist/灵动岛-debug.apk`** | **已编译好的可安装 APK**（`com.lingdong.island`，minSdk 28 / target 35，约 140 KB） |
| `dist/preview/` | 四状态还原对比图（录屏原帧 vs 同参数渲染）与动画曲线拟合图 |
| `analysis/apk/` | 参考 App `com.jamworks.dynamicspot v1.88` 逆向规格：REPORT.md、SPEC.json、解码后的 layout/drawable/animator/values、反编译源码 |
| `analysis/video/` | 三段录屏的逐帧测量：REPORT.md（状态机/几何/视觉）、CURVES.md（每段过渡的进度表+拟合曲线）、SPEC.json、frames_*.csv（324/196/490 帧）、接触表与 159 张高清裁切 |
| `ref/frames/` | 三段录屏的 30fps 全帧 PNG（供复核） |
| `preview/` | 自研渲染器对比图与曲线拟合图 |
| `tools/` | 分析/构建辅助脚本：gradle、jadx、apktool、Python 测量/曲线拟合/预览渲染脚本 |

## 编译 / 安装

```bash
# 编译
E:\voice\tools\gradle-8.9\bin\gradle.bat -p E:\voice\app :app:assembleDebug

# 安装到手机（需开启 USB 调试）
adb install -r E:\voice\dist\灵动岛-debug.apk
```

打开 App 后按提示授予「悬浮窗权限」「通知使用权」，点「开启灵动岛」，
用「充电动效 / 系统时钟计时 / 音乐播放」三个按钮即可逐一预览（真实场景下会自动触发）。

## v2 更新（2026-10-24，回应用户反馈）

用户反馈「太僵硬」「有时会显示在状态栏下面」。已重做：
1. **动画引擎改为真·弹簧物理**（`Spring.java`，质量-弹簧-阻尼，1ms 子步长、帧率无关）：
   弹出 +6.2% 回弹、收回冲过圆点 8% 再弹回、展开 +3.7% 过冲、收起下冲 15% 后回弹；
   窗口尺寸只在状态切换时改一次，动画期间不再逐帧改窗口（旧版僵硬/掉帧的根因）。
2. **位置固定在状态栏上**：垂直中心对齐系统状态栏中心（读取 `status_bar_height`）、
   挖孔模式 ALWAYS、被 ROM 下推时用屏幕坐标自检并负 y 补偿；新增「增强模式（无障碍）」
   —— 与参考 App dynamicSpot 相同的受信任 accessibility overlay 方式，可越过 ROM 对普通悬浮窗的限制；
   另加「垂直偏移」滑杆可手动微调。
3. 秒表暂停不再走字；预览模式 60s 自动退出并让真实事件接管；通知监听断线重绑等（见 app/README.md）。

## v3 更新（按 F:\ANIMATION.md 参数手册实现）

用户指定按 F:\ANIMATION.md 的动画参数实现，已整套落地（详见 pp/动画参数.md）：

1. **几何层改为宽/高双轴独立阻尼弹簧**（Spring.java）：
   - 展开：宽 ζ=0.58 / k=110（过冲 +10.7%，峰值 368ms），高 ζ=0.62 / k=320（+8.4%，224ms）；
   - 收起：宽 ζ=0.47 / k=165（欠冲 −18.8%，277ms），高 ζ=0.55 / k=320（−12.6%，210ms）；
   - 半隐式欧拉、dt 上限 32ms、|Δ|<5px && |v|<120px/s 视觉稳定门限、命中吸附目标；
   - 宽高参数不同 →「先垂直张开、再横向拉开」。
2. **缓动层**（IslandEase.java + Fade.java）：内容只用 cubic-bezier 淡入淡出，
   STANDARD(0.40,0,0.20,1) / CONTENT(0.20,0,0.30,1)，Newton-Raphson 8 次 + 二分 24 次。
3. **时序层**：内容 340ms、药丸淡出 150ms、收起大卡内容 170ms、收起胶囊内容 90ms（弹簧稳定后）、隐藏 170ms；
   弹出内容延迟 75ms、展开内容延迟 60ms。
4. **窗口**：(卡片+2×6dp)×1.18 余量（不裁过冲）、TOP|CENTER_HORIZONTAL 顶部对齐、
   windowAnimations=0 + PRIVATE_FLAG_NO_MOVE_ANIMATION（防系统搬动窗口）。

## v3.1 更新（Bug 修复 + 性能优化，2026-10-24）

在**不改任何几何/动画参数**的前提下，修掉一轮独立审计发现的 P1/P2 问题并做性能优化
（完整清单与验证见 [`verify/BUGFIX_REPORT.md`](verify/BUGFIX_REPORT.md)、
独立审计 [`verify/audit_independent.md`](verify/audit_independent.md)、
第二轮复核 [`verify/verify_report.md`](verify/verify_report.md)）：

1. **旋转/配置变化不再丢内容**：服务侧数据全量回填新 View（计时不再冻结 00:01、音乐不再 Unknown track、充电电量正确）；旋转后保留紧凑态数据。
2. **媒体/计时状态机修复**：音乐会话消失后岛会收起；预览结束恢复真实媒体（含暂停）与计时；点充电胶囊关闭后可切到仍在运行的计时/音乐；通知监听重连后自动恢复媒体；展开计时卡的两个圆钮不再误收起。
3. **耗电/掉帧优化**：静止状态从“只要可见就 60/120fps 全窗重绘”改为**彻底停帧**；稳态 onDraw 0 分配（Shader/文本/字体度量全部缓存）；电池属性轮询 300ms→1s；封面改后台缩略图（≤512）且播放中不再每秒重缩放；通知正则预编译。
4. **稳定性**：窗口被系统移除看门狗自动重挂；位置补偿改为 0/120/400ms 多次重测；`startForeground` 失败即停不再吞异常；无障碍宿主断开自动回落普通悬浮窗；**只开“增强模式（无障碍）”也能独立显示**（不再要求悬浮窗权限）；回前台不再无谓重建窗口/闪烁。
5. **版本**：versionCode 4 / versionName 3.1（APK 仍在 `dist/灵动岛-debug.apk`）。

已知限制（需真机确认）：窗口内透明区域仍会吞掉部分状态栏手势；触摸死区、ROM 窗口层级/位置补偿、开机自启等行为需真机复核（详见报告 §4）。

## v3.2 更新（交互与视觉修正，2026-10-25）

1. **展开态新增“点岛外 / 上滑收起”**：展开计时卡或音乐播放器时，点击岛以外的任意位置（窗口外通过 `FLAG_WATCH_OUTSIDE_TOUCH` 的 `ACTION_OUTSIDE`，窗口内卡片外直接判定），或在岛上向上滑动，都会收起回紧凑胶囊；紧凑态不响应，避免误关真实计时/音乐。
2. **颜色不再溢出岛体/屏边**：之前 ambient 光晕画在圆角裁剪之外，展开态半径可达 ~698px，黄/红氛围光会铺到屏幕边缘并被屏边裁切；现改为在卡片圆角裁剪内、背景之后绘制，所有颜色严格限制在灵动岛内部。
3. **窗口尺寸小优化**：展开态悬浮窗宽度由 1.156 屏宽钳制到 1 屏宽（卡片 0.98W 仍完整可见，超屏过冲本来就被屏幕裁掉），减少无谓的 surface 与触摸区域。
4. **版本**：versionCode 5 / versionName 3.2（`dist/灵动岛-debug.apk`）。

## v8.73 更新（无障碍明确标记为必须开启项，2026-10-31）

- 引导权限页无障碍按钮改为：
  - **打开无障碍设置（必须开启）**
- 下方增加红色提示：
  - **「无障碍权限为必须项，未开启无法进入下一步」**
- 权限列表行改为 **「无障碍权限（必须开启）」**；
- 主设置「权限」页同步改为「必须开启」，并保留顶部大按钮；
- 忽略电池优化也标记为「必须开启」；
- 5 项必需权限未全部完成时，引导页/权限页完成按钮保持禁用；
- 主开关 `hasRequiredPermissions()` 继续强制无障碍和电池优化。

版本：versionCode 120 / versionName 8.73，正式签名包 `dist/游龙灵动岛-v8.73-签名版.apk`。
## v8.72 更新（引导页增加明确的无障碍设置按钮，2026-10-31）

- 引导页权限步骤顶部新增醒目按钮：
  - 简体中文：「打开无障碍设置（必需）」
  - English: "Open accessibility settings (required)"；
- 已开启时显示「无障碍已开启 · 打开设置」；
- 权限列表中无障碍行改名为「无障碍权限（必需）」，仍保留单独「去授权」按钮；
- 主设置「权限」页同样新增「打开无障碍设置（必需）」大按钮；
- 5 项必需权限仍全部完成后才能进入/开启。

版本：versionCode 119 / versionName 8.72，正式签名包 `dist/游龙灵动岛-v8.72-签名版.apk`。
## v8.71 更新（引导页强制全部权限，2026-10-31）

引导页权限配置改为 **5 项全部必需**，缺一不可：

1. 悬浮窗权限；
2. 通知使用权；
3. Android 13+ 通知权限；
4. 无障碍增强；
5. 忽略电池优化。

- 引导页「完成并进入」按钮在 5 项全部完成前禁用；
- 主设置「权限」页同样按 5 项显示，完成按钮禁用直到全部授权；
- 主开关 `hasRequiredPermissions()` 也改为 5 项全部满足才允许开启；
- 电池优化使用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接申请，失败回退电池优化列表；
- 新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；
- 权限页继续按引导选择的语言单语言显示。

版本：versionCode 118 / versionName 8.71，正式签名包 `dist/游龙灵动岛-v8.71-签名版.apk`。
## v8.70 更新（音乐岛进度条可拖动 + 收藏按钮，2026-10-30）

### 进度条
- 展开音乐卡时，在进度条区域（卡片纵向 49%~62%）按下/拖动：
  - 实时把拖动比例映射到 0.161~0.844 的轨道范围；
  - 调用 `MediaController.TransportControls.seekTo()`；
  - 同时立即更新 UI 的 `positionMs`，进度条/时间/滑块跟手；
- 拖动结束不会误触发「打开音乐 App」。

### 收藏
- 心形收藏按钮现在可点击：
  - 点击切换 `music.favorite`；
  - 心形变为珊瑚红；
  - 弹出「已收藏 / 已取消收藏」提示；
  - MediaSession 没有标准收藏接口，因此当前为本地收藏状态。

版本：versionCode 117 / versionName 8.70，正式签名包 `dist/游龙灵动岛-v8.70-签名版.apk`。
## v8.69 更新（引导权限强制 + 单语言授权页 + 横屏竖向灵动岛，2026-10-30）

1. **引导页权限强制**
   - 配置权限页「完成并进入」按钮在 3 项必需权限全部授权前禁用；
   - 悬浮窗、通知使用权、Android13 通知权限缺一不可；
   - 主设置里的「权限」页同样在必需权限未完成时禁用完成按钮。
2. **授权页单语言**
   - 引导页选择简体中文后，权限页只显示简体中文；
   - 选择 English 后只显示英文；
   - 不再显示中英双语。
3. **横屏竖向灵动岛**
   - 横屏时整个灵动岛绘制旋转 90°，变成竖向胶囊；
   - 摄像头跟随模式下继续放在摄像头下方；
   - 窗口尺寸、触摸坐标同步旋转/映射；
   - 竖屏保持横向。

版本：versionCode 116 / versionName 8.69，正式签名包 `dist/游龙灵动岛-v8.69-签名版.apk`。
## v8.68 更新（引导完成进入主页闪退修复，2026-10-30）

根因：v8.66 去掉主菜单图标后，行内子 View 索引变化：

- 原来：图标(0)、文字列(1)、开关/箭头(2)
- 去图标后：文字列(0)、开关/箭头(1)

权限行的 `permissionRowSub` 仍按旧索引 `getChildAt(1)` 取「文字列」，实际取到了箭头 TextView，强转 LinearLayout 时 `ClassCastException`，所以引导页完成进入主页立即闪退。

修复：

```java
permissionRowSub = (TextView)
        ((LinearLayout) permRow.getChildAt(0)).getChildAt(1);
```

版本：versionCode 115 / versionName 8.68，正式签名包 `dist/游龙灵动岛-v8.68-签名版.apk`。
## v8.67 更新（后台隐藏 + 闹钟保活，2026-10-30）

通用页新增「后台与保活」：

1. **后台隐藏**
   - 尽量隐藏常驻前台服务通知；
   - 通知渠道使用最低重要性、锁屏隐藏、无声音/角标；
   - 说明：Android 强制前台服务保留通知，无法完全删除，只能尽量降低显示层级。
2. **闹钟保活（防 LMK）**
   - 使用 AlarmManager 每 15 分钟唤醒一次；
   - 若服务被系统/LMK 杀死，`KeepAliveReceiver` 收到闹钟后重新 `startForegroundService`；
   - 优先精确闹钟 `setExactAndAllowWhileIdle`；Android 12+ 无精确闹钟权限时回退 `setAndAllowWhileIdle`；
   - 开关开启时会跳转「允许精确闹钟」授权页（如系统支持）；
   - 开机/服务启动/刷新时自动重新排程，关闭开关或关闭灵动岛时取消；
   - 新增 `SCHEDULE_EXACT_ALARM` 权限与 `KeepAliveReceiver`。

版本：versionCode 114 / versionName 8.67，正式签名包 `dist/游龙灵动岛-v8.67-签名版.apk`。
## v8.66 更新（无图标分类菜单 + 首次引导 + 外观实时预览，2026-10-30）

1. **去掉图标和蓝色标题栏**
   - 主菜单移除全部彩色圆形图标、播放图标和蓝色 Header；
   - 子页面标题栏也改为白色，仅保留文字返回按钮；
2. **外观页自动预览**
   - 进入「外观」时自动在屏幕顶部显示一个常驻小胶囊；
   - 调整任何尺寸/圆角滑块可实时看到变化；
   - 离开外观页自动结束预览，恢复原有通知/音乐/充电话状态；
3. **首次引导页**
   - 新用户首次启动进入 `OnboardingActivity`：
     - 第 1 页选择语言（简体中文 / English）；
     - 第 2 页逐项配置必需权限：悬浮窗、通知使用权、Android13 通知权限；
     - 全部完成后进入主菜单；未完成不能进入；
   - 完成标记 `onboarded`，以后不再显示引导；
   - 老版本升级首次也会进入一次引导。

版本：versionCode 113 / versionName 8.66，正式签名包 `dist/游龙灵动岛-v8.66-签名版.apk`。
## v8.65 更新（删除液态玻璃灵动岛，2026-10-30）

按用户要求彻底删除液态玻璃灵动岛：

- 删除 `:liquidglass` Gradle 模块、Kotlin/Compose 依赖与全部 `com.kyant.*` 源码；
- 删除 `LiquidIslandService`、`LiquidGlassActivity`、`ic_menu_liquid`；
- 主菜单移除「液态玻璃」行；
- 删除共享渲染中的液态玻璃材质分支、blur behind 窗口标志和 `useLiquidGlass` 接口；
- 恢复 `android.useAndroidX=false`、纯 Java 构建；
- APK 从 19.4MB 降到 **1.21MB**；
- 其余动静岛功能、分类菜单、权限页、横屏摄像头跟随模式保留。

版本：versionCode 112 / versionName 8.65，正式签名包 `dist/游龙灵动岛-v8.65-签名版.apk`。
## v8.64 更新（横屏摄像头跟随模式，2026-10-30）

位置页新增「横屏位置模式」两个选项：

1. **居中（默认）**：灵动岛始终在屏幕顶部中间；
2. **跟随摄像头**：横屏时读取屏幕 DisplayCutout，把灵动岛水平对齐到刘海/挖孔摄像头下方，并做屏幕边缘夹紧；竖屏仍保持居中。

实现：

- `IslandService.applyWindowPlacement()`：仅在横屏 + 开启跟随 + API29+ 有 cutout 时生效；
- 取面积最大的 cutout rect，水平中心对齐，`lp.x` 偏移；垂直放到 cutout.bottom 下方；
- 卡片宽度用 `IslandView.getCardWidth()` 夹紧，避免跑出屏幕；
- `cameraFollowWindowY` 参与原有位置校正，避免 ROM 顶栏补偿把岛拉回顶部；
- 旋转屏幕时现有 `onConfigurationChanged` 重建窗口并重新应用；
- 切换模式通过 ACTION_REFRESH 立即重新定位。

版本：versionCode 111 / versionName 8.64，正式签名包 `dist/游龙灵动岛-v8.64-签名版.apk`。
## v8.63 更新（dynamicSpot 风格分类主菜单，2026-10-30）

设置首页改成 dynamicSpot Pro 风格：

- 顶部蓝色标题栏「游龙灵动岛」+ 右侧 ▶/■ 主开关按钮；
- 主菜单行：
  - **灵动岛**：通知/充电/音量/解锁状态总开关（右侧 Switch）；
  - **通用**：显示设置和互动（常驻、七类显示开关、通知应用白名单、服务/预览）；
  - **外观**：尺寸、圆角和样式（全部尺寸滑块）；
  - **位置**：更改垂直位置；
  - **权限**：逐项授权页（显示 x/3）；
  - **液态玻璃**：独立液态玻璃模块开关；
- 每行带彩色圆形图标、标题、副标题、Switch/箭头和分隔线；
- 新增 `SettingsCategoryActivity`（通用/外观/位置子页）；
- 新增 6 个菜单矢量图标；
- 主开关缺少必需权限时仍会弹回关闭并进入权限页。

版本：versionCode 110 / versionName 8.63，正式签名包 `dist/游龙灵动岛-v8.63-签名版.apk`。
## v8.62 更新（设置页分类 + 权限授权页，2026-10-30）

设置页重新分类：

1. **灵动岛开关**（置顶）
   - 「开启灵动岛」开关；
   - 「液态玻璃」开关；
   - 「权限」入口卡片，显示 0/3~3/3 必需权限；
   - 开启主开关时若缺少必需权限：自动关闭开关并进入权限页，不会启动灵动岛；
2. **外观设置**
   - 垂直位置微调；
   - 整体缩放、紧凑宽高、展开宽高、圆角、常驻宽高；
   - 恢复默认尺寸；
3. **显示设置**
   - 灵动岛常驻；
   - 通知/充电/音量/音乐/铃声/解锁/锁屏 七个独立开关；
   - 通知应用白名单；
4. **权限与授权**
   - 新增 `PermissionActivity`：逐项授权、实时显示状态；
   - 必需：悬浮窗（或无障碍增强）、通知使用权、Android13 通知权限；
   - 可选：无障碍增强、忽略电池优化；
   - 每项独立「去授权」，全部完成后返回软件再点开启；
5. **服务与调试**
   - 开启/关闭服务、液态玻璃模块入口、充电动效/音乐预览。

修复：设置页 onResume 现在按当前服务类型刷新（液态玻璃运行时不误启动普通服务）。

版本：versionCode 109 / versionName 8.62，正式签名包 `dist/游龙灵动岛-v8.62-签名版.apk`。
## v8.61 更新（解锁结束后长岛变长方形修复，2026-10-29）

逐帧分析视频后定位：解锁方块（高、窄）切换到一个**更宽、更矮**的目标岛时，`ensureWindow(..., allowShrink=false)` 只要发现其中**任一轴要缩小**就直接 return，导致窗口宽度没有增长；卡片先变宽，却被旧窗口左右裁掉，两角变成直角长方形，等 settle 阶段窗口重设后才恢复胶囊，约0.5秒。

- 修复：窗口增长改为**逐轴独立**：
  - `targetW = max(winW, targetW)`；
  - `targetH = max(winH, targetH)`；
  - 允许一轴增长、另一轴保持旧尺寸，等 settle 再统一收缩；
- 解锁方块→长胶囊时，宽度立即扩窗，不再被裁成直角矩形；
- 原 F-14「动画期间不缩窗」行为保留。

版本：versionCode 108 / versionName 8.61，正式签名包 `dist/游龙灵动岛-v8.61-签名版.apk`。
## v8.60 更新（移除音量弹跳，2026-10-28）

- 按用户要求，音量灵动岛的抖动动画全部删除；
- 删除 `IslandView.bounceVolume()`、`volumeBounceStart`、onDraw 缩放、afterDraw kick；
- 删除 `IslandService` 的 wasVisible/sameLevel/step/冷却/bounce 调用及相关字段；
- 音量 HUD 恢复为：显示类型 + 百分比，1.5 秒收回，无任何缩放弹跳。

版本：versionCode 107 / versionName 8.60，正式签名包 `dist/游龙灵动岛-v8.60-签名版.apk`。
## v8.59 更新（厂商大跨度音量只弹一次，2026-10-28）

有些厂商按一下音量键不是±1，而是一次跳十几级，并连续发多个 `VOLUME_CHANGED`，导致弹多次。

- 记录单次事件的跨度 `step = |newLevel - oldLevel|`；
- `step >= 4` 视为一次大跨度手势，弹跳冷却 **500ms**；
- `step < 4` 正常按键冷却 220ms；
- 同一音量值 140ms 内重复广播仍不重复弹；
- 首次展开仍然不弹；展开后只弹一次；
- 幅度保持 3.5%、190ms。

版本：versionCode 106 / versionName 8.59，正式签名包 `dist/游龙灵动岛-v8.59-签名版.apk`。
## v8.58 更新（音量弹跳只弹一次、幅度减小，2026-10-28）

- 第一次按音量键时灵动岛只展开，**不弹**；
- 等 HUD 完全展示后（≥300ms）再按音量键，每次只弹一下；
- 同一个音量值 140ms 内的重复广播不重复弹；
- 弹跳时长 260ms→**190ms**，幅度 8.5%→**3.5%**；
- 关闭音量开关后仍然不显示、不弹。

版本：versionCode 105 / versionName 8.58，正式签名包 `dist/游龙灵动岛-v8.58-签名版.apk`。
## v8.57 更新（调音量时灵动岛快速弹一下，2026-10-27）

- 每次音量键变化（音量 HUD 出现/更新）时，灵动岛做一次 **260ms 的放大→缩小弹跳**：
  - 正弦包络，最大放大 8.5%，中点最大后回落；
  - 连续按键会重新开始弹跳；
  - 只缩放绘制层，不改窗口/几何状态；
- 仅当 **「调音量时显示灵动岛」开关开启** 时才弹；关闭后既不显示 HUD，也不弹；
- 解锁视频独占期间不弹；
- 普通版和液态玻璃版共用。

版本：versionCode 104 / versionName 8.57，正式签名包 `dist/游龙灵动岛-v8.57-签名版.apk`。
## v8.56 更新（每种灵动岛独立开关，2026-10-27）

设置页新增「灵动岛显示开关（单独控制）」，默认全部开启：

- 来消息时显示灵动岛 —— `show_notif`
- 充电时显示灵动岛 —— `show_charge`
- 调音量时显示灵动岛 —— `show_volume`
- 播放音乐时显示灵动岛 —— `show_music`
- 响铃/静音时显示灵动岛 —— `show_ringer`
- 解锁时显示解锁动画 —— `show_unlock`
- 锁屏时显示锁头胶囊 —— `show_lock`

实现：

- `IslandService` 读取这些开关，`desiredMode()` 每层都加对应 gate；
- 关闭后对应事件不再显示：通知到达直接忽略、充电不启动 HUD、音量不显示、音乐/铃声不显示、解锁不播放、锁屏不显示锁头；
- 开关操作通过 `ACTION_REFRESH` 立即刷新；如果当前是液态玻璃服务，会刷新 `LiquidIslandService`，不会误启动原服务；
- 「关闭灵动岛」会同时停止原服务和液态玻璃服务；
- 默认 true，不影响现有用户行为。

版本：versionCode 103 / versionName 8.56，正式签名包 `dist/游龙灵动岛-v8.56-签名版.apk`。
## v8.55 更新（宽屏锁屏/解锁尺寸修复，2026-10-27）

视频里宽屏设备上锁屏胶囊和银色解锁方块接近占满屏幕，原因是几何只按 `W`（宽边）计算。

- `lockW()` 增加 `0.45×SCREEN_H` 上限；
- `lockH()` 增加 `0.070×SCREEN_H` 上限；
- `unlockSize()` 增加 `0.35×SCREEN_H` 上限；
- 正常竖屏手机尺寸不变；
- 宽屏/折叠屏上锁屏胶囊和解锁方块不再巨大。

版本：versionCode 102 / versionName 8.55，正式签名包 `dist/游龙灵动岛-v8.55-签名版.apk`。
## v8.54 更新（锁屏灵动岛收短，2026-10-27）

- 锁屏胶囊宽度 **0.500W → 0.400W**；
- 锁头图标、解锁视频/方形不变。

版本：versionCode 101 / versionName 8.54，正式签名包 `dist/游龙灵动岛-v8.54-签名版.apk`。
## v8.53 更新（解锁动画期间独占灵动岛，2026-10-27）

- 新增 `unlockHold`：从解锁动画预备窗口开始的 **120ms 准备阶段**就锁定灵动岛，直到视频播放完毕；
- `desiredMode()` 最顶部判断 `unlockAnimating || unlockHold`，优先级高于通知/音量/充电/铃声/音乐/常驻/预览；
- 解锁开始时取消在途通知关闭动画，`dismissNotificationIsland()` 在 unlockHold 期间直接忽略；
- 如果当前有展开的通知/音乐卡，先 snap 掉，直接让解锁视频接管；
- 视频 1.3s 播完才放开 hold，重新调度通知自动关闭并显示其他岛。

版本：versionCode 100 / versionName 8.53，正式签名包 `dist/游龙灵动岛-v8.53-签名版.apk`。
## v8.52 更新（锁屏灵动岛加长，2026-10-27）

- 锁屏胶囊宽度 **0.300W → 0.500W**，高度不变；
- 锁头图标仍靠左显示；
- 解锁视频/平方形尺寸不变；
- 黑色原模块和液态玻璃模块共用。

版本：versionCode 99 / versionName 8.52，正式签名包 `dist/游龙灵动岛-v8.52-签名版.apk`。
## v8.51 更新（音量 HUD 改为百分比，2026-10-27）

- 去掉顶部白条，不再画进度线；
- 右侧直接显示 **实时音量百分比**（如 `50%`），按 `level/max` 计算；
- 左侧喇叭 + 音量类型文字保留；
- 胶囊仍保持加长尺寸；
- v8.49 的 VOLUME_CHANGED 广播 + 300ms 轮询双通道保留，百分比随按键实时变化。

版本：versionCode 98 / versionName 8.51，正式签名包 `dist/游龙灵动岛-v8.51-签名版.apk`。
## v8.50 更新（音量 HUD 视觉继续贴近参考图，2026-10-27）

- 参考图里进度线是**胶囊上沿靠右的一条细白线**，不是整宽粗条：
  - 白线移到右上区域（最大长度约 0.44 个胶囊宽）；
  - 轨道更淡更细，填充部分纯白；
  - 填充严格按 `level/max`，50% 音量就是一半长度；
- 喇叭图标 0.54h→0.44h，文字 0.36h→0.30h，更接近参考图比例；
- 保留 v8.49 的 VOLUME_CHANGED 广播 + 300ms 轮询双通道实时刷新；
- 去掉底部条和百分比。

版本：versionCode 97 / versionName 8.50，正式签名包 `dist/游龙灵动岛-v8.50-签名版.apk`。
## v8.49 更新（音量进度条按参考图重做并实时变化，2026-10-27）

- 按用户图片 1:1 调整：
  - 音量胶囊保持加长（0.620W×0.098W）；
  - 进度条移到**胶囊顶部**，细白线 + 半透明轨道；
  - 去掉原来的底部粗条和右侧百分比；
  - 左侧喇叭 + 音量类型文字（媒体音量/铃声/…）。
- 进度条实时性：
  - 新增隐藏广播 `android.media.VOLUME_CHANGED_ACTION` 动态接收（立即更新）；
  - 同时保留 300ms AudioManager 轮询兜底；
  - 两条路径都调用 `showVolume(stream, level)`，用 **level/max** 计算填充比例（50% 音量正好一半长度）；
  - 每次更新都 `view.setVolume` + `view.invalidate()`，确保重绘。
- 显示时长仍1.5s，连续按键重新计时。

版本：versionCode 96 / versionName 8.49，正式签名包 `dist/游龙灵动岛-v8.49-签名版.apk`。
## v8.48 更新（音量 HUD 加长 + 进度条，2026-10-27）

- 音量胶囊宽度 0.420W → **0.620W**，明显变长；
- 高度 0.0935W → **0.098W**；
- 内部新增 **音量进度条**：
  - 轨道为白色半透明圆角条；
  - 填充按当前音量/最大值比例；
  - 标签与百分比在进度条上方；
- 喇叭图标 + 流名称（媒体音量/铃声/通知/闹钟/通话音量/系统音量）+ 百分比保留；
- 显示 1.5s，连续按键重新计时；
- 黑色原模块与液态玻璃模块共用。

版本：versionCode 95 / versionName 8.48，正式签名包 `dist/游龙灵动岛-v8.48-签名版.apk`。
## v8.47 更新（音量灵动岛，2026-10-27）

调音量时在灵动岛上显示短暂音量 HUD：

- tick 每 300ms 轮询 AudioManager 的 6 个流：
  MUSIC / RING / NOTIFICATION / ALARM / VOICE_CALL / SYSTEM；
- 哪个流变化就显示对应标签：
  - 媒体音量 / 铃声 / 通知音量 / 闹钟 / 通话音量 / 系统音量；
- 黑色（或液态玻璃）胶囊：左侧喇叭图标 + 流名称 + 百分比；
- 显示 1.5s，连续按键会重新计时；结束后回到通知/音乐/充电/常驻；
- 优先级：解锁视频 > 音量 HUD > 通知 > 充电 > 锁屏 > 铃声/静音 > 音乐 > 常驻；
- 黑白原模块和 v8.46 液态玻璃模块共用同一套逻辑。

版本：versionCode 94 / versionName 8.47，正式签名包 `dist/游龙灵动岛-v8.47-签名版.apk`。
## v8.46 更新（液态玻璃模块改为真实灵动岛材质，2026-10-27）

用户澄清：不是把设置界面做成玻璃，而是**灵动岛本体**要变成液态玻璃。

- 新增 `LiquidIslandService extends IslandService`：
  - 复用原模块完整通知/音乐/充电/解锁/锁屏逻辑；
  - 覆盖 `useLiquidGlass()=true`；
- `IslandView` 新增 `setLiquidGlass`：
  - 岛体不再纯黑，改为半透明玻璃面 + 对角高光 + 白色边缘；
  - overlay 窗口加 `FLAG_BLUR_BEHIND` + `setBlurBehindRadius(22dp)`，模糊真实屏幕背景；
  - 液态玻璃模式优先普通 overlay 窗口（a11y 窗口无法保证 blur）；
- 入口「使用液态玻璃灵动岛」打开 Activity 后自动：
  1. 停止原 `IslandService`；
  2. 启动 `LiquidIslandService`；
  3. 灵动岛本体以液态玻璃材质运行；
- 原模块仍保持黑色 Canvas 材质，只有入口和共享渲染分支新增；
- 新页面的 `LiquidButton`/玻璃预览第一版保留。

版本：versionCode 93 / versionName 8.46，正式签名包 `dist/游龙灵动岛-v8.46-签名版.apk`。
## v8.45 更新（新增独立液态玻璃灵动岛模块，2026-10-27）

按用户要求新增**完全独立的第二个模块**，不修改现有 Canvas 灵动岛实现：

- 设置页服务区新增入口 **「使用液态玻璃灵动岛」**，点击启动新模块 Activity；
- 新 Gradle 模块：`:liquidglass`（Kotlin + Jetpack Compose）；
- 1:1 引入开源项目 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）：
  - `com.kyant.backdrop` 全部源码；
  - `LiquidButton` / `InteractiveHighlight` / `Capsule`；
  - blur + vibrancy + lens 折射真实背景；
- 新模块当前包含：
  - 液态玻璃灵动岛胶囊（330×84dp，Capsule 形状，blur/lens/vibrancy）；
  - 液态玻璃按钮：通知 / 音乐 / 充电 / 解锁 / 返回；
  - 点击切换灵动岛预览状态；
- 现有模块的 Java IslandService/IslandView 未改动，只新增启动入口；
- 许可证文件：`liquidglass/LICENSE-AndroidLiquidGlass.txt`。

> 说明：这是液态玻璃模块的第一阶段（入口 + 独立模块 + 玻璃材质/按钮渲染）。原有 Canvas 模块的全部通知/音乐/充电业务逻辑迁移到该 Compose 模块，需要下一阶段继续做。

版本：versionCode 92 / versionName 8.45，正式签名包 `dist/游龙灵动岛-v8.45-签名版.apk`。
## v8.44 更新（解锁视频完全不见了，2026-10-27）

- 根因：v8.42 把充电优先级放到解锁之上，手机充电时 `desiredMode()` 直接返回 MODE_CHARGE，解锁视频完全被压掉；
- v8.44 把**解锁播放提升到最高优先级**：
  解锁 > 通知 > 充电 > 锁屏 > 响铃/静音 > 音乐 > 常驻；
- 即使充电中、有通知，也会先播完 1.3s 解锁视频，再回到原状态；
- 保留 v8.43 的窗口预撑（120ms）和只显示一半修复；
- 增加启动 watchdog：解锁 pending 超过700ms未开始，强制开始，避免延迟回调丢失导致完全不显示。

版本：versionCode 91 / versionName 8.44，正式签名包 `dist/游龙灵动岛-v8.44-签名版.apk`。
## v8.43 更新（修复解锁岛只显示一半，2026-10-27）

- 概率性“只显示一半”根因：解锁视频/方形开始播放时，overlay 窗口还停在锁屏小胶囊尺寸，view 已经扩成方形，窗口把下半部分裁掉；
- 修复：
  - `startUnlockAnimation()` 先把窗口 attach/调整到 MODE_UNLOCK 完整尺寸；
  - 延迟 **120ms** 再开始播放第一帧，给 WindowManager 应用新尺寸的时间；
  - 开始播放时再次强制 `lp.width/height` 为方形窗口尺寸；
  - `windowSizeFor*` 增加 `SCREEN_H` 上限，窗口高度不再超过实际屏幕；
  - 用 `unlockSeq` 取消息屏/重复解锁的延迟启动。
- 解锁视频内容、岛尺寸、播放完自动收回不变。

版本：versionCode 90 / versionName 8.43，正式签名包 `dist/游龙灵动岛-v8.43-签名版.apk`。
## v8.42 更新（播放音乐时插电也显示充电岛，2026-10-26）

- 旧逻辑 `desiredMode()` 里 `musicActive` 优先于 `chargingActive`，播放音乐时插充电器只看到音乐岛，充电岛完全不出现；
- 现在充电是**瞬时优先状态**，优先级改为：
  通知 > **充电** > 解锁动画 > 锁屏 > 响铃/静音 > 音乐 > 常驻；
- 插电后立即显示充电岛，6s 充电 hold 结束后自动回到音乐岛；
- v8.40 的充电广播防误收起保留。

版本：versionCode 89 / versionName 8.42，正式签名包 `dist/游龙灵动岛-v8.42-签名版.apk`。
## v8.41 更新（更换解锁视频，2026-10-26）

- 岛内解锁视频换成 `1a4cb87d-ed0f-429a-a120-366f49e802fd.mp4`；
- 新视频：1080×1080，77 帧，1.300s；
- 重新打包 sprite sheet 9×9×256；
- 帧时间改为 `elapsed*77/1300`，播完 1.3s 自动收回；
- 方形岛尺寸、视频在岛内 80% 居中显示、音轨不播放等保持不变。

版本：versionCode 88 / versionName 8.41，正式签名包 `dist/游龙灵动岛-v8.41-签名版.apk`。
## v8.40 更新（修复充电岛刚展开就自己合上，2026-10-26）

概率性问题定位到电池广播状态滞后：

- 旧逻辑 `ACTION_BATTERY_CHANGED` 只要 `chargingNow=false` 就立刻 `chargingActive=false`；
- ROM 在 `ACTION_POWER_CONNECTED` 后的第一条 battery tick 里，`status` 可能仍是 NOT_CHARGING / level=100，导致充电岛刚展开就被关掉；
- 修复：
  - 仍在充电（plugged）时，battery tick 不再结束充电岛，交由 6s 正常 hold / 显式拔出广播决定；
  - 真正 unplug 的 battery tick 也要连续 **1.5s** 确认，避免粘性广播旧值；
  - 新增统一 `endChargingNow()`，清 chargingUntil / grace 状态；
  - `ACTION_POWER_DISCONNECTED` 仍立即结束。

版本：versionCode 87 / versionName 8.40，正式签名包 `dist/游龙灵动岛-v8.40-签名版.apk`。
## v8.39 更新（锁屏锁头放大 + 静音斜杠裁切，2026-10-26）

- 解锁前锁屏锁头图标 0.42h → **0.60h**，明显放大；
- 静音红色图标底的铃铛/斜杠现在**裁剪在红底圆角矩形内**，斜杠不再溢出；
- 斜杠长度 iconSize×0.52 → ×0.42，进一步保证不超出；
- 静音红底、岛宽、文字位置不变。

版本：versionCode 86 / versionName 8.39，正式签名包 `dist/游龙灵动岛-v8.39-签名版.apk`。
## v8.38 更新（只缩小视频，岛尺寸不变，2026-10-26）

- **灵动岛方形恢复 0.300W**（v8.37 错误地改成了 0.240W，本次改回）；
- 视频在方形内部按 **80%** 居中绘制（`UNLOCK_VIDEO_SCALE=0.80`），四周留黑色边；
- 播放时长、自动收回、锁屏锁头胶囊都不变。

版本：versionCode 85 / versionName 8.38，正式签名包 `dist/游龙灵动岛-v8.38-签名版.apk`。
## v8.37 更新（解锁视频显示缩小，2026-10-26）

- 解锁视频方形区域 0.300W → **0.240W**（缩小 20%），视频内容随之变小；
- 播放时长、逐帧 sprite、播完自动收回、锁屏锁头胶囊都不变。

版本：versionCode 84 / versionName 8.37，正式签名包 `dist/游龙灵动岛-v8.37-签名版.apk`。
## v8.36 更新（解锁直接播放视频，2026-10-26）

按用户要求，解锁不再使用 Canvas 手绘绿色动画，直接在灵动岛里播放提供的视频：

- 视频：1080×1080，72 帧，1.2846s；
- 72 帧抽成 256×256，打包为 `res/drawable-nodpi/unlock_frames.png`（9×8 sprite sheet）；
- 解锁时 0.30W×0.30W 黑色圆角方，按真实时间 `frame = elapsed*72/1284` 播放；
- 播放完 1284ms 立即收回：有常驻/音乐/充电回到该状态，否则收圆点隐藏；
- 不再保留 v8.35 的 1.6s 静止胶囊；
- 锁屏白色锁头胶囊和 v8.34 KeyguardManager 轮询保留；
- 不播放视频音轨。

版本：versionCode 83 / versionName 8.36，正式签名包 `dist/游龙灵动岛-v8.36-签名版.apk`。
## v8.35 更新（解锁动画 1:1 细调，2026-10-26）

按视频 f10–f120 重新对齐：

- 锁头改为**绿色描边锁头**（不是实心），在 130–750ms 内旋转一整圈，模拟视频里的旋转/打开；
- 方形展开使用 release 弹簧，速度更快；
- 时序改为：
  - 130–750ms 锁头旋转；
  - 740–970ms 绿色圆环；
  - 960–1180ms 绿色对勾；
  - 1180–1650ms 保持对勾；
- 1650ms 后才缩回胶囊；之后保留 1.6s 静止胶囊；
- 总时长约1.65s，与视频 1.7s 接近。

版本：versionCode 82 / versionName 8.35，正式签名包 `dist/游龙灵动岛-v8.35-签名版.apk`。
## v8.34 更新（修复解锁后仍停留在锁屏态，2026-10-26）

用户反馈：解锁屏幕后灵动岛一直显示锁屏胶囊。

- 根因：锁屏/解锁只依赖 `ACTION_USER_PRESENT`；部分 ROM/后台服务收不到该广播，`locked` 一直保持 true；
- 修复：tick（300ms）在亮屏时轮询 `KeyguardManager.isKeyguardLocked()`：
  - true→false 自动触发解锁动画并清除锁屏态；
  - false→true 重新显示锁屏胶囊；
  - `ACTION_USER_PRESENT` / `SCREEN_ON` 仍保留作为快速路径；
  - 解锁后忽略 Keyguard 短暂回传的 stale true，避免又切回锁屏；
  - 息屏/亮屏与生物识别解锁（亮屏时已解锁）也会触发动画；
- 锁屏胶囊 + 解锁绿锁头→圆环→对勾逻辑不变。

版本：versionCode 81 / versionName 8.34，正式签名包 `dist/游龙灵动岛-v8.34-签名版.apk`。
## v8.33 更新（静音岛对齐 + 响铃图标放大，2026-10-26）

根据用户真机截图和 1284×1080 参考：

- **静音岛加宽**：0.358W → **0.393W**（视频静音胶囊 505 px，比响铃 457 px 宽）；
- **静音红底左移对齐**：左距 0.067h → **0.233h**（视频红色圆角底左侧留白约 28 px）；
- **铃铛图标放大**：0.40h → **0.60h**，实际 glyph 从约 24×31 提升到约 38×48，响铃岛左侧图案明显变大；
- 响铃图标中心距卡左 0.51h → **0.49h**，文字右内距 0.28h → **0.32h**；
- 响铃↔静音切换时窗口/几何按新宽度动画重排，不闪跳。

版本：versionCode 80 / versionName 8.33，正式签名包 `dist/游龙灵动岛-v8.33-签名版.apk`。
## v8.32 更新（解锁前锁屏胶囊 + 解锁动画，2026-10-26）

按 `video_261026_225507.mp4`（576×1280@60fps）复刻：

- **解锁前**：锁屏时顶部显示 0.30W×0.078W 黑色胶囊 + 白色锁头；
- **解锁后**：
  - 黑色圆角方 0.30W×0.30W；
  - 绿色动画顺序：锁头淡入/旋转 → 绿色圆环 → 绿色对勾；
  - 1.5s 后缩回胶囊，再保留 1.6s 静止胶囊后回到常驻/隐藏；
- 监听 `ACTION_SCREEN_ON / ACTION_USER_PRESENT / ACTION_SCREEN_OFF` + KeyguardManager；
- 窗口加 `FLAG_SHOW_WHEN_LOCKED`；
- 逐帧/实现说明：`verify/ref_unlock_video/ANALYSIS.md`。

版本：versionCode 79 / versionName 8.32，正式签名包 `dist/游龙灵动岛-v8.32-签名版.apk`。
## v8.31 更新（响铃 / 静音 HUD，2026-10-26）

按 `video_20261026_224650.mp4`（1284×1080@60fps）复刻顶部胶囊：

- 新 `MODE_RINGER`：0.358W × 0.0935W 黑色胶囊，纯圆角；
- **响铃**：白色实心铃铛 + 白字「响铃」；
- **静音**：红色 #F83030 圆角图标底 + 白色铃铛/红色斜杠 + 红字「静音」；
- 监听 `AudioManager.RINGER_MODE_CHANGED_ACTION`：NORMAL→响铃，SILENT/VIBRATE→静音；每次切换显示 2.6s；
- 优先级：通知 > 响铃 HUD > 音乐/充电 > 常驻；HUD 结束后回到常驻/音乐/充电或隐藏；
- 逐帧测量和实现说明：`verify/ref_ringer_video/ANALYSIS.md`。

版本：versionCode 78 / versionName 8.31，正式签名包 `dist/游龙灵动岛-v8.31-签名版.apk`。
## v8.30 更新（点击通知正文重新打开应用，2026-10-26）

- 通知正文点击路径：
  - `sendPendingIntent` 改为返回成功/失败；
  - Android 14+ 使用 `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` 发送 PendingIntent，避免目标 SDK 35 下从悬浮窗服务后台启动 Activity 被系统静默拦截；
  - 发送失败时回退 `launchApp(包名)`；
  - 两者都失败时 Toast「无法打开通知应用」，不再无反馈；
- 点击通知普通按钮仍按 v8.29 立即收回；
- 常驻/充电/音乐收回逻辑不变。

版本：versionCode 77 / versionName 8.30，正式签名包 `dist/游龙灵动岛-v8.30-签名版.apk`。
## v8.29 更新（点击通知普通按钮后立即收回，2026-10-26）

- 通知展开卡上的普通 action（如应用商店「全部更新」）点击后：
  - 先发送该 action 的 PendingIntent；
  - **立即调用 `dismissNotificationIsland()`** 收回岛；
  - 不再调用 `extendNotifHoldForExpanded()` 等满 4 秒；
- 收回仍走 v8.28 的路径：常驻/音乐/充电存在时直接 morph 回该状态，不消失不重挂；无底层时正常隐藏；
- 回复 action 已在 v8.24 移除，不涉及。

版本：versionCode 76 / versionName 8.29，正式签名包 `dist/游龙灵动岛-v8.29-签名版.apk`。
## v8.28 更新（常驻灵动岛不再被通知“收回再重显”，2026-10-26）

- 修复常驻（always-on）时通知销毁路径：
  - 旧逻辑通知结束后统一 `hideNoBounce()` → 圆点 → `detachOverlay()` → `recomputeState()` 重新挂常驻，导致常驻岛先消失再出现；
  - 现在 `dismissNotificationIsland()` 先算底层 `desiredMode()`：
    - 常驻/音乐/充电存在 → 直接 `morphToNoBounce(target)` 收回到该状态，窗口保持 attached，不再走圆点、不 detach；
    - 无底层 → 仍按原逻辑收圆点并 detach；
  - `finalizeNotificationClose()` 对目标非 HIDDEN 直接 `ensureWindow(target)` + `snapToMode(target)`，常驻岛全程不消失；
- 保留 4 秒自动关闭、无回弹收起、纯黑修复。

版本：versionCode 75 / versionName 8.28，`dist/灵动岛-debug.apk`（调试包）；正式包见 `游龙灵动岛-v8.28-签名版.apk`。

## v8.27 更新（修复偶发“纯黑展开、无内容无动画”通知，2026-10-26）

- 新增 `IslandView.replayExpandedIfBlank()`：检测到 expanded 模式 content fade 的 target 仍为 0 时，先 snap 回圆点再重播完整展开动画（不是只淡入内容），既修复纯黑也恢复展开效果；
- 在通知到达/重复通知、窗口 rebind、窗口丢失重新 attach、settle、tick（每 300ms）五处调用 `repairNotificationContentIfBlank()`，附带 `Log.w("repaired blank notification content (replayed expand, where)")`；
- 新通知 key 会重置 4 秒自动关闭计时；同一通知 update 不重置；
- 根因判断：窗口 detach/rebind 或关闭动画竞态后，view 处于 NOTIF_EXPANDED 但 content 层 target alpha 被留在 0，于是只画黑色卡片且不再跑动画。

版本：versionCode 74 / versionName 8.27，`dist/灵动岛-debug.apk`，454,827 B，SHA-256 `B23B2CB09B74D4E4BE87B6343168B70288E8BB62B0FD27075F86F2F7ED0A10B3`。

## v8.26 更新（充电显示逐帧 1:1 + 不显示快速/超快充电，2026-10-25）

按新视频逐帧分析（1920×432@30fps，259 帧，`verify/ref_charge_video/ANALYSIS.md`）：

- 稳定态岛体 **0.5255W × 0.0870W**，纯黑底；标签 cap 0.329h、左内距 0.341h；百分比 cap 0.275h、右内距 1.048h；闪电高 0.431h、中心右内距 0.560h；
- v8.25 已调整：卡宽 **0.526W**、标签字号 **0.364h/左距 0.318h**、百分比 **0.360h/右距 1.034h**、闪电盒 **0.575h/中心0.560h**、充电绿改为 **#28E080**、去掉绿色 ambient 洗色；
- v8.26：**不再显示「快速充电 / 超快充电」**：
  - label 永远「正在充电」；
  - 百分比永远整数，不再显示快速充小数；
  - 闪电永远单枚绿色，不再双闪电/黄色；
  - 快速充光晕/闪烁禁用；
- 展开约 0.33s、收起约 0.57s，沿用原 spring/fade，不改动画；
- 参考 206 帧开始收起是拔充电器事件，不是定时隐藏。

版本：versionCode 73 / versionName 8.26，`dist/灵动岛-debug.apk`，453,927 B，SHA-256 `6F892F62A020DB4AE421BF7ACE423F52CEE1905C97AA15D1AA40179293E647E0`。

## v8.24 更新（删除通知回复功能，2026-10-25）

按你的要求移除岛内回复：

- `fillNotification` 遇到带 **RemoteInput** 的 action 直接跳过，不再显示「回复」按钮；
- 删除消息 App 的合成「回复」按钮，不再拦截 action 打开回复输入框；
- 通知其他普通 action 仍正常显示/执行；
- 无障碍配置恢复为 `canRetrieveWindowContent=false`、`flagDefault`，不再读取 App 窗口内容；
- 通知 4 秒自动关闭、收起保留动画但无回弹等 v8.22/v8.23 行为保留。

版本：versionCode 71 / versionName 8.24，`dist/灵动岛-debug.apk`，455,043 B，SHA-256 `73A268E17BCDFED4B85EF0B02D2770D45CB3D29B268321A8287D217C8C48CB02`。

## v8.23 更新（4 秒自动关闭 + 保留收起动画去回弹 + QQ/微信发送修复，2026-10-25）

- **通知展开卡 4 秒自动关闭**：
  - 首次展开启动 `notifAutoHideTask`（4000ms）；同一通知 update / remove+repost 不重置；
  - 用户输入回复时不打断，延后 1s；
- **保留收起动画，只去掉回弹**：
  - v8.21 的“瞬时 snap 删除全部动画”过于激进，v8.22 已改为 **HideNoBounce**：仍执行收起动画，但宽度/高度/圆角弹簧改为临界阻尼 ζ=1.0，无 overshoot；
  - 音乐/充电/常驻自身的动画完全不变；
- **QQ/微信强制发送修复**：
  - 发送时先 `ACTION_SET_TEXT`，失败用剪贴板 + `ACTION_PASTE`；
  - 等待 140ms 让输入框 text watcher 刷新，再重新取 App 窗口；
  - 发送按钮优先按 `pkg:id/send_btn`（QQ 已确认 `com.tencent.mobileqq:id/send_btn`），再按“发送/Send”文本查找，点击时不再要求当时 enabled；
  - 优先选择“真正包含 EditText 的那个 App 窗口”，避免拿到同包名的小窗/悬浮窗。

**真机验证**：v8.22 下通知 2 秒仍在、5 秒已收起为小岛；v8.23 修复 QQ 发送按钮刷新问题（QQ 输入框 id `input`、发送按钮 id `send_btn` 已通过 uiautomator 确认）。

版本：versionCode 70 / versionName 8.23，`dist/灵动岛-debug.apk`，456,015 B，SHA-256 `3B927E70D167EABDEBBD1C4299DCEB77662F87301D4130887B11C59277C2A5EE`。

## v8.20 更新（实机抓到根因：展开后立刻被“展开超时”收起，2026-10-25）

在真机上抓 logcat 复现后确认两个根因：

1. **展开超时计时器 `expandedAt` 是旧值**：
   - 通知从「充电/常驻」等已展开过的状态进入时，`applyMode(expanded)` 没有刷新 `expandedAt`；
   - tick 里 `now - expandedAt > EXPANDED_TIMEOUT_MS` 立即成立，于是通知卡刚展开就被 `collapseExpanded()` 收起；
2. **通知卡被纳入展开超时**：即使不是旧值，到达超时时间也会自动收起。

v8.20：
- `onNotificationArrived` 和 `applyMode` 进入 expanded/charge 时都刷新 `expandedAt`；
- tick 的展开超时判断排除 `MODE_NOTIF_EXPANDED`，通知卡永不参与自动超时；
- 配合 v8.18 的 sticky dismiss、v8.19 的 WindowManager 残留清理。

**真机验证**：安装 v8.20 后 post 通知，通知卡保持展开并正确显示 QQ 通知内容；logcat 无 dismiss、无 addView 重复添加。

版本：versionCode 67 / versionName 8.20，`dist/灵动岛-debug.apk`，453,851 B，SHA-256 `9558C3DF3EFAA16DEB5E5DE9DE2AC850B0532CE579C2B2C2F58CABFD44935898`。

## v8.18 更新（修复展开中途被自动合上，2026-10-25）

- 你看到的“刚展开一点就自动合上”是 **展开动画还没结束，之前 remove 事件排的 `notifDismissTask` 到点了**；
- v8.18：
  - `notifDismissTask` 只要 `notifActive && notifForceExpanded` 就直接 return，永不自动收起通知卡；
  - `scheduleNotificationDismiss()` 在 `notifForceExpanded` 时直接 return，不再排任何自动 dismiss；
  - ticker 的 6 秒紧凑通知自动隐藏也跳过 `notifForceExpanded`；
  - 只有用户点正文打开 App、点 action/回复、上滑才关闭；点屏幕空白仍不收回；
- 通知卡可以在没有新通知、通知被 App remove/repost 的情况下一直保持展开。

版本：versionCode 65 / versionName 8.18，`dist/灵动岛-debug.apk`，453,723 B，SHA-256 `BC2C02C021FDFD446F0A53E0E463484E76624863645686D2E18679E339907A71`。

## v8.17 更新（彻底修复通知岛自展开/自收回循环，2026-10-25）

- 根因：微信/QQ 等 App 常用 **remove + re-post** 更新通知；原逻辑在 remove 时安排 dismiss，宽限期结束就收起，新 post 又展开，形成循环；
- v8.17：**展开态通知卡完全 sticky**：
  - `scheduleNotificationDismiss()` 检测到当前是 `MODE_NOTIF_EXPANDED` 直接 return，不安排任何自动收起；
  - 通知被移除、更新、重发都不会让大卡自己合上；
  - 只有用户点通知正文打开 App、点 action/回复、或上滑，才会收起；
- 点屏幕空白仍然不收回；
- 未读消息不会再“展开→合上”循环。

版本：versionCode 64 / versionName 8.17，`dist/灵动岛-debug.apk`，453,595 B，SHA-256 `F7192C376D7094B06BDD416B05F762C27D04C76DC80AEDDA658AD36282BF5109`。

## v8.16 更新（微信/QQ 强制回复适配，2026-10-25）

- v8.14 的合成回复只对“被识别为消息”的通知添加；微信/QQ 有些版本没有 category/messages extras/shortcut，因此回复按钮没出现；
- v8.16 增加 **`isMessagingPackage()`**：微信（com.tencent.mm）、QQ（mobileqq/tim）、企业微信、钉钉、WhatsApp、Telegram、飞书、Discord、LINE 等直接视为消息应用；
- 这些应用即使没有 RemoteInput，也会强制显示合成「回复」按钮；
- 强制回复发送：
  - 无障碍 `forceReply()`：找到 App 窗口/输入框，先 `ACTION_SET_TEXT`，失败再用剪贴板 + `ACTION_PASTE`，然后找「发送/Send」按钮点击；
  - 仍失败则复制到剪贴板并提示；
- **更新后必须重新关闭再开启一次增强模式**，因为无障碍配置在 v8.14 才改成 `canRetrieveWindowContent=true`；
- 如果微信/QQ 自定义控件拒绝 SET_TEXT/PASTE 或找不到发送按钮，就只能填入/复制，不能保证自动发送。

版本：versionCode 63 / versionName 8.16，`dist/灵动岛-debug.apk`，453,567 B，SHA-256 `AA92BD2F32A458146506092E0553064CE17A512F807A69DB304533AD64ADA9D7`。

## v8.15 更新（所有通知首条即展开，2026-10-25）

- 你反馈的 App 不满足 `CATEGORY_MESSAGE / RemoteInput / shortcutId / messages extras` 任一条件，所以 v8.13 的单条消息识别没命中，第一条仍是 compact；
- v8.15 取消依赖：`onNotificationArrived` 里 `notifForceExpanded` **无条件为 true**；
- 无论从隐藏/常驻/音乐/充电/已有通知来，无论什么类型的通知，第一条就直接展开大卡；
- 之前的“其他通知先 compact”规则被覆盖；如果你以后想恢复，只需改回 message-only。

版本：versionCode 62 / versionName 8.15，`dist/灵动岛-debug.apk`，452,955 B，SHA-256 `BCEE6294CB9BD94BC62B0C05B7E3F331A927D793586679970F37E2307D4B504A`。

## v8.14 更新（强制回复：无 RemoteInput 也能回复，2026-10-25）

- 消息类通知如果没有 RemoteInput，会在展开卡操作行追加一个**合成「回复」按钮**（优先占用最后一个可见位，最多 2 个普通 action + 回复）；
- 点它：先通过 `contentIntent` 打开对应 App，再弹出回复输入框；
- 发送时：
  - 有 RemoteInput → 仍走标准 direct-reply；
  - 无 RemoteInput → 调用增强模式 `IslandAccessibilityService.forceReply()`：
    - 按包名找到 App 窗口；
    - 找到可编辑输入框，`ACTION_FOCUS` + `ACTION_SET_TEXT` 写入文字；
    - 找「发送/Send」按钮并点击；
    - 找不到发送按钮时文字会留在输入框；
  - 无障碍不可用/失败 → 自动复制回复到剪贴板并提示粘贴发送；
- 为强制回复，无障碍配置改为 `canRetrieveWindowContent=true` + `flagRetrieveInteractiveWindows`；**需要在系统设置里重新开启一次增强模式**才会生效；
- 强制回复输入期间即使通知被 App 移除，也保留通知数据，发送完成后再收起。

版本：versionCode 61 / versionName 8.14，`dist/灵动岛-debug.apk`，453,011 B，SHA-256 `5ABE7AC995F65FFDD8BED74FBC72598D491AE388F663EFCC445BAE5C68D1FEFF`。

## v8.13 更新（单条消息也自动展开，2026-10-25）

- v8.12 的上下文规则保留：已有通知卡时直接 expanded；其他非消息通知从隐藏/常驻/音乐/充电进入先 compact；
- **消息类通知例外**：`isMessageNotification()` 检测以下任一条件时，即使只有一条、即使从隐藏/常驻/音乐/充电来，也直接 `notifForceExpanded=true` 展开大卡：
  - `CATEGORY_MESSAGE` / `CATEGORY_EMAIL`；
  - 有 `android.messages` / `android.people.list` extras；
  - 有 shortcutId（会话通知）；
  - 通知 action 含 RemoteInput（可回复消息）；
- 这样 QQ/微信这类单条消息不会再先显示小胶囊、等到第二条才展开。

版本：versionCode 60 / versionName 8.13，`dist/灵动岛-debug.apk`，449,347 B，SHA-256 `76F55913DE2AF0653B353BFCAE02DB84E82B1E69D0C3143E784DB1523568CED4`。

## v8.12 更新（只有“其他通知状态”直接大卡，2026-10-25）

按你的补充修正 v8.11：

- 来通知时若当前正在显示 **通知卡（compact 或 expanded）**：
  - `notifForceExpanded=true` → 直接/继续显示 **展开大卡**；
- 若当前是 **隐藏 / 常驻 idle / 音乐 / 充电**：
  - `notifForceExpanded=false` → 先显示 **compact 通知胶囊**，点它才展开成大卡；
  - 不再从音乐/充电/常驻状态直接抢成大卡；
- v8.10 的防自动收起、v8.11 的通知展开态上滑 dismiss 保留；
- 点空白不收回只对通知 expanded 生效。

版本：versionCode 59 / versionName 8.12，`dist/灵动岛-debug.apk`，448,923 B，SHA-256 `4CA322FD405A48865182329BDA7FD5AAA95F0066A2321BFE2925E45056812D7D`。

## v8.11 更新（通知岛彻底不再显示小胶囊，2026-10-25）

- `desiredMode` 对 `notifActive` 直接返回 **`MODE_NOTIF_EXPANDED`**，不再依赖 `notifForceExpanded` 条件；
- 从隐藏/常驻/音乐/充电/其他通知状态进入通知岛时，都是展开大卡；
- 通知展开态上滑不再收成 compact 胶囊，而是直接 dismiss 通知卡；点屏幕空白仍不收回；点正文打开应用并 dismiss；
- 结果是通知岛没有任何 compact 显示路径。

版本：versionCode 58 / versionName 8.11，`dist/灵动岛-debug.apk`，448,859 B，SHA-256 `F4EA4F0F4021DAA74632AF679AA697F339F489B73813CE6FF751C8D460C9013F`。

## v8.10 更新（修复通知岛自己反复展开/收回，2026-10-25）

- 通知岛反复展开/收回的原因是：很多 App 用「remove + re-post」更新通知，旧逻辑在 remove 时把 `notifForceExpanded` 清掉并 250ms 后 dismiss，随后新 post 又展开；
- v8.10：
  - 通知移除时**不再清 notifForceExpanded**；
  - 展开通知卡的自动 dismiss 宽限提高到 **1.2s**；
  - `notifDismissTask` 在真正隐藏前用 `NotificationListenerService.getActiveNotifications()` 检查该 key 是否仍在通知栏；仍在就 800ms 后再查，**不会把仍然存在的通知收起**；
  - 用户点卡/点应用/发送回复触发的主动 dismiss 不受这个检查影响。

版本：versionCode 57 / versionName 8.10，`dist/灵动岛-debug.apk`，448,883 B，SHA-256 `E47418B3E0E027C8D7B5BB4FCDA6BB663E7537F5F30B631B60ED4C98ADB04C58`。

## v8.8 更新（通知岛直接展开 + 点屏幕不收回，2026-10-25）

- 新通知不再先显示 compact 小胶囊：`notifForceExpanded=true`，`desiredMode` 直接返回 `MODE_NOTIF_EXPANDED`，view 从隐藏/其他状态直接 morph/展开到通知大卡；
- 如果当前已是 compact 通知卡，会立即提升为 expanded，不停留在小胶囊；
- **点击屏幕空白处不会收回通知卡**（`onIslandOutsideTap` 对 `MODE_NOTIF_EXPANDED` 直接 return）；上滑仍可收起，点卡正文仍打开应用并收起；
- 通知消失/点应用/发送回复后正常 dismiss，`notifForceExpanded` 复位。

版本：versionCode 55 / versionName 8.8，`dist/灵动岛-debug.apk`，448,331 B，SHA-256 `6F7A7389602F78730403338B243ADE34DC534B080517B725CFA0755225C02EFC`。

## v8.7 更新（强制显示隐藏的回复 action，2026-10-25）

- 之前只保存通知的前 3 个 action，回复 action 若排在第 4 个及以后就不会出现在岛上；
- 现在 `fillNotification` 会扫描**全部 actions**，找到第一个带 `RemoteInput` 的回复 action，并**强制占用最后一个可见按钮位**（最多保留 2 个普通 action + 回复）；
- 回复按钮即使在原始通知里排得很后，也会显示在展开卡操作行最右侧；
- 点击后仍走 v8.6 的输入框 + `RemoteInput.addResultsToIntent` 标准发送；
- 没有 RemoteInput 的通知无可伪造的回复通道，仍不会显示回复按钮。

版本：versionCode 54 / versionName 8.7，`dist/灵动岛-debug.apk`，448,047 B，SHA-256 `E71E1368CAB591430DE12C0A84F13B352620CE2D456C3E67A3561515BC9CFFF7`。

## v8.6 更新（通知灵动岛内直接回复，2026-10-25）

- 解析通知 action 的 **RemoteInput**，记住带输入框的 action（通常是「回复」按钮）；
- 展开通知卡里点这个 action 不再直接发送空 intent，而是弹出岛下方的**深色回复输入框**（EditText + 取消/发送）；
- 发送时用 `RemoteInput.addResultsToIntent` 把文字塞进原 action 的 PendingIntent 再 `send`，走系统标准 direct-reply 通道；
- 发送/取消/通知消失/服务销毁都会移除输入框；需要悬浮窗权限；
- 没有 RemoteInput 的通知 action 行为不变。

版本：versionCode 53 / versionName 8.6，`dist/灵动岛-debug.apk`，447,451 B，SHA-256 `39DE7E6DFD70E2BE35F66E551BD020968A9E5D128C119B04F8065B160DEA908C`。

## v8.5 更新（收起性能优化，不改动画轨迹/时长，2026-10-25）

- 收起/隐藏时不再对整个 overlay 窗口设置 **`RenderEffect` 动态模糊**；这个全窗口 GPU blur 是收起阶段最重的一笔，去掉后弹簧轨迹、时长、两段式节奏、内容淡入淡出全部不变；
- 展开时的 motion blur 保留；
- 收起/隐藏会清掉已经设置的 RenderEffect；下次展开/ morph 时重新允许；
- 不改变任何 spring 参数、Collapse phase 时间、窗口尺寸策略。

版本：versionCode 52 / versionName 8.5，`dist/灵动岛-debug.apk`，441,811 B，SHA-256 `CE92B3D351D9FF63A23612A5784AF08EAAE3E73CCCF308408FF611BEA6F7C938`。

## v8.4 更新（通知自动展开 + 点击跳转应用，2026-10-25）

- **有通知自动展开**：新通知到达后，服务在 240ms 后检查当前状态，若已显示通知 compact 胶囊则自动 `morphTo(MODE_NOTIF_EXPANDED)`；若还在从音乐/充电/隐藏过渡，会短暂重试（最多 6 次×180ms）；
- 自动展开后 hold 时间按展开态延长；
- 点击展开通知卡正文仍走原来的 `contentIntent`：**直接跳转对应应用并收起岛**；没有 contentIntent 时用包名 `launchApp`；按钮区域仍执行通知动作，不跳转；
- 通知在自动展开前被移除/点击时，自动展开任务会取消，不会对已消失的通知再弹卡。

版本：versionCode 51 / versionName 8.4，`dist/灵动岛-debug.apk`，441,719 B，SHA-256 `468666356CFA1FE75B56C551B62E14EBE7EFAE0D3E710C1BA7FEC2F22F217773`。

## v8.3 更新（删除计时器灵动岛，2026-10-25）

按要求移除计时器灵动岛功能：

- App 里去掉 **「系统时钟计时」预览按钮** 和 **「计时器应用」选择区**；
- `desiredMode` 不再返回计时器模式（预览计时 demo 也忽略）；计时胶囊/展开卡不会主动出现；
- `onNotificationPosted` 识别到秒表/计时器通知时直接 **吞掉**，不会再当作普通消息通知弹岛；
- 计时器自动拉起服务逻辑一并移除；
- 音乐、充电、普通通知、常驻、尺寸自定义等全部保留。

版本：versionCode 50 / versionName 8.3，`dist/灵动岛-debug.apk`，440,275 B，SHA-256 `4A75C58BB19D1B4976A3E883CD7BA71489A6387E39DC389628099677089B19EC`。

## v8.2 更新（照抄 dynamicSpot 的“计时器应用”标记，2026-10-25）

拆了 `C:\Users\13190\Downloads\base.apk`（dynamicSpot 1.88），它的秒表识别不是靠猜通知标题，而是：

- 在通知应用列表里给某个 app 打 **`<package>_timerApp`** 标记；
- `NotificationObserverSpot.p()` / `w()` 里，被标记为该 app 的通知会绕过 ongoing 过滤，作为计时器来源处理。

对应照抄到本项目：

- App 新增「计时器应用」：
  - **选择计时器应用（秒表/计时器所在应用）**：单选列表，保存到 `timer_app_pkg`；
  - **清除计时器应用（恢复自动识别）**：删除标记；
- `looksLikeTimer`：如果通知包名等于 `timer_app_pkg`，且不是闹钟，直接判定为计时器（不再依赖 title/text/chronometer，系统把 extras 剥光也能识别）；
- 通知监听自动拉起服务也读取这个标记；
- 原有 v8.0/v8.1 的自动识别、动作 inferred running、计时优先充电、remove+repost 延迟清理全部保留。

**使用方法（关键）**：装好后打开 App → 计时器应用 → 选择「闹钟时钟」（`com.android.BBKClock`）→ 保存，再开秒表。

版本：versionCode 49 / versionName 8.2，`dist/灵动岛-debug.apk`，441,979 B，SHA-256 `ED7A63EE04E8C19D3CCAA93AB0CDBC0CC77DFFA0C237C2394E2D9099AFAA82B4`。

## v8.1 更新（计时器再修：动作识别 + 更新/移除竞态，2026-10-25）

- 有些 ROM 会把 Clock 通知的 extras 剥离给第三方监听，title/text 可能为空；新增按**通知按钮动作**识别计时器：
  - 时钟包通知里出现「计次 / 暂停 / 继续 / 重置 / start / pause / resume」等动作 → 认定为计时器；
  - 服务 `looksLikeTimer` 和监听自动拉起服务都使用该规则；
- 部分 OEM 时钟用「移除再重发」更新秒表：`onNotificationRemoved` 不再立即清计时状态，而是延迟 **1.2s** 清除；期间收到新通知会取消清理，避免新通知先到、旧移除后到导致状态被清空；
- 计时/音乐优先于充电、无 chronometer 时按动作推断 running 等 v8.0 修复保留。

版本：versionCode 48 / versionName 8.1，`dist/灵动岛-debug.apk`，435,771 B，SHA-256 `34D411BCAD8EF6CC04951FE0903144691CC2B945E4FD6BA5CA3F377F9BC4798B`。

## v8.0 更新（计时器真正显示：自动拉起服务 + 优先于充电，2026-10-25）

根据实测的 vivo 秒表通知（`com.android.BBKClock`，title=`秒表`，无 text/chronometer，actions=计次/暂停）：

- **数字刷新**：根据 actions 里的“暂停/继续”推断 `timer.running`，vivo 这类通知不再停住；
- **优先于充电**：`desiredMode` 改为 通知 → 计时器 → 音乐 → 充电 → 常驻，插着充电器时计时胶囊也能显示；
- **自动拉起服务**：如果 ROM 把灵动岛前台服务杀掉了，`IslandNotificationListener` 收到“秒表/计时”通知且 `enabled=true` 时会自动 `startForegroundService`（5 秒内只尝试一次），服务注册时会回放这条通知并进入计时状态；
- 计时通知的识别仍兼容 title/subText/infoText、时间文本、stopwatch category、timer 包 ongoing 通知。

> 注意：真实计时检测依赖「通知使用权」；如果系统设置里这一项没开，预览能用但真实秒表收不到。App 状态页可确认。

版本：versionCode 47 / versionName 8.0，`dist/灵动岛-debug.apk`，433,719 B，SHA-256 `7B1BC3A88A8EE6B9671F0F22F14465E357B9DDFD95CC0CB4232495E5CE23B0AA`。

## v7.9 更新（vivo 秒表通知适配 + 计时优先显示，2026-10-25）

根据你发来的通知截图（闹钟时钟 / 秒表 48:10）和设备 `dumpsys notification` 实测，这条通知的字段是：

- `pkg=com.android.BBKClock`，`android.title="秒表"`，**没有 android.text、没有 EXTRA_SHOW_CHRONOMETER**，`when` 是开始时间，actions=计次/暂停。

对应修法：

- `looksLikeTimer` 已能靠 title“秒表”识别（v7.8 扩展仍保留）；
- **v7.9 关键修复**：这类 OEM 秒表通知没有 `EXTRA_SHOW_CHRONOMETER`，原来 `timer.running=false`，虽然会显示但数字**不刷新**；现在根据通知 actions 判断：
  - 有“暂停/pause” → running=true；
  - 有“继续/恢复/resume” → running=false；
  - 标题含“秒表/stopwatch”且 `when>0` → running=true；
- `onTimerNotificationPosted` 的初始 tick 也改用 `timer.running`；
- **计时/音乐优先于充电**：`desiredMode` 顺序改为 通知 → **计时器 → 音乐** → 充电 → 常驻 idle。之前插着充电器时充电分支会一直压住计时器，导致“计时了但灵动岛不显示”；现在即使充电中也会显示计时胶囊。

版本：versionCode 46 / versionName 7.9，`dist/灵动岛-debug.apk`，432,899 B，SHA-256 `E240C0E1961A521C42572B9EB9FB5036951771DF7F89CEB0BE68ACF603046544`。

## v7.8 更新（计时器检测增强，2026-10-25）

用户反馈开始计时后灵动岛检测不到，无法显示计时胶囊。

- `looksLikeTimer` 扩展：
  - 除 title/text 外也检查 **subText / infoText**；
  - 支持 `android.chronometerCountDown`（有些 ROM 只设置倒计时标记，不设置 `EXTRA_SHOW_CHRONOMETER`）；
  - 支持 `category=stopwatch/timer`；
  - 时钟包通知如果标题/文本/子文本里出现 `05:00` 这类时间格式，也识别为计时器；
  - `com.vivo.timerwidget` 这类包名含 `timer/stopwatch` 的 ongoing 通知，即使文字很少也识别为计时器；
  - 闹钟（标题/文本含“闹钟”或 `CATEGORY_ALARM`）仍排除，不会被误判成计时器；
- 计时器识别仍在普通通知应用白名单**之前**执行，所以 v7.6 的应用筛选不会挡掉计时器。

版本：versionCode 45 / versionName 7.8，`dist/灵动岛-debug.apk`，432,367 B，SHA-256 `93597A6B5EDCAB8A87FADF3E4F26A760A3103BFCBA05F6673755F3F75D34142D`。

## v7.7 更新（音波速度加快，2026-10-25）

- 紧凑音乐胶囊右侧音波的动画时间统一乘 **1.7×**（`WAVE_TIME_SCALE=1.7`）；
- 主频、抖动、包络、相位漂移一起加速，观感明显更快；波形幅度/颜色/位置不变。

版本：versionCode 44 / versionName 7.7，`dist/灵动岛-debug.apk`，431,667 B，SHA-256 `D90C20E9BC152522C8F844A71229E66C02F556345C56883E1730128ECCA1D74A`。

## v7.6 更新（通知灵动岛应用选择，2026-10-25）

新增「通知灵动岛应用」筛选：

- App 设置页新增：
  - **选择显示通知的应用**：多选列表（已安装且有桌面入口的应用，按名称排序），保存后写入 `notif_apps`；
  - **恢复显示全部应用通知**：删除 `notif_apps`，回到全部应用都弹通知的默认行为；
  - 对话框内置「全部不选」，可一键清空后再勾选单个应用。
- 服务端过滤：`onNotificationArrived` 在计时器识别之后、普通通知处理之前检查包名白名单，**只有被勾选的应用通知才会弹岛**；计时器/音乐等特殊状态不受影响；
- 默认（从未设置过）仍然全部应用可见；一旦保存过选择，就只显示选中的应用；保存「全部不选」= 屏蔽所有普通通知；
- 修改筛选后服务会 refresh，如果当前正在显示的通知所属应用已被取消勾选，会立即收起。

版本：versionCode 43 / versionName 7.6，`dist/灵动岛-debug.apk`，431,595 B，SHA-256 `C0113BE4EE99479D1323609691CADA2CEE64D7ACC3B48CE1B4636CF7136E43C1`。

## v7.5 更新（恢复成最初加入的龍 badge 样式，2026-10-25）

按你的要求，音乐展开卡的龍 badge 回退到 **v6.8 首次加入时**的样子：

- `music_badge.png` 恢复为原始 1260×1261 图**居中裁成正方形后直接缩到 256×256**（不紧裁、不调亮度/对比度、不平移）；
- `drawMusicBadge` 恢复为**整图铺满圆形裁切**，去掉 v6.9 金色描边、v7.2 黑色底板/0.94×内缩、v7.4 位移；
- badge 仍绘制在同一条控制行 `ctrlY=0.766h` 上；
- v7.3 的应用图标（完整龍图，不裁剪）和名称「游龙灵动岛bata【严禁外传】」保持不变。

版本：versionCode 42 / versionName 7.5，`dist/灵动岛-debug.apk`，423,871 B，SHA-256 `D3C91DB33AFC960A4D4D4EAAB0E9993A6BC284B434CB5F8CAFB90C65FAED2456`。

## v7.4 更新（龍 badge 图片在圆内精确居中，2026-10-25）

- 你说的是图片在圆里偏左/没居中；检查当前 `music_badge.png` 金色内容 bbox 为 x26..222、y25..230，中心是 **124,127.5**，确实比 256 画布中心偏左约 4px；
- 把整张 badge 图平移 **+4px 右、+1px 下**，金色龍 bbox 现在为 x30..226、y26..231，中心 **128,128.5**，与圆/黑底板同心；
- 圆内绘制时的 0.94×等距内缩保留，badge 中心仍与其他按钮同一 `ctrlY=0.766h`。

版本：versionCode 41 / versionName 7.4，`dist/灵动岛-debug.apk`，438,527 B，SHA-256 `9E59E51092E05C506CA52DE1CF8B23C7C963A932F2444A2A7AE652AEDCE2DF8D`。

## v7.3 更新（应用图标换龍图 + 改名，2026-10-25）

- **应用图标**：改用你发的金色「龍」原图，居中补成正方形后缩到 512×512 的 `ic_app_full.png`，**完整保留整张图不裁剪**；`android:icon`/`roundIcon` 都指向它；
- **应用名称**：改为 **「游龙灵动岛bata【严禁外传】」**（`app_name`），无障碍服务名改为「游龙灵动岛增强模式」；
- App 设置页标题改为「游龙灵动岛」，副标题带 `bata【严禁外传】`；前台服务通知标题/渠道名也同步为「游龙灵动岛…」；
- 应用图标由系统启动器决定是否再套圆角遮罩；我们给的是完整未裁切方形图。

版本：versionCode 40 / versionName 7.3，`dist/灵动岛-debug.apk`，439,255 B，SHA-256 `99553EE0CAD040A31686E74A6ABF51DAFB935148D1AEF05D158114DFDAE8ADB3`。

## v7.2 更新（音乐 app 图标视觉居中，2026-10-25）

- badge 仍绘制在与上一首/暂停/下一首完全相同的 `ctrlY=0.766h` 中心线上，不改变控制行位置；
- 新增**不透明黑色圆形底板**，并把图片以 **0.94×直径等距内缩**绘制，四周留出对称黑边；之前图片贴满圆形边缘、和深色卡片糊在一起，视觉上会显得金色龍偏上/偏左；
- 金色细环仍在圆边，整体圆形边界更明确，龍在圆内和按钮行上都居中。

版本：versionCode 39 / versionName 7.2，`dist/灵动岛-debug.apk`，237,549 B，SHA-256 `D8D7008FB580A438CF47009B183589C4C81A3E83391D0B7CA8E5F3BEF7C86DF0`。

## v7.1 更新（音波随封面换色 + 更无规律，2026-10-25）

- **音波颜色跟随封面**：紧凑胶囊右侧五根音波原来固定用红/粉渐变，现在改用当前专辑封面提取出的 `music.accent`，并混入少量白色保证深色封面上也看得见；换封面/换歌时音波颜色同步变化；
- **无规律度再提高**：在 v6.3 双正弦基础上增加第三、第四个不同频率的分量（含 10–15 rad/s 高频抖动）、一个缓慢漂移的相位，以及每根单独的慢速振幅包络；五根音波不再只靠固定相位差区分，起伏节奏更随机；
- 暂停时仍收为最低高度；波形幅度/位置不变。

版本：versionCode 38 / versionName 7.1，`dist/灵动岛-debug.apk`，237,473 B，SHA-256 `449057D6AD06AF11BF36A1E132B7EA397BF46FF4F95DC925689524DB409C3D1E`。

## v7.0 更新（没有音乐封面时显示应用图标，2026-10-25）

用户要求：没有音乐封面的应用，封面位置就显示它（应用自己的图标）。

- Android `MediaMetadata` 取不到 `ALBUM_ART/ART/DISPLAY_ICON` 时，服务异步读取当前媒体应用的 launcher icon（≤512px，缓存复用），填入音乐封面的 fallback；
- 紧凑胶囊的封面位置和展开卡的封面位置都会显示该应用图标，按原封面同样的圆角/center-crop 绘制；
- 无封面瞬间不再显示纯色渐变；图标加载失败/无包名时才回退到原 accent 渐变；
- 切歌/切应用时清理旧应用图标，避免串图；读取在 worker 线程，不阻塞动画；
- 应用图标仍是 v6.9 的龍图片 badge，不影响。

版本：versionCode 37 / versionName 7.0，`dist/灵动岛-debug.apk`，236,545 B，SHA-256 `8F03415B812959B081EA2FBFA16D039058418D7CC7A83868CBBFE4ECD116B6A5`。

## v6.9 更新（音乐 badge 图片放大/提亮 + 金色外圈，2026-10-25）

用户要求调整 v6.8 的龍图片 badge。

- 原图四周黑边太多，图片里的龍只占约 48% 宽度；重新按金色内容 bbox 裁成 760×760 正方形再缩放 256×256，龍现在约占圆内 **80%**；
- 亮度 +8%、对比度 +6%，暗部不再是一整块死黑；
- 圆形图片外加一圈细的暖金色描边（宽度 1.2% 直径），让它在黑色卡片上更像一个 app badge；
- 圆形裁切、淡入淡出、失败回退逻辑不变。

版本：versionCode 36 / versionName 6.9，`dist/灵动岛-debug.apk`，234,345 B，SHA-256 `CC5759024B2B88690FB5711EADE0147A00DF9C0453FE42F2B309B6CC4087B64A`。

## v6.8 更新（音乐展开卡 app 图标换成你发的龍图片，2026-10-25）

- 把你提供的金色「龍」图片裁成 256×256 PNG 放进 `res/drawable/music_badge.png`；
- 音乐展开卡左侧原来的珊瑚红圆形 + 音符，现在直接绘制这张图片（圆形裁切，随卡片缩放/淡入淡出）；
- 图片资源解码失败时仍回退到原红圆+音符，不会空图；
- 其他 app 图标/封面/按钮不变。

版本：versionCode 35 / versionName 6.8，`dist/灵动岛-debug.apk`，219,041 B，SHA-256 `1E4D4B252C5E009A325E96325A3957B0AE89636308D950FB2D95BB2625CC5B9D`。

## v6.7 更新（App 设置界面改为白色，2026-10-25）

用户要求把软件界面换成白色。

- `AppTheme` 从 `Theme.Material.NoActionBar` 改为 **`Theme.Material.Light.NoActionBar`**，`windowBackground/statusBarColor/navigationBarColor` 全部白色，并开启 `windowLightStatusBar/windowLightNavigationBar`（深色状态栏/导航栏图标）；
- MainActivity 所有硬编码深色全部换成浅色：
  - 页面背景纯白；
  - 标题 `#111114`、正文 `#33333A`、副标题/分组/提示 `#6B6B75~#777780`；
  - 按钮/滑块行浅灰 `#F4F5F8` + 浅边框 `#DADBE3`、深色文字；
  - 运行中状态文字仍为绿色 `#2E9E5B`；
- 灵动岛浮层本身（黑卡、内容颜色）保持不变，这次只改 App 设置页。

版本：versionCode 34 / versionName 6.7，`dist/灵动岛-debug.apk`，156,467 B，SHA-256 `42E122F89127B934330C2C17560AD44245C8C0C23790DC7AAFBD3889AFDA08AF`。

## v6.6 更新（音乐进度条加可见圆球，2026-10-25）

用户反馈音乐展开卡的进度条像一条直线，看不出播放位置。

- 在进度条当前播放位置新增一个**白色圆球**（半径 0.020×卡高，约 19px 直径 @1080），并加一圈半透明暗色 halo，保证在深色轨道和浅色已播放段上都清楚可见；
- 圆球位置随播放进度实时移动，并钳在进度条两端圆角内，不会溢出；
- 进度条轨道/已播放段颜色不变。

版本：versionCode 33 / versionName 6.6，`dist/灵动岛-debug.apk`，156,299 B，SHA-256 `C6BBFC77A6A9AE8579D86D5CAB3BD1FA217BDA7293F721E362FD81F8819986A9`。

## v6.5 更新（音乐 app 图标里的音符视觉居中，2026-10-25）

用户圈出音乐展开卡左侧红色 app 图标，反馈里面的音符图案偏了、不居中。

- 原因：Material 24dp 的 MusicNote path 实际 bbox 为 x7.7..19.3、y3..19，**在自身 24×24 viewport 里偏右约 1.5dp、偏上约 1dp**；`drawIcon` 只按 viewport 中心摆放，导致音符在红圆里看着偏；
- 新增 `drawIconCentered()`：先 `computeBounds` 再按**路径实际中心**摆放（缩放比例仍按 24dp），app 图标里的音符改为该方式绘制；
- 其他路径（上一首/暂停/下一首/心形）本身在 viewport 中基本居中，保持不变。

版本：versionCode 32 / versionName 6.5，`dist/灵动岛-debug.apk`，156,095 B，SHA-256 `D1C6B2AB3359CE7577BD8F6257103AA53814D0D53D8064E5911C928DD2394F24`。

## v6.4 更新（音乐展开卡底部控制区视觉对齐，2026-10-25）

用户圈出展开卡底部控制区，反馈按钮偏小、发灰/塑料感、视觉没对齐。

- **按钮 glyph 实际尺寸修正**：Material 24dp path 只占约 58% 盒高，旧 `0.115 h` 的盒尺寸让上/下一首、暂停的 glyph 只有参考的约 57%；改为 `0.175 h`，实际 glyph 高约 0.102h，和参考 `95/929` 对齐；
- **app 图标**从 0.157h 提到 **0.162h**，颜色从粉红改为参考的珊瑚红 **#FB6767**；
- **心形**单独用 0.138h（原 iconSize*1.02 会因放大 controls 变得过大）；
- **底部 bloom 减淡**：`accent 225→190`、`85→60`，避免控制区被灰亮渐变冲淡产生塑料感；
- 控制行中心仍为参考的 0.766h，五个按钮的 x 中心不变。

版本：versionCode 31 / versionName 6.4，`dist/灵动岛-debug.apk`，155,843 B，SHA-256 `7ED2CC52F3F4791000E31A7818D9ACBF40AABC67F9398888AB196078A48266F4`。

## v6.3 更新（音波去规律 + 常驻胶囊独立尺寸，2026-10-25）

- **音波去规律**：五根音波原来共用 8.9 rad/s 的单一正弦，只是相位不同，看起来像机械波浪；现在每根用**两个不同频率、不同相位的正弦叠加**（`WAVE_FREQ_A/B`、`WAVE_PHASE_A/B`），频率互不整除，五根不再同步起伏；
- **常驻胶囊独立尺寸**：新增「常驻胶囊宽 / 常驻胶囊高」两个滑杆（`geom_idle_w/h`），只作用于 always-on 的 idle 胶囊；调整它们不会影响紧凑音乐/计时胶囊或展开卡，反之亦然；
  - idle 宽默认 0.244W、高默认 0.0892W；范围宽 60–200%、高 60–160%，有屏幕上限钳制；
  - 修改后运行时重建视图立即生效。

版本：versionCode 30 / versionName 6.3，`dist/灵动岛-debug.apk`，155,819 B，SHA-256 `A68AC5B78E6B56A23B568893F7322084FC6E412269FA03BA2C550408EB15FAF9`。

## v6.2 更新（去掉常驻小圆点 + 加大胶囊波形幅度，2026-10-25）

- **去掉常驻 idle 胶囊中间的小圆点**：现在常驻时就是一块干净的黑色小胶囊（仍保留按下放大/松手回弹的反馈）；
- **紧凑音乐胶囊波形加大**：参考测量里的 `WAVE_RANGE` 是“占胶囊高度”的比例，旧代码又乘了一次 0.44，导致实际摆幅只有参考的约 43%；现在直接按胶囊高度计算，最大摆幅从约 18px 恢复到参考的 **42px**（1080 屏），五根音波上下起伏明显变大；
- 频率、相位、颜色不变。

版本：versionCode 29 / versionName 6.2，`dist/灵动岛-debug.apk`，154,983 B，SHA-256 `56F5BFBD02CB3C6EFF4C8DAEC05B7976C5767D3B2AD133CC94C888341EC8BA61`。

## v6.1 更新（常驻胶囊按压反馈，2026-10-25）

常驻 idle 胶囊现在点击有反馈：

- 按下时整块岛以中心为基准**微微放大**（约 +3%），松手后弹簧**弹回**原尺寸；
- 只有常驻 idle 状态触发这个按压反馈；音乐/计时/通知展开等原有交互不变；
- 按压使用独立的小数值弹簧（0→1→0），松手带一点回弹，不会影响岛体几何/窗口尺寸；
- idle 中间的小圆点也一起缩放，反馈更明显。

版本：versionCode 28 / versionName 6.1，`dist/灵动岛-debug.apk`，155,191 B，SHA-256 `A420BD020592A4A5E8CFF415F8084CC41063190B56F7DED1993907E9120FD7A5`。

## v6.0 更新（灵动岛常驻显示，2026-10-25）

App 内新增「灵动岛常驻」开关（SharedPreferences `always_on`）：

- 打开后，没有计时/音乐/通知/充电时不再隐藏，而是保留一个 **idle 小胶囊**（0.244W × compactH），中间一个低透明度小圆点，避免看起来像空胶囊/漏洞；
- 打开开关或服务启动时立即显示；从展开卡收起时直接两段式收成 idle 胶囊；关闭开关立即隐藏；
- 音乐/计时/通知/充电来了仍优先显示对应状态，结束后自动回到 idle；
- 与尺寸自定义、通知右移、空胶囊修复等全部兼容。

版本：versionCode 27 / versionName 6.0，`dist/灵动岛-debug.apk`，154,531 B，SHA-256 `100AC5F6BAE03DEE8D8154B7D369A9E25B744D092A3F9D9A01833A78308BF9A8`。

## v5.9 更新（修复收起后偶尔变成空胶囊，2026-10-25）

用户反馈：有时候岛收回后会停在一块空胶囊上，不显示应该有的图标/文字。

**原因**：两段式收起的 phase2（空胶囊→compact）之前要等空胶囊弹簧完全稳定后才启动 compact 内容 90ms 淡入；如果窗口重建/掉帧让 phase1 的稳定判定延迟，就会看到空胶囊停在那里。

**修法**：
- phase2 一开始（空胶囊宽度开始重排时）就同步启动 compact 内容淡入，不再等弹簧结束；
- phase1 加 **650ms 看门狗**：即使窗口短暂 detach/掉帧导致弹簧没 settle，也会强制进入 phase2，不会再卡在空胶囊；
- 内容淡入提前后，空胶囊可见时间只剩 phase1 的约 200ms，和参考一致。

版本：versionCode 26 / versionName 5.9，`dist/灵动岛-debug.apk`，152,359 B，SHA-256 `ECE46BDE31427B9BBD0A248A652A5DFC8AAE2DA87FA8CA5DBC7D5345A2196975`。

## v5.8 更新（通知岛内容整体右移，2026-10-25）

用户要求把“消息提示”的通知岛内容整体往右挪：

- **通知 compact 胶囊**：图标 + 文字整体右移 `0.22 × 胶囊高`（约 21px @1080），左侧留白变大，右侧文字自动收窄避免溢出；
- **通知展开卡**：图标 + 文字列整体右移 `0.020 × 展开卡宽`（约 21px @1080），文字列宽度相应缩小，右侧边距保持不变；
- 通知 action 按钮命中区同步右移，点击位置不失配；
- 音乐/计时/充电等其他状态不受影响。

版本：versionCode 25 / versionName 5.8，`dist/灵动岛-debug.apk`，151,807 B，SHA-256 `911F76D97FC0FB1BCC0649D15D1C2DD6D5497D8786D979D7275C1935EE3E2512`。

## v5.7 更新（灵动岛尺寸自定义，2026-10-25）

App 内新增「灵动岛尺寸（自定义）」滑杆，所有值持久化在 `island` SharedPreferences：

| 滑杆 | 范围 | 默认 | 作用 |
|---|---|---|---|
| 整体缩放 | 70–130% | 100% | 所有岛体尺寸 |
| 紧凑胶囊宽 | 80–130% | 100% | compact pill 宽度 |
| 紧凑胶囊高 | 80–130% | 100% | compact pill 高度（同时影响 dp 内容） |
| 展开卡宽 | 85–115% | 100% | expanded card 宽度（最大不超过 0.985 屏宽） |
| 展开卡高 | 80–130% | 100% | 展开卡高度（音乐/计时/通知） |
| 展开卡圆角 | 70–130% | 100% | 大卡圆角 |

- 拖动松手即保存；服务在运行时自动重建视图/窗口并重新显示（会回到 compact 状态）；
- 「恢复默认尺寸」一键回 100%；
- 宽度/胶囊尺寸有屏幕上限钳制，避免超出屏幕被 ROM 裁切；
- 内容布局会按新卡宽/高重新缩放（v5.5 的 sticky 内容逻辑）。

版本：versionCode 24 / versionName 5.7，`dist/灵动岛-debug.apk`，151,607 B，SHA-256 `F2A2852EC67EFF2DFB370E00C947EEEC1573E72BCDC2D64D3A3482278924A3DC`。

## v5.6 更新（灵动岛内部视觉对齐，2026-10-25）

按 `analysis/video/SPEC.json` 的参考视频逐元素测量（card top/card h 归一化），把岛内元素重新对齐：

**音乐展开卡**（参考 card bbox y21..950, h929）：

| 元素 | 参考 | v5.6 |
|---|---|---|
| 封面 top | 0.150 H | 0.150 H |
| 标题 cap 顶 / cap 高 | 0.178 H / 0.091 H | **0.178 H / 0.091 H**（字号恢复 0.127 H） |
| 第二行 top | 0.319 H | 0.322 H（baseline = title baseline + 0.125 H） |
| 进度条 y | 0.542 H | **0.542 H** |
| 控制行中心 y | 0.766 H | 0.766 H |

**计时展开卡**（参考 card 3190×804, top32）：

| 元素 | 参考 | v5.6 |
|---|---|---|
| 数字 bbox 顶/底 | 0.346 H / 0.478 H | 字号 0.190 H，baseline **0.478 H** |
| “秒表” bbox 顶/底 | 0.543 H / 0.670 H | 字号 **0.160 H**，baseline **0.670 H** |
| 数字左缘 | 0.200 W_card | 0.200 W_card（间隙 0.036 W） |
| 播放/暂停中心 x | 0.731 / 0.885 W_card | **0.731 / 0.885 W_card** |

紧凑音乐/计时胶囊按 SPEC（封面 0.663 h、左距 0.071 pw、波形右距 0.076 pw、计时 badge 0.63h/左距 0.074 pw、数字 0.282h）原本已对齐，本轮未动；充电/通知没有新的参考样张，保持原布局。

> 展开卡外宽仍是 0.955W（为了给 +7% 回弹留屏边余量）；内部元素比例按 SPEC 对齐。设备在本轮安装时弹出“用户拒绝权限”，所以 v5.6 没有真机截图验证。

版本：versionCode 23 / versionName 5.6，`dist/灵动岛-debug.apk`，147,599 B，SHA-256 `5162999CDF1A7B50185156F6C7E253545617CE6476825A27A79D751755B4FBB5`。

## v5.5 更新（文字/图片真正“粘”在灵动岛上，2026-10-25）

用户定位到“僵硬”的根因：我们的展开卡内容是按**最终卡片位置**预排好、再由裁剪区域慢慢露出来，看起来像内容提前放在屏幕上、岛只是展开一个窗口；参考效果应该是文字和图片**粘在岛上**，跟着岛一起移动/缩放。

**v5.5 改动**：

- 音乐 / 计时 / 通知三种展开卡的内容改为：先在最终卡片坐标系里排版，再按**当前弹簧卡片尺寸**做 `translate + scale`；
- 展开时封面、标题、进度条、按钮都从岛内小尺寸**长出来并跟着岛移动**，收起时跟着岛一起缩回去（不再停在屏幕上等裁剪）；
- 文字行数/内容在动画中不重排（避免之前“有时候多有时候少”），只做整体缩放；
- 真机 60fps 录屏验证：展开时 `f_036` 内容还小、贴在岛左上并随岛放大；`f_045` 已接近全尺寸但带运动模糊；峰值约 1078px、stable 1032px；
- 展开仍为 v5.4 的 k=140/ζ=0.60；两段式收起、点击空白打开 App、文字固定/滚动、模糊等保持不变。

版本：versionCode 22 / versionName 5.5，`dist/灵动岛-debug.apk`，147,599 B，SHA-256 `F201DF86F2F45749D0BE24B351EA0C2FCE6C71FA534018651CB28D1E61FC035C`。

## v5.4 更新（展开稍微加快，2026-10-25）

v5.3 的 k=110 手感偏慢，用户要求加快一点：

| 项目 | v5.3 | **v5.4** |
|---|---|---|
| 展开 k | 110 | **140** |
| ζ | 0.60 | 0.60 |
| 60fps 过冲 | +7.6% | **+7.4%** |
| 峰值时刻 | ~350ms | **~317ms** |
| 稳定时间 | ~600ms | **~550ms** |

仍保留回弹和 0.955W 宽度余量，不会回到 v5.2 的“啪一下”。
版本：versionCode 21 / versionName 5.4，`dist/灵动岛-debug.apk`，147,435 B，SHA-256 `714F6741F6820E03FDC624DC040D17789DFAE2A5A22AB72BBAD70D8845AB9F12`。

## v5.3 更新（对比视频后把展开再放慢、恢复参考黑卡，2026-10-25）

用户提供 1440×1080@60fps 的「我们的 / 别人的」并排对比视频，反馈我们的展开仍然僵硬。

**对比分析**（`verify/video_160841/`）：

- 右侧「别人的」是**静态展开卡**（整段宽度平台 693 px 不变），无法从该视频直接拟合展开弹簧；
  它的卡片顶部采样为 **RGB 0,0,0 纯黑**，只有底部有蓝/青 bloom——所以 v5.3 **撤销了全卡模糊封面 wash**，回到黑顶+底部 bloom；
- 左侧「我们的」展开峰值来得快、峰值/收敛时间短（v5.2 k185 约 267ms 峰、483ms 稳），对比下显得“硬”；
- 参考节奏采用此前 142841 视频的展开峰 ~345ms、稳 ~600ms。

**v5.3 调整**：

- 展开刚度 k **185 → 110**（ζ 保持 0.60）：60fps 离散 **+7.6% 回弹**、峰值 **~350ms**、稳定 **~600ms**，回弹幅度仍在、但整体放慢约 30%，不再“啪一下”；
- expandedW 保持 0.955W 的宽度余量，峰值 ~1079px 仍能完整显示；
- 收起两段式、点击空白打开对应 App、文字固定/滚动、模糊等全部保留。

版本：versionCode 20 / versionName 5.3，`dist/灵动岛-debug.apk`，147,439 B，SHA-256 `BEFFF819AA7530B405DB889454B7D52AA6E3881F5ACC15510D82ADDB6DAB7181`。

## v5.2 更新（展开弹性加大，2026-10-25）

用户反馈 v5.0/v5.1 的展开“弹性不是预期效果”，要求加大。

- 展开宽/高阻尼从 ζ=0.72 降到 **ζ=0.60**（k 仍 185）→ 60fps 离散过冲从 +1.9% 提升到 **+7.0%**，峰约 267ms、约 483ms 稳定；
- **关键修复**：v5.0 的展开目标宽 0.980W 在 1080 竖屏上只剩约 20px 余量，+7% 过冲会撞到屏幕边缘被裁平，看起来反而“没有弹性”。v5.2 把展开目标宽改为 **0.955W**，给宽度过冲留出可见空间（真机实测展开峰值约 1080px、稳定 1032px，回弹约 48px；卡面仍占屏宽 95.5%）；
- 收起两段式、点空白打开对应 App、运动模糊等全部保留；
- 真机实测：compact 暗段 374 → expanded 峰 1080 → 稳 1032，宽度回弹清晰可见。

版本：versionCode 19 / versionName 5.2，`dist/灵动岛-debug.apk`，147,439 B，SHA-256 `8F5623280999C62E98B19886A4CFE7AD8BF0E0AEA792AA54FCA97ECAC963B3E2`。

## v5.1 更新（展开卡点空白处打开对应 App，2026-10-25）

用户需求：音乐展开后点击岛内**除按钮以外**的区域，直接跳到对应音乐 App。

- **音乐展开卡**：只有底部控制行（x 在五个按钮中心 ±0.079W、y 在 0.66–0.86H 内）算按钮，
  继续执行上一首/暂停/下一首；点击封面、标题、第二行、进度条等**其他任何位置** →
  用 `MediaController.getPackageName()` 启动源 App，并留下 compact 播放胶囊；
- **通知展开卡**：点击非 action 区域本来就是打开 `contentIntent`；新增 contentIntent 为空时
  用通知包名 `getLaunchIntentForPackage()` 兜底；
- **计时展开卡**：点两个圆钮仍走 Clock action；点卡内其他地方打开 Clock/计时器通知的来源 App，
  没有包名时回退 `AlarmClock.ACTION_SHOW_TIMERS`；
- 预览（demo）没有真实来源 App，点音乐卡体会提示“预览模式没有关联应用，请用真实音乐 App 测试”，
  不会静默失败。

版本：versionCode 18 / versionName 5.1，`dist/灵动岛-debug.apk`，147,439 B，SHA-256 `9B76EB08B714A6BF80B7EDBCAA62252E940B606EE2E518F1FD8C10BE4019BE82`。

## v5.0 更新（按新视频逐帧 1:1 还原动画，2026-10-25）

用户提供 `video_20261025_142841.mp4`（1776×1080@30fps，331 帧，音乐胶囊多组开合）要求逐帧分析、1:1 还原。

**逐帧测量结论**（完整报告：[`verify/ref_video_142841/ANALYSIS.md`](verify/ref_video_142841/ANALYSIS.md)）：

- compact **652×158**（0.367W×0.089W），expanded **1758×783**（0.99W×0.441W），卡片 top 17；
- **展开**：宽/高归一化进度几乎完全一致（高度不再比宽度快），峰值仅 **+1.7~1.8%**、约 345ms，约 600ms 稳定；
- **收起是严格两段式**：
  1. phase1 先快速收成 **空胶囊 435×158**（0.244W×compactH，约 200–220ms，内容消失）；
  2. phase2 空胶囊 → compact（宽度 435→620，约 **+6.4% 回弹**，约 280–300ms），高度全程钳在 158；
- 展开卡标题 cap 顶 0.223H、字号 0.114H；第二行 cap 顶 0.329H；封面 top 0.150H；标题 marquee 滚动。

**v5.0 实现**：

- 展开宽/高共用 **ζ0.72 / k185**（按本项目 Spring 离散积分拟合，+1.9% 峰值、约 345ms 峰、约 550ms 稳）；
- `collapseToCompact` 改为两段式：phase1 `COLLAPSE1 ζ0.78/k740` → `minPillW()`；phase2 `RELAYOUT ζ0.60/k300` → compactW；高度 `collapseHeightClamp` 钳在 compactH；
- phase1 全程无内容，phase2 弹簧稳定后 compact 内容 90ms 淡入（与参考一致）；
- 标题/第二行/封面的字号与纵向位置按视频重新对齐（标题不再偏高）；
- 原有的运动模糊、标题 marquee、不透明卡片、横屏不变大等全部保留。

真机首轮录屏（旧 phase2）已验证两段式成立、空胶囊出现、展开过冲 +1.75% 与视频一致；phase2 时序按视频重调后，最终参数因手机被用户测试 App/USB 授权占用未能二次录制。

- 版本：versionCode 17 / versionName 5.0，`dist/灵动岛-debug.apk`，146,247 B，SHA-256 `BEA90E7B6A0EFB1CAB5EBCF285BAA12C9F1CCE8B903D7CE627DF79EB87EBB63F`。

## v4.0 更新（按 F:\ANIMATION.md §2.2 原值恢复弹簧，2026-10-25）

用户指定参考 `F:\ANIMATION.md` 调整曲线。已把弹簧参数恢复为手册 §2.2 的**原值**（此前几轮按手感调过阻尼）：

| 场景 | 轴 | ζ | k | 手册理论过冲/欠冲 | 60fps 离散仿真 |
|---|---|---|---|---|---|
| 展开 | 宽 | **0.58** | 110 | +10.7% | +8.82%（617ms） |
| 展开 | 高 | **0.62** | 320 | +8.4% | +5.04%（283ms） |
| 收起 | 宽 | **0.47** | 165 | −18.8% | −16.67%（667ms） |
| 收起 | 高 | **0.55** | 320 | −12.6% | −9.35%（350ms） |

同时核对手册其余约定，代码本来一致：半隐式欧拉、dt≤32ms、`|Δ|<5px && |v|<120px/s` 视觉稳定门限、`SPRING_HEADROOM=1.18`（过冲不被窗口裁）、内容 340/150/170/90/170ms 与 75/60ms 延迟、两种 cubic-bezier、`windowAnimations=0`+`PRIVATE_FLAG_NO_MOVE_ANIMATION`。

- 本次恢复的是手册的**完整参数表**，所以收起会重新出现手册强调的“皮筋”欠冲（−16.7%）；展开宽 +8.8%。
- 版本：versionCode 16 / versionName 4.0（`dist/灵动岛-debug.apk`）。

## v3.9.3 更新（弹簧再调弹一点，2026-10-25）

用户反馈 v3.9.2 仍然“有点僵硬”，继续降低阻尼（刚度不变、速度不变，回弹更大）：

| 过渡 | v3.9.2 ζ | **v3.9.3 ζ** | 60fps 离散仿真过冲 |
|---|---|---|---|
| 展开 宽 | 0.66 | **0.60** | +4.5% → **+7.6%** |
| 展开 高 | 0.70 | **0.63** | +1.4% → **+4.5%** |
| 收起 宽 | 0.56 | **0.50** | −9.6% → **−14.2%** |
| 收起 高 | 0.63 | **0.57** | −4.5% → **−8.0%** |

这组数值已经接近最初 v3.7 的弹簧（+8.8%/−16.7%），但略收一点；稳定时间仍约 0.6s/0.5s。
版本：versionCode 15 / versionName 3.9.3（`dist/灵动岛-debug.apk`）。

## v3.9.2 更新（弹簧阻尼回调小一点，2026-10-25）

用户反馈“弹性动画怎么这么小，阻尼有点太大了”：

| 过渡 | v3.8 ζ | **v3.9.2 ζ** | 离散仿真过冲（60fps） |
|---|---|---|---|
| 展开 宽 | 0.72 | **0.66** | +2.2% → **+4.5%** |
| 展开 高 | 0.75 | **0.70** | −0.8% → **+1.4%** |
| 收起 宽 | 0.64 | **0.56** | −5.0% → **−9.6%** |
| 收起 高 | 0.70 | **0.63** | −1.7% → **−4.5%** |

- 刚度 k 完全不变，速度不变，只是回弹幅度变大（比 v3.8 明显更有弹性，但仍小于最初 v3.7 的 +8.8%/−16.7%）；
- 版本：versionCode 14 / versionName 3.9.2（`dist/灵动岛-debug.apk`）。

## v3.9.1 更新（横屏/游戏时灵动岛变得巨大修复，2026-10-25）

**问题**：打游戏时屏幕转到横屏，灵动岛突然变得非常大。

**原因**：所有几何比例以 `IslandSpec.W`（屏幕宽）为基准；横屏时 `DisplayMetrics.widthPixels` 从 1080 变成 2376，`HC/compactW/expandedW` 全部按 2.2 倍放大（例如展开卡从 1058px 变成 2328px）。

**修法**：`IslandSpec.init()` 改用**屏幕短边**作为基准宽度 `W = min(widthPixels, heightPixels)`，`H = max(...)`。竖屏不变（1080），横屏仍用 1080，所以岛屿保持和竖屏相同的物理尺寸（紧凑胶囊 397px、展开卡 1058px、卡高 96/474px），只是居中显示在横屏顶部。

- 旋转后仍由 `onConfigurationChanged` 重新标定几何并重建悬浮窗；
- 版本：versionCode 13 / versionName 3.9.1（`dist/灵动岛-debug.apk`）。

> 本轮未在真机上复测横屏（设备当时正在被用户使用），但改动为纯几何基准修复，竖屏比例与之前完全一致。

## v3.9 更新（展开文字固定 + 溢出自动滚动，2026-10-25）

用户反馈“展开的时候文字会动、有时多有时少”：

1. **文字固定**：音乐/计时/通知展开卡全部改为按**最终展开尺寸**排版，并以最终卡片中心/顶部为锚点；弹簧展开/收起期间文字不再跟着 `cardW/cardH` 缩放、重排或跳位，卡片裁剪区域只是逐渐露出这份固定布局；
2. **溢出自动滚动**：展开动画（弹簧+淡入淡出）结束并停顿 600ms 后，超出的文字开始缓慢滚动：
   - 音乐标题、通知标题：水平滚动；
   - 通知正文：卡片固定显示 6 行，超出部分垂直自动滚动（最多预排版 24 行）；
   - 滚动到尾部停顿 700ms 后回到开头循环；收起/切换状态时立即复位；
3. **只在需要时跑帧**：展开且有文字溢出时才维持 60fps 滚动循环，无溢出时仍保持“静止停帧”；
4. **真机测试中顺带修复**：切换无障碍宿主时 `removeViewImmediate` 未完成就 addView 会导致“View has already been added”，岛直接消失；现在 rebind 延迟 80ms 再挂载，且 tick 对“有内容但窗口未附着”每 ~1.2s 自动重试重挂；
5. 版本：versionCode 12 / versionName 3.9（`dist/灵动岛-debug.apk`）。

## v3.8 更新（弹簧曲线调柔 + 真机实测，2026-10-25）

用户反馈“弹簧有点夸张”后，调高阻尼、保持刚度（速度不变、回弹更小），并在真机（vivo V2133A / Android 14 / 1080×2376 / 480dpi）上实测：

| 过渡 | 原 ζ | 新 ζ | 理论过冲 | 真机实测（30fps 录屏量宽） |
|---|---|---|---|---|
| 展开 宽 | 0.58 | **0.72** | +3.8% | **+2.4%**（峰 1074px，目标 1058px） |
| 展开 高 | 0.62 | **0.75** | +2.8% | +1.2%（峰 270px，目标 268px） |
| 收起 宽 | 0.47 | **0.64** | −7.3% | **−5.1%**（谷 362px，目标 396px） |
| 收起 高 | 0.55 | **0.70** | −4.6% | 实测被模糊/检测下限遮挡，理论约 −4.6% |

- 刚度 k 不变（展开 110/320、收起 165/320），所以动画速度和以前接近，只是回弹幅度大幅减小；
- 真机实测：展开约 0.57s 稳定、收起约 0.53s 稳定；配合 v3.7 的运动模糊，观感不再“弹得很夸张”；
- 真机测试还发现并修复一个 bug：自家前台通知文本含“计时器”，曾被误判成系统时钟计时器，把展开计时卡的副标题改成“灵动岛正在运行”；现在直接忽略自家包的通知；
- 真机测试确认：音乐展开卡的时间/进度条已不再重叠，通知卡/真实电量等工作正常；
- **vivo/OriginOS 普通悬浮窗在状态栏区域的触摸会被系统状态栏拦截**：要在顶部胶囊上点击展开，需要开启 App 内「增强模式（无障碍）」；这是测试中确认的 ROM 行为（App 早已提供该模式）。
- 版本：versionCode 11 / versionName 3.8（`dist/灵动岛-debug.apk`）。

## v3.7 更新（展开/收起模糊过渡，2026-10-25）

用户反馈展开/收起“太直接”，加入运动模糊过渡：

- `IslandView` 每帧按宽/高/圆角三个弹簧的**速度**计算模糊半径（`min(9dp×density, v × 0.0022)`），通过 `View.setRenderEffect(RenderEffect.createBlurEffect(...))` 应用（API 31+；API 28–30 自动跳过、不崩溃）；
- 弹簧快速移动时整岛（含内容）轻微模糊，速度降下来自动变清晰，静止时完全锐利；展开、收起、隐藏、通知卡展开/收回、计时/音乐/充电状态切换都生效；
- `snapHidden()`/窗口 detach 时强制清除 RenderEffect，不会残留“永久模糊”；
- 模糊只影响绘制，不改变布局、窗口尺寸、触摸范围与按钮命中；
- 版本：versionCode 10 / versionName 3.7（`dist/灵动岛-debug.apk`）。

## v3.6 更新（通知展开卡重排 + 防闪烁，2026-10-25）

按用户提供的 QQ 通知截图与 10 秒录屏优化：

1. **通知展开卡改为内容自适应高度**（参考 dynamicSpot `popup_big` 的 `wrap_content`）：60dp 圆图标在左、右侧文本列（11sp 应用名/时间、15sp 标题、13sp 正文最多 6 行、最多 3 个操作按钮、12dp 按钮间距），4dp 卡内边距 + 16dp 文本列边距；标题/正文宽度按卡宽计算，长标题不再过早截断，正文不再挤在图标下方，**一两行内容时不再留一大片空白**（高度 0.18W–0.55W 之间按内容计算）；
2. **防闪烁**：QQ/微信群通知“先移除再重发”时，收起动作延迟 250ms；期间新通知到达则原地更新卡片（不闪空胶囊/空卡）；同一应用通知 key 变化时保留旧图标直到新图标加载完成；
3. 通知展开卡高度已接入弹簧目标与悬浮窗尺寸计算（`notificationExpandedHeight()`），窗口随内容增减；
4. 版本：versionCode 9 / versionName 3.6（`dist/灵动岛-debug.apk`）。

## v3.5 更新（音乐展开卡视觉对齐，2026-10-25）

按用户反馈的“灵动岛视觉对齐”和参考录屏逐帧测量的数值重排音乐展开卡：

1. **进度条不再穿过时间文字**：进度条左右端点按“当前时间/总时长”两个文本的实际测量宽度收窄（`max(0.161w, 左文字宽+间距)` / `min(0.844w, 右文字宽+间距)`），不再与 `00:03 / 00:33` 重叠；时间字号对齐录屏（≈0.100 h_card）；
2. **标题/歌手垂直位置对齐录屏**：标题帽顶从 0.216 h_card 上移到 0.178 h_card，歌手基线相应调整到标题下方 0.125 h_card；
3. **标题区回到纯黑**：删除了封面周围的径向泛光（之前把整卡洗成灰蓝色、看起来像半透明），只保留参考录屏的“顶部黑 → 底部专辑取色泛光”；音乐紧凑胶囊也改为纯黑；
4. **封面圆角**从 0.16 改为 0.10（录屏实测 26/282≈0.09）；
5. 进度条填充色改为录屏实测的暖白 `#E0D6D4`；
6. 版本：versionCode 8 / versionName 3.5（`dist/灵动岛-debug.apk`）。

## v3.4 更新（灵动岛改为完全不透明，2026-10-25）

- 计时展开卡底部原来是参考录屏的「顶部不透明 → 底部约 62% 黑」半透明层，用户反馈看起来像半透明 → 现在**所有状态都是完全不透明黑卡**（计时展开、通知大卡、音乐大卡、充电/紧凑胶囊一致），删除了该渐变与相关常量；
- 通知展开卡的操作按钮底色由半透明 `#65858585` 改为不透明 `#3A3A3C`；
- 版本：versionCode 7 / versionName 3.4（`dist/灵动岛-debug.apk`）。

## v3.3 更新（通知灵动岛 + 真实电量，2026-10-25）

按用户提供的参考 APK（`com.jamworks.dynamicspot` v1.88，已用 apktool/jadx 拆包）补齐通知能力，并把充电百分比改为真实电量：

1. **来通知也显示**（对应参考 `popup_first` 紧凑胶囊 + `popup_big` 展开大卡）：
   - 任意应用通知 → 顶部黑色胶囊：应用图标（圆形）+ 标题（超长省略，宽度随文本动态伸缩）；
   - 点胶囊展开大卡：圆形应用图标、应用名 + 时间、标题、最多 4 行正文、底部最多 3 个操作按钮；
   - 点操作按钮直接发送通知的 `PendingIntent`；点正文打开应用；点岛外/上滑收起；6 秒自动收起；
   - 通知被移除立即收起；音乐/计时/充电进行中来通知会暂时显示通知，通知消失后自动回到原状态；
   - 过滤：自家 FGS 通知、系统 `android` 通知、启动时回放的旧通知、群摘要、常驻通知（非通话/短信）、MediaStyle 媒体通知（由音乐模式负责）、空内容通知。
2. **充电显示真实电量**：插电触发与“充电动效”预览都从 sticky `ACTION_BATTERY_CHANGED` / `BATTERY_PROPERTY_CAPACITY` / `CHARGE_COUNTER` 读取真实百分比，不再写死 65% / 65.5x%；只有 ROM 提供真实小数（电荷计数器）时才显示两位小数。
3. **抄自参考 APK 的其他细节**：通知动作按钮（最多 3 个）与 `PendingIntent` 语义、计时器展开卡两个圆钮接系统时钟通知的暂停/继续 action、应用名/图标缓存、通知自动隐藏时序。
4. **权限**：新增 `QUERY_ALL_PACKAGES`（Android 11+ 才能解析通知来源应用的名称/图标；侧载无碍，上架 Google Play 需按政策申报）。
5. **版本**：versionCode 6 / versionName 3.3（`dist/灵动岛-debug.apk`）。

## 实现摘要

- 纯 Java + 自绘 Canvas 的悬浮窗（`TYPE_APPLICATION_OVERLAY`），无 AndroidX/Compose 依赖，APK 约 140 KB。
- 全部几何尺寸以录屏实测的「屏宽比例」标定，因此在任何屏幕上都能还原录屏中的比例；参数集中在 `IslandSpec.java`。
- 四种状态：通知（图标+标题胶囊 ⇄ 详情大卡 + 操作按钮）、充电（圆点→胶囊，正在充电→快速充电→超快充电 的文案/颜色/双闪电一次性光爆）、
  系统时钟秒表/计时器（胶囊 + 展开控制卡，两段式收起）、
  音乐（封面 + 5 根红粉波形胶囊 + 展开播放器，背景按封面取色；暂停保留胶囊）。
- 动画曲线来自 `analysis/video/CURVES.md` 的逐帧拟合：出现 +2.4% 回弹、展开弹簧、收起两段式、充电收起指数缓出、快充光爆一次性包络。
- 数据来源：BatteryManager 广播（插拔边沿触发）、NotificationListenerService（每秒读数、断线重绑、启动回放通知）、
  MediaSessionManager（封面/标题/进度/播放态）。

## 参考规格（逐帧实测，详见 `analysis/video/REPORT.md`）

三段录屏均为同一手机的全屏宽顶部裁切（相对比例 = 帧宽：`chg 1920 : timer 3256 : music 2118`），
所有数字均为占屏幕宽 W 的比例：

| 状态 | 尺寸 | 说明 |
|---|---|---|
| 胶囊（compact） | 0.3664～0.3687 W × 0.0894～0.0896 W，全圆角 | 音乐/计时器胶囊一致，r = h/2 |
| 展开卡片 | 0.9799～0.9806 W，圆角 0.0739～0.0742 W | 音乐高 0.4386 W；计时高 0.2469 W（两张卡片同绝对圆角） |
| 收起中间态 | 0.244 W 宽 | 内容瞬间消失后的空胶囊 |
| 充电胶囊 | 0.5266 W × 0.0896 W | 文案 + 电量 + 单/双闪电 |
| 顶部位置 | 卡片顶距屏幕顶 0.0099 W | 与状态栏时间垂直居中 |
| 计时常量 | 计时图标 0.63 h、读数 0.282 h；展开图标/按钮 0.474 h_card、读数 0.137 h_card | 副标题 #587185，按钮 #CDCDD0 / #FE9043 |
| 音乐常量 | 胶囊封面 0.663 h；展开封面 0.136 w_card、标题帽高 0.091 h_card | 进度条 0.161–0.844 w_card，五个按钮中心 0.100/0.296/0.496/0.695/0.893 |
| 波形 | 5 条，条宽 12–13px、间距 23.5px、高 12–83px（占视频原生像素） | 颜色 #E24B65，中心条幅度最大 |

动画时序（`analysis/video/CURVES.md` 拟合值 → 代码实现）：

| 过渡 | 时长 | 曲线 |
|---|---|---|
| 充电出现（圆点→胶囊） | 560ms | spring ζ=0.65, ω=11.0（宽度 +2.4% 回弹）；内容延迟 230ms、370ms 淡入 |
| 充电收起（胶囊→圆点） | 500ms + 650ms 圆点保持 | 指数缓出 k=2.2（拟合 RMSE 0.08） |
| 计时展开 | 380ms | spring ζ=0.80, ω=15.0（拟合 RMSE 0.01） |
| 音乐展开 | 300ms | spring ζ=0.62, ω=10.0（拟合 RMSE 0.02） |
| 收起两段式 | 220ms + 530ms | 贝塞尔(0.45,0.20,0.10,1.00) → 弹簧(ζ=0.65, ω=12)（+5% 过冲；拟合 RMSE 0.03/0.01） |
| 快充光爆 | 900ms 上升 + 1100ms 衰减（一次性） | 径向渐变包络，峰值 ~32% |

## 验证方式（无真机时）

- 逐帧数值验证：`analysis/video/frames_*.csv` 给出每帧岛体尺寸/内容框；`preview/curves_fit.png` 把
  录屏实测进度（红）与 App 使用的曲线（绿）画在同一坐标系里对比（充电出现/音乐展开 RMSE≈0.02，计时展开≈0.05）。
- 视觉验证：`preview/final_*.png` 上半部分是录屏原帧，下半部分是按 `IslandSpec` 同参数渲染的预览图。
- 编译验证：Gradle `assembleDebug` 成功；`aapt2 dump badging` 确认包名/权限/minSdk/targetSdk。
- 独立代码评审（qa-verifier）发现的 1 个 Critical / 8 个 High 问题已全部修复（预览模式泄漏、充电演示电量、
  计时卡片底部透明层、暂停状态、通知回放、启动崩溃保护、收起内容闪烁、旋转适配等）。

## 已知边界

- 未连接真机做最终手感调校；所有参数可按 `app/README.md` 的说明在 `IslandSpec.java` 一处微调。
- 计时器依赖系统时钟 App 的通知格式（已做通用识别 + 关键字/包名/chronometer 多路判断），个别 ROM 可能需要补充包名。
- 展开音乐卡片的五个按钮按录屏复原；其中上一首/暂停/下一首已接 MediaController 真实控制。
















































