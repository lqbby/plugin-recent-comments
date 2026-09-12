package com.lqbby.ethereal.friends;

/**
 * 「随机友链」端点的后台设置（Console → 插件 → Ethereal 配套 → 设置），
 * 对应 extensions/settings.yaml 的 friendsRandom 分组。
 *
 * <p>字段一律用包装类型：用户从未保存过设置时 ConfigMap 不存在，
 * 反序列化结果为 null，由端点侧回退到与 settings.yaml 一致的默认值。
 */
public class FriendsRandomConfig {

    private Integer defaultSize;

    private Integer maxSize;

    /** 候选项（全部友链的 name 列表）缓存秒数；洗牌每次请求都重做，故不影响随机性。 */
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
