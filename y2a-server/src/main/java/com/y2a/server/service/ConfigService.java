package com.y2a.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.config.AppConfigEntry;
import com.y2a.server.repository.ConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

/**
 * 配置服务 —— 208 项配置的读写与归一化。
 *
 * <p>归一化规则用「策略注册表」组织：每类有问题的配置项注册一个归一化函数，
 * 新增规则只需注册，不改主流程（策略模式 + 开闭原则）。Python 版是 208 项里
 * 散落的 if/elif，这里集中管理。</p>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, Function<Object, Object>> normalizers = new HashMap<>();

    public ConfigService(ConfigRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        registerNormalizers();
    }

    private void registerNormalizers() {
        normalizers.put("UPLOAD_TARGET_DEFAULT", value -> {
            UploadTarget target = UploadTarget.fromValue(String.valueOf(value));
            return target.value();
        });
        // 后续阶段把 Python 中 _normalize_submit_api / quality_mode / reasoning_effort
        // 等规则逐步注册到这里。
    }

    public Map<String, Object> getAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AppConfigEntry e : repository.findAll()) {
            result.put(e.getConfigKey(), e.getConfigValue());
        }
        return result;
    }

    public Map<String, Object> getByCategory(String category) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AppConfigEntry e : repository.findByCategory(category)) {
            result.put(e.getConfigKey(), e.getConfigValue());
        }
        return result;
    }

    public Object get(String key, Object defaultValue) {
        AppConfigEntry e = repository.selectById(key);
        return e == null ? defaultValue : e.getConfigValue();
    }

    public String getString(String key, String defaultValue) {
        Object v = get(key, defaultValue);
        return v == null ? defaultValue : String.valueOf(v);
    }

    @Transactional
    public int updateAll(Map<String, Object> changes) {
        int count = 0;
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            Object normalized = normalize(entry.getKey(), entry.getValue());
            try {
                count += repository.updateValue(entry.getKey(),
                        objectMapper.writeValueAsString(normalized));
            } catch (Exception e) {
                throw BizException.badRequest("配置序列化失败: " + entry.getKey());
            }
        }
        return count;
    }

    private Object normalize(String key, Object value) {
        Function<Object, Object> fn = normalizers.get(key);
        if (fn == null) {
            return value;
        }
        try {
            return fn.apply(value);
        } catch (Exception e) {
            log.warn("配置 {} 归一化失败，使用原值: {}", key, value, e);
            return value;
        }
    }
}
