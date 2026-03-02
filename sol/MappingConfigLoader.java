package com.insurance.excel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.insurance.excel.model.TemplateMappingConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads all *-mapping.yml files from the classpath mappings/ directory at startup
 * and makes them available by template ID.
 *
 * To add a new template mapping, simply drop a new YAML file in
 * src/main/resources/mappings/ — no code changes required.
 */
@Slf4j
@Component
public class MappingConfigLoader {

    @Value("${excel.mapping.location:classpath:mappings/*-mapping.yml}")
    private String mappingLocation;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, TemplateMappingConfig> configCache = new HashMap<>();

    @PostConstruct
    public void loadAll() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(mappingLocation);

        log.info("Loading {} template mapping file(s) from '{}'", resources.length, mappingLocation);

        for (Resource resource : resources) {
            try {
                TemplateMappingConfig config = yamlMapper.readValue(
                        resource.getInputStream(), TemplateMappingConfig.class);

                String id = config.getTemplate().getId();
                configCache.put(id, config);
                log.info("  ✓ Loaded mapping: id='{}' → file='{}'",
                        id, config.getTemplate().getFile());

            } catch (Exception e) {
                log.error("  ✗ Failed to load mapping from '{}': {}", resource.getFilename(), e.getMessage());
                throw new IllegalStateException("Invalid mapping config: " + resource.getFilename(), e);
            }
        }

        if (configCache.isEmpty()) {
            log.warn("No template mapping files found at '{}'", mappingLocation);
        }
    }

    /**
     * Retrieve a mapping config by template ID.
     * @throws IllegalArgumentException if no config found for the given ID
     */
    public TemplateMappingConfig getConfig(String templateId) {
        TemplateMappingConfig config = configCache.get(templateId);
        if (config == null) {
            throw new IllegalArgumentException(
                    "No mapping config found for templateId='" + templateId +
                    "'. Available IDs: " + configCache.keySet());
        }
        return config;
    }

    public Map<String, TemplateMappingConfig> getAllConfigs() {
        return Map.copyOf(configCache);
    }

    /**
     * Hot-reload a specific mapping file at runtime (useful in dev / config-refresh scenarios).
     */
    public void reload(String templateId) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(mappingLocation);

        for (Resource resource : resources) {
            TemplateMappingConfig config = yamlMapper.readValue(
                    resource.getInputStream(), TemplateMappingConfig.class);
            if (templateId.equals(config.getTemplate().getId())) {
                configCache.put(templateId, config);
                log.info("Reloaded mapping config for templateId='{}'", templateId);
                return;
            }
        }
        throw new IllegalArgumentException("No mapping file found for templateId='" + templateId + "'");
    }
}