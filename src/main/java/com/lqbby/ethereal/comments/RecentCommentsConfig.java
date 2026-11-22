package com.lqbby.ethereal.comments;

/**
 * 插件后台设置（Console → 插件 → 最近评论 → 设置），
 * 对应 extensions/settings.yaml 的 recentComments 分组。
 *
 * <p>所有字段用包装类型：ConfigMap 尚未保存过（用户从未进过设置页）时
 * 反序列化结果为 null，由端点侧取默认值，与 settings.yaml 的 value 保持一致。
 */
public class RecentCommentsConfig {

    private Integer defaultSize;

    private Integer maxSize;

    private Integer cacheSeconds;

    /** 是否把自定义页面（SinglePage/Page）的评论也算进「最近评论」，默认 false（排除）。 */
    private Boolean includePageComments;

    /** 排除的用户名（逗号分隔），如 AI 自动回复账号；这些账号的评论/回复不进最近评论。 */
    private String excludeOwners;

    public Integer getDefaultSize() {
        return defaultSize;
    }

    public void setDefaultSize(Integer defaultSize) {
        this.defaultSize = defaultSize;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize = maxSize;
    }

    public Integer getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(Integer cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public Boolean getIncludePageComments() {
        return includePageComments;
    }

    public void setIncludePageComments(Boolean includePageComments) {
        this.includePageComments = includePageComments;
    }

    public String getExcludeOwners() {
        return excludeOwners;
    }

    public void setExcludeOwners(String excludeOwners) {
        this.excludeOwners = excludeOwners;
    }
}
