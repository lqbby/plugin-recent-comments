# CHANGELOG

本插件最早是作者博客主题 Ethereal（halo-dev 生态）的配套内部插件，侧边栏「最新评论」由主题组件直接渲染（v0.x 私有历史，未公开发布）。自 1.0.0 起独立为通用插件：中性命名、通用 API group，并内置可嵌入的 Web Component，供任意主题使用。自 1.1.0 起重命名回「Ethereal 配套」并新增文章系列能力。

## 1.2.1（2026-09-13）

版本号推进（`1.2.0` 已发布使用）。**本版代码与 1.2.0 完全一致**，仅同步文档。

- README 新增「三、随机友链」章节：端点用法、`?_=<时间戳>` 绕 CDN 缓存的必要性、PluginLinks 依赖与降级行为、后台设置项、主题接入示例。
- CHANGELOG 补记 1.2.0 的变更内容（此前仅有 1.1.0 条目）。

## 1.2.0（2026-09-13）

新增「随机友链」端点，供主题按需拉取友链、避免全量下发。

- **新增端点** `GET /apis/api.recent-comments.halo.run/v1alpha1/friends/random?size=6`：服务端从全部友链中随机挑 N 条返回，匿名可读。
  - 数据源为 **PluginLinks** 的 `Link` 扩展；未安装时返回空集合，不影响其余功能。
  - 候选名单（全部友链的 name）可缓存（默认 60 秒）以减轻匿名请求压力，但**洗牌每次请求都重做**，故缓存不影响随机性。
  - 响应仅含 `name` / `displayName` / `url` / `logo` / `description`，不外泄分组、优先级、RSS 与校验状态。
  - 新增匿名角色规则（`extensions/role-templates.yaml`）与后台设置组「随机友链」。
- **背景**：主题「关于我 → 我的朋友」原先由 Thymeleaf 把**全部**友链卡片写进 HTML、前端再洗牌截前 N。友链上百条时页面体积随数量线性膨胀（每卡约 650B，且浏览器要解析再删掉 99% 的节点）。改为服务端随机 + 按需下发后，**页面体积与友链总数无关**。
- **实现注意**：Link 属于其他插件，Halo 插件间 classloader 隔离，本插件不引用其 Java 类；改为经 `SchemeManager` 取到 Class（仅作参数传递）→ `listAllNames` 取名单 → 逐个 `fetch(GVK, name)` 拿 `Unstructured`。

## 1.1.0（2026-09-08）

插件重命名并新增「文章系列」聚合能力。

- **重命名**：插件标识由 `recent-comments` 改为 `ethereal-companion`（`metadata.name` / displayName「Ethereal 配套」/ 仓库 `lqbby/ethereal-companion`），JAR 产物同步更名；静态资源前缀随之变为 `/plugins/ethereal-companion/`。评论 API group `api.recent-comments.halo.run` **保持不变**（端点路径仍是 `comments/latest`）。
- **新增文章系列 Finder**：`@Finder("recentCommentsSeriesFinder")`（`com.lqbby.ethereal.series` 包），一次 `client.listAll(Post.class)` 取回全部文章 → 内存过滤（已发布且未删除且公开可见）→ 按 `metadata.annotations["series"]` 分组 → 组内按 `seriesOrder` 升序（未填排最后，再按发布时间倒序）→ 系列按名称升序。主题经 `recentCommentsSeriesFinder.listAll()` / `.get(name)` 服务端渲染，无需前端 fetch。
  - `SeriesView{ name, posts }`、`SeriesPostView{ name, title, permalink, order, publishTime }`。
- **Java 包重构**：`BasePlugin` 入口类上移根包 `com.lqbby.ethereal.EtherealCompanionPlugin`（组件扫描根），其下 `comments`（评论端点）与 `series`（系列 Finder）两个子包。
- **注释/文档**：修正 widget.js 头部路径（补 `/static/`）、README/CHANGELOG 全面更新。
- 破坏性：插件标识变更意味着 Halo 视为**新插件**，需卸载旧 `recent-comments` 后安装 `ethereal-companion`；评论设置（defaultSize/maxSize/cacheSeconds）因 configMapName 变更需重新保存（默认值与旧版一致）。

## 1.0.2（2026-09-03）

新增插件后台可自定义设置（Console → 插件 → 最近评论 → 设置）。

- **默认返回条数 `defaultSize`**（1-50，默认 5）：请求未携带 size 参数（或非法）时的返回条数。
- **单次请求上限 `maxSize`**（1-100，默认 20）：size 超限按该值截断，防止大查询拖垮站点。
- **结果缓存秒数 `cacheSeconds`**（0-3600，默认 0 即禁用）：聚合结果服务端缓存，按「单次请求上限」存满量、按各请求 size 切片返回；流量大的站点建议 30-300 秒。
- 实现：新增 `extensions/settings.yaml`（Setting 表单）+ `plugin.yaml` 关联 `settingName`/`configMapName` + 端点注入 `ReactiveSettingFetcher`（自带配置缓存与变更自动刷新）；用户从未保存过设置时回退内置默认值，行为与 1.0.1 完全一致。
- `RecentCommentsConfig` 为纯 POJO（项目未引 lombok），字段均包装类型以区分「未配置」与「配置为 0」。

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
