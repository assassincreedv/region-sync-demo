package com.example.regionsync.mapper;

import com.example.regionsync.model.base.SyncableEntity;

import java.util.Map;

public interface EntityMapper<T extends SyncableEntity> {
    String getTableName();
    Class<T> getEntityClass();
    T fromPayload(Map<String, Object> payload);
    void updateEntity(T existing, Map<String, Object> payload);
    String extractBusinessKey(Map<String, Object> payload);
}
