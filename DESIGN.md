# DESIGN.md — Photoria 设计契约

> 由 design-review 插件（design-system-capture）从现有代码证据提取生成。
> 真值源优先级：Theme.kt / Glass.kt 令牌 > 组件实现 > 页面散落值。
> 每次 UI 改动后请同步本文件；Design QA 以本文件为合同。

## 1. 品牌个性

后室（Backrooms）Level 0：荧光灯下的病态黄。UI 是"设备"而非"网页"——
深色玻璃仪器浮在实时取景之上，奶油黄文字模拟荧光照明，荧光黄只做强调。
约束：一切装饰不得干扰取景器可视性；辅助信息永不烘焙进成片。

## 2. 色彩角色（Theme.kt `Backrooms*` = 唯一批准色源）

| 语义 | Token | 值 | 用途 |
|---|---|---|---|
| 品牌强调 | BackroomsYellow | #D1BC55 | 快门内圈、选中描边、指示点 |
| 次级强调 | BackroomsMoss | #A8A36D | 次级文本/图标 |
| 正文/图标 | BackroomsCream | #F0E6B8 | 所有常规文字 |
| 面板底 | BackroomsWall | #2B2512 | 面板基色 |
| 遮罩/底 | BackroomsShadow | #100D06 | 全屏底、scrim |
| 强调上深字 | BackroomsYellowOnDark | #1C1705 | 荧光黄底上的文字 |
| 危险/录制 | Color.Red | 系统红 | 仅录制状态语义，不得挪用 |

玻璃分层（Glass.kt `PhotoriaGlass` = 唯一玻璃源）：
FillTop/FillBottom（渐变底）、SpecularTop（高光带）、Hairline/HairlineActive（描边）、
Glow（柔光斑）、NOISE_ALPHA=0.045（噪点）。
**禁止**在组件里再手写 `.copy(alpha=…)` 当玻璃用——引用 PhotoriaGlass。

## 3. 字阶（目标 6 档；现状审计有 9 档在漂，收敛时按此归位）

| Role | Size | Weight | 现状证据 |
|---|---|---|---|
| Display（倒计时） | 84sp | Bold | CountdownOverlay |
| Title（品牌/大标题） | 18sp | Bold | TopBar |
| Headline（卡标题） | 16sp | Medium | 权限卡 |
| Body（默认正文） | 13sp | Normal | 面板标签 |
| Label（紧凑标签） | 12sp | Normal/Medium | 滤镜名、chips |
| Micro（徽标/计数） | 10sp | Normal | 版本徽标 9→10 |
**删除**：11sp（已并入 12）、8sp。新代码不得引入表外字号。
**数字仪表一律等宽**：`theme/NumberFont`（FontFamily.Monospace）—— ISO/快门/EV/
灵敏度/缩放倍率/倒计时/版本徽标（V3a 起，注册于 Theme.kt）。

## 4. 圆角与间距

- 圆角只允许 3 档：`6`（小元素）/ `10–14`（卡片、面板）/ `50·Circle`（胶囊、按钮）。
  审计漂移值 8、22 → 归位到 10 / PanelShape 单一源。
- 间距走 4dp 网格：4/8/12/16/24/32；浮层偏移（64/96/112/160）属布局常量，
  收进 CameraScreen 顶部 `object Layout`，不散落字面量。

## 5. 运动与高度规范（PhotoriaGlass 弹簧 = 唯一缓动源）

| Spec | 参数 | 用途 |
|---|---|---|
| PressScale | d=0.55, k=640 | 按压缩放（0.86–0.94） |
| PanelSlide | d=0.82, k=380 | 抽屉/弹层滑入滑出 |
| SelectSpring / Dp | d=0.68, k=460 | 选中态吸附 |
| expand/shrink | d=0.9, k=420 | 滤镜栏折叠（V2）· 面板联动区 SectionExpand（V3c） |

高度三档（`PhotoriaGlass.Elevations`，V3b）：
Capsule 0（浮动小件）/ Card 16（权限卡、参数面板）/ Drawer 28（专业设置抽屉）。
抽屉打开必配压暗层：Black@0.32 scrim + 点按收起；被抬升层与沉下层必须成对出现。
规则：动画只驱动 graphicsLayer（scale/alpha/rotation/translation）；
禁重组期读 Animatable.value；tween 仅用于淡入淡出 ≤250ms。

## 6. 组件登记（新界面必须先查这里）

GlassSurface / GlassIconButton / GlassPill（Glass.kt）· CaptureButton（含 BusyRing）·
FilterSelector（圈 52dp+选中吸附）· FilterCategoryBar（40dp 触控高）·
CameraSettingsPanel（右侧玻璃抽屉，弹簧滑入）· FilterParamsPanel · AdjustPanel（调色：影调/色彩/色域三页滑杆，同族玻璃底）· ModeSegmentedControl ·
CountdownOverlay · BubbleLevel · HistogramBox · TopBar（渐变 scrim + 玻璃圆钮；W0 起无版本徽标）

调色滑杆读数一律 NumberFont（等宽），曝光显 EV、色相显角度；色域带选择点 40dp 触控、22dp 色点。
AdjustPanel 第四页曲线（CurveEditor）：150dp 高画布 + 四通道 chip（RGB/R/G/B，改动带色点角标），
拖拽期间只画临时态、松手才回写 —— 曲线是稀疏事件，不做每 move 落盘。

## 7. 状态与无障碍底线

- 触控目标 ≥40dp（V0b 后基线）；图标按钮 44dp。
- 文本对比：暗底上最低 cream@0.62（V0b 基线），禁 0.45 以下承载文字。
- 每个可点元素有 contentDescription；装饰图标传 null。
- 处理中必有反馈：快门 BusyRing、CaptureProgressOverlay、看门狗超时兜底。
- 权限拒绝态 = 玻璃卡两条出路（重授/系统设置），禁死屏。

## 8. 渲染证据要求（Design QA 门禁）

视觉判定必须有真机/模拟器截图对（同状态同内容）。当前设备离线时，
只能出 contract/debt 结论，视觉部分一律标 Inconclusive。
截图路径约定：`.design-qa/actual/<screen>-<version>.png`。
