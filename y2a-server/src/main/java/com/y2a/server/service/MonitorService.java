package com.y2a.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.monitor.MonitorConfig;
import com.y2a.server.domain.monitor.MonitorHistory;
import com.y2a.server.repository.MonitorConfigRepository;
import com.y2a.server.repository.MonitorHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YouTube 监控服务。
 *
 * <p>运行进度用内存表持有（对应 Python 的 _MONITOR_RUN_OPERATIONS），
 * 实际抓取由引擎/外部 API 完成，本服务负责配置 CRUD、历史与状态编排
 * （后续接入 YouTube Data API 时只需填充 {@link #executeRun}）。</p>
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    private static final long OP_TTL_MS = 600_000L;

    private final MonitorConfigRepository configRepo;
    private final MonitorHistoryRepository historyRepo;

    /** operationId → 运行进度。 */
    private final Map<String, Map<String, Object>> runOperations = new ConcurrentHashMap<>();

    public MonitorService(MonitorConfigRepository configRepo,
                          MonitorHistoryRepository historyRepo) {
        this.configRepo = configRepo;
        this.historyRepo = historyRepo;
    }

    // ---------------- 配置 CRUD ----------------

    public List<MonitorConfig> listConfigs() {
        return configRepo.findAllOrdered();
    }

    public List<MonitorHistory> listHistory(int limit) {
        return historyRepo.findRecent(limit);
    }

    public List<MonitorHistory> historyByConfig(Long configId, int limit) {
        return historyRepo.findByConfig(configId, limit);
    }

    public Map<String, Object> statsByConfig(Long configId) {
        Map<String, Object> raw = historyRepo.statsByConfig(configId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_records", asLong(raw, "total_records"));
        stats.put("added_to_tasks", asLong(raw, "added_to_tasks"));
        stats.put("avg_views", Math.round(asDouble(raw, "avg_views")));
        stats.put("avg_likes", Math.round(asDouble(raw, "avg_likes")));
        return stats;
    }

    public MonitorConfig getConfig(Long id) {
        MonitorConfig c = configRepo.selectById(id);
        if (c == null) {
            throw BizException.notFound("监控配置不存在: " + id);
        }
        return c;
    }

    @Transactional
    public MonitorConfig createConfig(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        if (name.isBlank()) {
            throw BizException.badRequest("名称不能为空");
        }
        MonitorConfig c = new MonitorConfig();
        applyPayload(c, payload);
        c.setCreatedTime(Instant.now());
        c.setUpdatedTime(Instant.now());
        configRepo.insert(c);
        return c;
    }

    @Transactional
    public MonitorConfig updateConfig(Long id, Map<String, Object> payload) {
        MonitorConfig c = getConfig(id);
        applyPayload(c, payload);
        c.setUpdatedTime(Instant.now());
        configRepo.updateById(c);
        return c;
    }

    @Transactional
    public void deleteConfig(Long id) {
        getConfig(id);
        configRepo.deleteById(id);
    }

    // ---------------- 运行 ----------------

    public Map<String, Object> startRun(Long configId) {
        MonitorConfig c = getConfig(configId);
        String opId = UUID.randomUUID().toString();
        Map<String, Object> op = new ConcurrentHashMap<>();
        op.put("config_id", configId);
        op.put("message", "准备运行监控「" + c.getName() + "」");
        op.put("detail", "");
        op.put("done", false);
        op.put("success", false);
        op.put("level", "info");
        op.put("created_at", System.currentTimeMillis());
        runOperations.put(opId, op);

        Thread worker = new Thread(() -> executeRun(opId, c), "monitor-run-" + configId);
        worker.setDaemon(true);
        worker.start();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation_id", opId);
        result.put("config_id", configId);
        result.put("message", op.get("message"));
        return result;
    }

    /** 实际抓取逻辑。接入 YouTube Data API 后在此实现。 */
    private void executeRun(String opId, MonitorConfig c) {
        Map<String, Object> op = runOperations.get(opId);
        if (op == null) breakRun(opId);
        try {
            op.put("message", "正在抓取…");
            op.put("level", "info");
            Thread.sleep(1200);
            configRepo.touchLastRun(c.getId(), Instant.now());
            op.put("message", "监控运行完成");
            op.put("detail", "未配置 YouTube API 密钥时为模拟运行");
            op.put("done", true);
            op.put("success", true);
            op.put("level", "success");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            op.put("done", true);
            op.put("success", false);
            op.put("level", "error");
            op.put("message", "运行被中断");
        } catch (Exception e) {
            log.error("监控运行失败 configId={}", c.getId(), e);
            op.put("done", true);
            op.put("success", false);
            op.put("level", "error");
            op.put("message", "运行失败: " + e.getMessage());
        }
    }

    public Map<String, Object> runStatus(String opId) {
        Map<String, Object> op = runOperations.get(opId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (op == null) {
            result.put("found", false);
            result.put("done", false);
            return result;
        }
        long created = ((Number) op.get("created_at")).longValue();
        if (System.currentTimeMillis() - created > OP_TTL_MS) {
            runOperations.remove(opId);
            result.put("found", false);
            result.put("done", false);
            return result;
        }
        result.put("found", true);
        result.putAll(op);
        return result;
    }

    // ---------------- 历史操作 ----------------

    @Transactional
    public Map<String, Object> addVideoToTasks(String videoId, Long configId) {
        if (videoId == null || videoId.isBlank() || configId == null) {
            throw BizException.badRequest("video_id 与 config_id 不能为空");
        }
        getConfig(configId);
        MonitorHistory existing = historyRepo.selectOne(new QueryWrapper<MonitorHistory>()
                .eq("config_id", configId).eq("video_id", videoId).last("LIMIT 1"));
        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getAddedToTasks())) {
                return Map.of("success", false, "message", "该视频已添加到任务队列");
            }
            existing.setAddedToTasks(true);
            existing.setTaskClaimedAt((double) System.currentTimeMillis());
            historyRepo.updateById(existing);
            return Map.of("success", true, "message", "已添加到任务队列");
        }
        // 历史里没有则补一条（保持与 Python 的 claim 语义一致）
        MonitorHistory h = MonitorHistory.builder()
                .configId(configId).videoId(videoId).videoTitle("")
                .addedToTasks(true).taskClaimedAt((double) System.currentTimeMillis())
                .runTime(Instant.now()).build();
        historyRepo.insert(h);
        return Map.of("success", true, "message", "已添加到任务队列");
    }

    @Transactional
    public int clearHistory(Long configId) {
        return historyRepo.deleteByConfig(configId);
    }

    @Transactional
    public int clearAllHistory() {
        return historyRepo.deleteAll();
    }

    /** 从配置文件恢复监控配置（Python 版从 config/youtube_monitor 读取，此处为占位）。 */
    public Map<String, Object> restoreConfigs() {
        return Map.of("success", true, "message", "当前版本没有可恢复的配置文件", "restored", 0);
    }

    @Transactional
    public void resetOffset(Long configId) {
        MonitorConfig c = getConfig(configId);
        c.setHistoricalOffset(0);
        c.setHistoricalProgressDate("");
        c.setUpdatedTime(Instant.now());
        configRepo.updateById(c);
    }

    // ---------------- 内部工具 ----------------

    private void breakRun(String opId) {
        runOperations.remove(opId);
    }

    /** 把前端 camelCase/snake_case 混合 payload 映射到实体。 */
    private void applyPayload(MonitorConfig c, Map<String, Object> p) {
        if (p.containsKey("name")) c.setName(str(p.get("name")));
        c.setEnabled(boolOr(p.get("enabled"), c.getEnabled(), true));
        if (p.containsKey("monitor_type")) c.setMonitorType(str(p.get("monitor_type")));
        else if (p.containsKey("monitorType")) c.setMonitorType(str(p.get("monitorType")));
        if (p.containsKey("channel_mode")) c.setChannelMode(str(p.get("channel_mode")));
        else if (p.containsKey("channelMode")) c.setChannelMode(str(p.get("channelMode")));
        c.setRegionCode(strOr(p.get("region_code"), c.getRegionCode(), "US"));
        c.setCategoryId(strOr(p.get("category_id"), c.getCategoryId(), "0"));
        c.setTimePeriod(intOr(p.get("time_period"), c.getTimePeriod(), 7));
        c.setMaxResults(intOr(p.get("max_results"), c.getMaxResults(), 10));
        c.setMinViewCount(intOr(p.get("min_view_count"), c.getMinViewCount(), 0));
        c.setMinLikeCount(intOr(p.get("min_like_count"), c.getMinLikeCount(), 0));
        c.setMinCommentCount(intOr(p.get("min_comment_count"), c.getMinCommentCount(), 0));
        c.setKeywords(strOr(p.get("keywords"), c.getKeywords(), ""));
        c.setExcludeKeywords(strOr(p.get("exclude_keywords"), c.getExcludeKeywords(), ""));
        c.setTitleKeywords(strOr(p.get("title_keywords"), c.getTitleKeywords(), ""));
        c.setTitleMatchMode(normalizeTitleMode(strOr(p.get("title_match_mode"), c.getTitleMatchMode(), "any")));
        c.setChannelIds(strOr(p.get("channel_ids"), c.getChannelIds(), ""));
        c.setChannelKeywords(strOr(p.get("channel_keywords"), c.getChannelKeywords(), ""));
        c.setExcludeChannelIds(strOr(p.get("exclude_channel_ids"), c.getExcludeChannelIds(), ""));
        c.setMinDuration(intOr(p.get("min_duration"), c.getMinDuration(), 0));
        c.setMaxDuration(intOr(p.get("max_duration"), c.getMaxDuration(), 0));
        c.setScheduleType(strOr(p.get("schedule_type"), c.getScheduleType(), "manual"));
        c.setScheduleInterval(intOr(p.get("schedule_interval"), c.getScheduleInterval(), 120));
        c.setOrderBy(strOr(p.get("order_by"), c.getOrderBy(), "viewCount"));
        c.setStartDate(strOr(p.get("start_date"), c.getStartDate(), ""));
        c.setEndDate(strOr(p.get("end_date"), c.getEndDate(), ""));
        c.setLatestDays(intOr(p.get("latest_days"), c.getLatestDays(), 7));
        c.setLatestMaxResults(intOr(p.get("latest_max_results"), c.getLatestMaxResults(), 20));
        c.setRateLimitRequests(intOr(p.get("rate_limit_requests"), c.getRateLimitRequests(), 20));
        c.setRateLimitWindow(intOr(p.get("rate_limit_window"), c.getRateLimitWindow(), 60));
        c.setAutoAddToTasks(boolOr(p.get("auto_add_to_tasks"), c.getAutoAddToTasks(), false));
        c.setVideoTypes(strOr(p.get("video_types"), c.getVideoTypes(), "video,short,live"));
    }

    private static String normalizeTitleMode(String v) {
        return "all".equalsIgnoreCase(v) ? "all" : "any";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String strOr(Object o, String current, String def) {
        if (o == null) return current == null ? def : current;
        String s = String.valueOf(o).trim();
        return s;
    }

    private static Integer intOr(Object o, Integer current, int def) {
        if (o == null) return current == null ? def : current;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return current == null ? def : current;
        }
    }

    private static Boolean boolOr(Object o, Boolean current, boolean def) {
        if (o == null) return current == null ? def : current;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return List.of("true", "1", "on", "yes").contains(s);
    }

    private static long asLong(Map<String, Object> raw, String key) {
        Object v = raw == null ? null : raw.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Map<String, Object> raw, String key) {
        Object v = raw == null ? null : raw.get(key);
        return v instanceof Number n ? n.doubleValue() : 0d;
    }
}
