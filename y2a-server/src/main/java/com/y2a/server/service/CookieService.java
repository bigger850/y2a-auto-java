package com.y2a.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cookie/登录态文件服务：上传、路径解析、状态检查。
 * 对应 Python 的 _persist_settings_uploads + system_health cookie 检查。
 */
@Service
public class CookieService {

    private final ConfigService configService;
    private final ObjectMapper mapper;

    public CookieService(ConfigService configService, ObjectMapper mapper) {
        this.configService = configService;
        this.mapper = mapper;
    }

    /** 字段 → (保存文件名, 配置键, 默认相对路径, 显示名)。 */
    private static final Map<String, String[]> SPECS = Map.of(
            "youtube_cookies_file", new String[]{"yt_cookies.txt", "YOUTUBE_COOKIES_PATH", "cookies/yt_cookies.txt", "YouTube"},
            "acfun_cookies_file", new String[]{"ac_cookies.json", "ACFUN_COOKIES_PATH", "cookies/ac_cookies.json", "AcFun"},
            "bilibili_cookies_file", new String[]{"bili_cookies.json", "BILIBILI_COOKIES_PATH", "cookies/bili_cookies.json", "Bilibili"},
            "douyin_cookies_file", new String[]{"douyin_cookies.json", "DOUYIN_COOKIES_PATH", "cookies/douyin_cookies.json", "抖音"}
    );

    /** 保存上传的 Cookie 文件，并更新配置路径。 */
    public Map<String, Object> saveUpload(String field, MultipartFile file, Path cookiesDir) {
        String[] spec = SPECS.get(field);
        if (spec == null || file == null || file.isEmpty()) {
            throw BizException.badRequest("无效的 Cookie 上传字段或空文件");
        }
        try {
            Files.createDirectories(cookiesDir);
            Path target = cookiesDir.resolve(spec[0]);
            file.transferTo(target);
            configService.updateAll(Map.of(spec[1], spec[2]));
            return Map.of("saved", target.toString(), "configKey", spec[1], "path", spec[2]);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存 Cookie 文件失败: " + e.getMessage());
        }
    }

    /** 检查所有平台 Cookie 状态（供 /api/system/health 与设置页卡片用）。 */
    public Map<String, Map<String, Object>> checkAll(Path appRoot) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : SPECS.entrySet()) {
            String[] spec = e.getValue();
            String configured = configService.getString(spec[1], spec[2]);
            Path path = resolve(appRoot, configured);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("path", path.toString());
            if (!Files.isRegularFile(path)) {
                status.put("status", "warning");
                status.put("message", "未配置" + spec[3] + "登录态");
            } else {
                status.put("status", "ok");
                status.put("readable", Files.isReadable(path));
                status.put("size", path.toFile().length());
            }
            result.put(spec[3], status);
        }
        return result;
    }

    private Path resolve(Path root, String configured) {
        Path p = Paths.get(configured);
        return p.isAbsolute() ? p : root.resolve(p).normalize();
    }
}
