package com.juying.medianest.domain.repository;

import com.juying.medianest.core.model.ServerConfig;

import java.util.List;

/** 已登录服务器账号的持久化端口。 */
public interface AccountRepository {
    List<ServerConfig> load();
    void save(List<ServerConfig> list);
    boolean same(ServerConfig a, ServerConfig b);
    boolean contains(List<ServerConfig> list, ServerConfig target);
    void addOrReplace(List<ServerConfig> list, ServerConfig cfg);
}
