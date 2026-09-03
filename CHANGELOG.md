# CHANGELOG

本插件最早是作者博客主题 Ethereal（halo-dev 生态）的配套内部插件，侧边栏「最新评论」由主题组件直接渲染（v0.x 私有历史，未公开发布）。自 1.0.0 起独立为通用插件：中性命名、通用 API group，并内置可嵌入的 Web Component，供任意主题使用。

## 1.0.1（2026-09-03）

修复 `static/widget.js` 无法通过 `/plugins/recent-comments/assets/**` 访问的问题（安装后 404）。

- **根因**：Halo 不会自动暴露插件 `static/` 目录下的静态资源，须在 `extensions/` 下声明 `ReverseProxy` 自定义模型（与官方 plugin-comment-widget 同款机制）。
- **修复**：新增 `extensions/reverseProxy.yaml`（`path: /static/**` → `directory: static`）。widget 资源实际地址为 `/plugins/recent-comments/assets/static/widget.js`（注意 URL 中带 `/static/` 层级）。
- 插件 logo 此前一直可用，是因为 Halo 对 `plugin.yaml` 的 `spec.logo` 字段有内置读取（jar 根目录），不代表 `static/` 被自动暴露。
- README 接入示例的 `<script src>` 同步更新为上述完整路径。

## 1.0.0（2026-09-03）

首个公开发布版本。

- **改名**：插件标识由 `ethereal-comments-latest` 改为 `recent-comments`（`metadata.name`），JAR 产物同步更名。
- **API group 通用化**：`api.ethereal.lqbby.com` → `api.recent-comments.halo.run`（端点路径不变，仍是 `comments/latest`）。
- **新增前端组件**：内置 `static/widget.js`，注册 `<recent-comments>` Web Component（shadow DOM 自带样式，自动适配宿主明暗模式），任意主题两行接入，无需任何主题代码配合。
- **展示中性化**：displayName / description 不再绑定 Ethereal 主题。
- 功能基线（自 0.x 沿袭）：
  - 后端一次遍历 `Comment` 扩展，按创建时间倒序截取 `approved && !hidden` 的评论，把侧边栏 N+1 次请求压成 1 次；
  - 响应体剔除 IP / UA / owner 原始注解，仅暴露渲染所需字段；
  - 头像与评论区一致：登录用户取 User 扩展 `spec.avatar`；访客现算 `sha256(小写邮箱)` 供头像服务寻址（与 Halo 查询层对 owner 的动态加工逐字一致）；
  - 匿名可读（role-template `aggregate-to-anonymous`，`resources` 含裸主资源 `comments`）。
