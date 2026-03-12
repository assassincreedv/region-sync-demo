package com.example.regionsync.mapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class EntityMapperRegistry {

    private final Map<String, EntityMapper<?>> mappersByTable;

    public EntityMapperRegistry(List<EntityMapper<?>> mappers) {
        mappersByTable = new HashMap<>();
        for (EntityMapper<?> mapper : mappers) {
            mappersByTable.put(mapper.getTableName(), mapper);
            log.info("Registered EntityMapper for table: {}", mapper.getTableName());
        }
    }

    public Optional<EntityMapper<?>> findMapper(String tableName) {
        return Optional.ofNullable(mappersByTable.get(tableName));
    }
}
