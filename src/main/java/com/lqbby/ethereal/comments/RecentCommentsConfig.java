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
}
