# Ethereal 配套（ethereal-companion）

Ethereal 主题的配套插件，聚合两块能力：

1. **全局最新评论** —— 聚合端点 + 可嵌入 `<recent-comments>` Web Component，把侧边栏最新评论原本的 N+1 次请求合并为 **1 次**；任意主题两行接入，头像/昵称与站点评论区完全一致。
2. **文章系列（series）** —— 聚合 Finder（`recentCommentsSeriesFinder`），把文章 `series` / `seriesOrder` 注解分组排序后服务端渲染，供主题做「文章页系列导航」与「/series/ 系列列表页」。

- 数据源：Halo 核心 `Comment` / `Post` 扩展，与其它评论插件解耦——无论站点用哪个评论插件，写入的评论都能被聚合；系列则直接读文章的 `metadata.annotations`。
- 隐私：评论响应体只含渲染所需字段，**不返回** IP、UA 与 owner 原始注解；邮箱仅以 `sha256` 哈希（64 位十六进制）暴露，用于头像寻址，与 Halo 官方评论接口处理一致。
- 数据保存期限：插件自身**不存储任何数据**，实时读取 Halo 扩展即时返回；卸载即停止聚合，数据始终跟随站点评论/文章本身。
- 兼容：Halo `>=2.25.0`；浏览器端零依赖、零构建。

## 一、最新评论

### 后台设置

Console → 插件 → Ethereal 配套 → 设置（保存后立即生效，无需重启）：

| 设置项 | 范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| 默认返回条数 | 1-50 | 5 | 请求未携带 `size` 参数（或非法）时返回的条数 |
| 单次请求上限 | 1-100 | 20 | `size` 超过该值时按该值截断，防止大查询拖垮站点 |
| 结果缓存秒数 | 0-3600 | 60 | 聚合结果服务端缓存；0 为禁用（实时查询） |

### 主题接入

任意主题的侧边栏 / 页脚模板中，两步接入：

```html
<script src="/plugins/ethereal-companion/assets/static/widget.js" defer></script>
<recent-comments size="5"></recent-comments>
```

`<recent-comments>` 属性：

| 属性 | 默认 | 说明 |
|---|---|---|
| `size` | `5` | 展示条数，范围 1–20 |
| `show-avatar` | 开 | 置为 `"false"` 隐藏头像 |
| `show-time` | 开 | 置为 `"false"` 隐藏相对时间 |
| `open-new-tab` | 关 | 置为 `"true"` 让评论在新标签页打开 |
| `empty-text` | 暂无评论 | 无评论时的占位文案 |
| `error-text` | — | 拉取失败时的占位文案 |

明暗模式：组件自动适配宿主 `<html>` 的 `data-color-scheme` 标记，否则跟随 `prefers-color-scheme`；也可用 CSS 变量 `--rc-text / --rc-muted / --rc-accent / --rc-bg / --rc-hover` 覆写。

### 直接调用 API（可选）

```
GET /apis/api.recent-comments.halo.run/v1alpha1/comments/latest?size=5
```

匿名可读，返回 `{ "size": 5, "total": 42, "items": [...] }`。单条结构：`name` / `displayName` / `avatar` / `emailHash` / `content` / `creationTime` / `subjectRef` / `subjectTitle` / `permalink`。

## 二、文章系列

### 用法

1. 主题侧声明两个文章注解（`AnnotationSetting`，`targetRef` 为 `Post`）：
   - `series`：系列名称（文本）
   - `seriesOrder`：系列内序号（文本，建议填数字，如 `1`、`2`…）

2. 后台编辑文章时填写 `series` 与 `seriesOrder`（值存 `metadata.annotations`）。

3. 主题模板服务端调用 Finder：

   - 系列列表页（/series/）：
     ```html
     <div th:each="s : ${recentCommentsSeriesFinder.listAll()}">
       <h2 th:text="${s.name}">系列名</h2>
       <a th:each="p : ${s.posts}" th:href="@{${p.permalink}}" th:text="${p.title}">标题</a>
     </div>
     ```
   - 文章页系列导航（先读当前文章的 `series` 注解）：
     ```html
     <th:block th:with="seriesName=${#annotations.get(post, 'series')}">
       <th:block th:if="${not #strings.isEmpty(seriesName)}"
                 th:with="series=${recentCommentsSeriesFinder.get(seriesName)}">
         <a th:each="p : ${series.posts}" th:href="@{${p.permalink}}" th:text="${p.title}">标题</a>
       </th:block>
     </th:block>
     ```

### Finder 返回值

`recentCommentsSeriesFinder.listAll()` 返回 `Flux<SeriesView>`，`get(name)` 返回 `Mono<SeriesView>`。

`SeriesView`：`name`（系列名）、`posts`（`List<SeriesPostView>`，按 `seriesOrder` 升序，未填序号的排最后再按发布时间倒序）。

`SeriesPostView`：`name`（文章唯一标识）、`title`、`permalink`（来自 `status.permalink`，缺失回退 `/archives/{slug}`）、`order`（`seriesOrder` 解析结果）、`publishTime`（ISO 8601）。

## 安装

- 应用市场：Console → 插件 → 应用市场，搜索「Ethereal 配套 / ethereal-companion」安装。
- 手动安装：下载 `ethereal-companion-<version>.jar`，Console → 插件 → 安装，上传 JAR。

## 卸载

Console → 插件 → 停用并卸载即可。卸载后主题侧若保留 `<recent-comments>` 标签或系列 Finder 调用，组件会静默显示占位、Finder 调用报错，不影响页面其余功能（建议一并移除相关引用）。

## 常见问题

- **头像不显示？** 站点评论区本身能显示头像即可用；旧数据（无邮箱哈希且非登录用户）回退首字母色块。
- **为什么只返回 `emailHash` 而非邮箱？** 隐私考虑，明文邮箱不对外暴露；哈希仅用于头像寻址。
- **系列文章不出现？** 确认文章已发布、`series` 注解非空；`seriesOrder` 缺失不阻塞，仅排在系列末尾。

## 开发者

- 构建：JDK 21 + `gradle clean assemble`，产物在 `build/libs/ethereal-companion-<version>.jar`。
- 工程未引入 `run.halo.plugin.devtools`，`plugin.yaml` 的 `spec.version` 由 Gradle `processResources` 注入；每新增 `@Component`/`@Finder` 类须同步维护 `META-INF/plugin-components.idx`。

## 开源许可

[GPL-3.0](./LICENSE)。本插件为作者博客主题 Ethereal 的配套插件，由原 `recent-comments`（最近评论）扩展而来（历史见 [CHANGELOG](./CHANGELOG.md)）。
