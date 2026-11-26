package com.juying.medianest.domain.repository;

import com.juying.medianest.core.model.ConfigCodec;

/** 应用配置与最近播放条目的持久化端口。 */
public interface ConfigRepository {
    ConfigCodec.ConfigBundle load();
    void save(ConfigCodec.ConfigBundle bundle);
    void saveLastItem(String itemJson);
    String loadLastItem();
    void invalidateCapability();
    void clearAll();
}
