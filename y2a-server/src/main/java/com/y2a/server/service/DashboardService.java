package com.y2a.server.service;

import com.y2a.server.domain.task.Task;
import com.y2a.server.repository.DashboardRepository;
import com.y2a.server.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页仪表盘数据（对应 Python index 路由的 stats + recent_tasks）。 */
@Service
public class DashboardService {

    private final DashboardRepository dashboardRepo;
    private final TaskRepository taskRepo;

    public DashboardService(DashboardRepository dashboardRepo, TaskRepository taskRepo) {
        this.dashboardRepo = dashboardRepo;
        this.taskRepo = taskRepo;
    }

    public Map<String, Object> summary() {
        ZoneId zone = ZoneId.systemDefault();
        Instant dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant dayEnd = dayStart.plusSeconds(86400);

        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            stats.put("total_tasks", dashboardRepo.totalTasks());
            stats.put("awaiting_review", dashboardRepo.countByStatus("awaiting_manual_review"));
            stats.put("failed_total", dashboardRepo.countByStatus("failed"));
            stats.put("pending_total", dashboardRepo.countByStatus("pending"));
            stats.put("ready_total", dashboardRepo.countByStatus("ready_for_upload"));
            stats.put("in_progress", dashboardRepo.inProgress());
            stats.put("completed_today", dashboardRepo.countByStatusToday("completed", dayStart, dayEnd));
            stats.put("failed_today", dashboardRepo.countByStatusToday("failed", dayStart, dayEnd));
            stats.put("created_today", dashboardRepo.createdToday(dayStart, dayEnd));
        } catch (Exception e) {
            stats.put("total_tasks", 0L);
            stats.put("awaiting_review", 0L);
            stats.put("failed_total", 0L);
            stats.put("pending_total", 0L);
            stats.put("ready_total", 0L);
            stats.put("in_progress", 0L);
            stats.put("completed_today", 0L);
            stats.put("failed_today", 0L);
            stats.put("created_today", 0L);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stats", stats);
        data.put("recent_tasks", recentTasks());
        return data;
    }

    /** 最近 10 条 + upload_id 解析（复刻 Python 的 both/bilibili/douyin/acfun 分支）。 */
    private List<Map<String, Object>> recentTasks() {
        List<Task> tasks = taskRepo.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                        .orderByDesc("updated_at").last("LIMIT 10"));
        return tasks.stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("title", t.displayTitle() != null ? t.displayTitle() : "未获取标题");
            row.put("status", t.getStatus());
            row.put("updated_at", t.getUpdatedAt());
            row.put("upload_target", t.getUploadTarget());
            row.put("upload_id", resolveUploadId(t));
            row.put("status_color", statusColor(t.getStatus()));
            return row;
        }).toList();
    }

    private String resolveUploadId(Task t) {
        String target = t.getUploadTarget() == null ? "acfun" : t.getUploadTarget().toLowerCase();
        try {
            return switch (target) {
                case "bilibili" -> firstNonBlank(t.getBilibiliUploadResponse(), "bvid", "aid");
                case "douyin" -> firstNonBlank(t.getDouyinUploadResponse(), "item_id");
                case "both" -> {
                    String bv = firstNonBlank(t.getBilibiliUploadResponse(), "bvid");
                    String ac = firstNonBlank(t.getAcfunUploadResponse(), "ac_number");
                    if (bv != null && ac != null) yield bv + " / AC" + ac;
                    yield bv != null ? bv : (ac != null ? "AC" + ac : null);
                }
                default -> firstNonBlank(t.getAcfunUploadResponse(), "ac_number");
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    private static String statusColor(String status) {
        return switch (status == null ? "" : status) {
            case "completed" -> "success";
            case "failed" -> "danger";
            case "awaiting_manual_review" -> "warning";
            case "uploading", "downloading", "ready_for_upload" -> "primary";
            case "pending" -> "secondary";
            default -> "info";
        };
    }
}
