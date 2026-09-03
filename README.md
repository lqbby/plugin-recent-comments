# recent-comments

全局「最新评论」聚合端点与可嵌入侧边栏组件。把侧边栏最新评论原本的 N+1 次请求合并为 **1 次**，并附带一个零依赖的 `<recent-comments>` Web Component——**任意主题**只需在模板里加两行即可拥有与站点评论区一致（头像、昵称）的最新评论列表。

- 数据源：Halo 核心 `Comment` 扩展（`approved && !hidden`，按创建时间倒序），与评论插件解耦——无论站点用的是哪个评论插件，写入的评论都能被聚合。
- 隐私：响应体只包含渲染所需字段，**不返回** IP、UA 与 owner 原始注解；邮箱仅以 `sha256` 哈希（64 位十六进制）形式暴露，用于头像寻址，与 Halo 官方评论接口的处理一致。
- 兼容：Halo `>=2.25.0`；浏览器端零依赖、零构建。

> 侧边栏实际效果见插件仓库首页图（应用市场上架时另附截图）。

## 安装

- 应用市场：Console → 插件 → 应用市场，搜索「最近评论 / recent-comments」安装。
- 手动安装：下载 `recent-comments-<version>.jar`，Console → 插件 → 安装，上传 JAR。

## 使用（主题接入）

任意主题的侧边栏 / 页脚模板中，两步接入：

```html
<script src="/plugins/recent-comments/assets/static/widget.js" defer></script>
<recent-comments size="5"></recent-comments>
```

`<recent-comments>` 支持以下属性：

| 属性 | 默认 | 说明 |
|---|---|---|
| `size` | `5` | 展示条数，范围 1–20 |
| `show-avatar` | 开 | 置为 `"false"` 隐藏头像 |
| `show-time` | 开 | 置为 `"false"` 隐藏相对时间 |
| `open-new-tab` | 关 | 置为 `"true"` 让评论在新标签页打开 |
| `empty-text` | 暂无评论 | 无评论时的占位文案 |
| `error-text` | — | 拉取失败时的占位文案（不设置则仅打印 console 警告） |

### 明暗模式

组件自动适配：优先读取宿主 `<html>` 上的 `data-color-scheme`（`color-scheme-dark` / `color-scheme-light` / `color-scheme-auto`，即 Halo 官方搜索 / 评论组件使用的同一套公共标记），否则跟随系统 `prefers-color-scheme`。也可通过 CSS 变量在宿主侧覆写配色：

```css
recent-comments {
  --rc-text: #222;
  --rc-muted: #6b7280;
  --rc-accent: #0f6e56;
}
```

### 直接调用 API（可选）

```
GET /apis/api.recent-comments.halo.run/v1alpha1/comments/latest?size=5
```

匿名可读，返回 `{ "size": 5, "total": 42, "items": [...] }`。单条结构：

| 字段 | 说明 |
|---|---|
| `name` | 评论唯一标识 |
| `displayName` | 评论者昵称 |
| `avatar` | 头像 URL（登录用户来自其 User 扩展；访客为 `null`） |
| `emailHash` | `sha256(小写邮箱)` 的 64 位十六进制，可拼第三方头像服务（如 `https://weavatar.com/avatar/<hash>?d=mp&f=webp&s=96`） |
| `content` | 评论正文（纯文本优先，必要时已剥离 HTML） |
| `creationTime` | ISO 8601 创建时间 |
| `subjectRef` | 评论对象引用（group / version / kind / name） |
| `subjectTitle` | 评论对象的标题（文章 / 单页等） |
| `permalink` | 评论对象链接 |

## 卸载

Console → 插件 → 停用并卸载即可。卸载后主题侧若保留 `<recent-comments>` 标签，组件会静默显示占位，不影响页面其余功能（建议一并移除引用脚本的 `<script>` 标签）。

## 常见问题

- **头像不显示？** 站点评论区本身就能显示头像即可用；若评论来自旧数据（无邮箱哈希且非登录用户），组件会回退为首字母色块。
- **为什么只返回 `emailHash` 而不是邮箱？** 出于隐私考虑，明文邮箱不对外暴露；哈希仅用于头像寻址，与 Halo 官方评论 API 行为一致。

## 开发者

- 构建：JDK 21 + `gradle clean assemble`，产物在 `build/libs/recent-comments-<version>.jar`。
- 工程未引入 `run.halo.plugin.devtools`，`plugin.yaml` 的 `spec.version` 由 Gradle `processResources` 注入；每新增 `@Component` 类须同步维护 `META-INF/plugin-components.idx`。

## 开源许可

[GPL-3.0](./LICENSE)。本插件源自作者博客主题 Ethereal 的配套内部插件（v0.x 历史见 [CHANGELOG](./CHANGELOG.md)），现已独立为通用插件公开发布。
