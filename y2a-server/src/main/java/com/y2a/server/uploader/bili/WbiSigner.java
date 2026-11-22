package com.y2a.server.uploader.bili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * bilibili WBI 签名（对齐 Python bili_sdk/utils/network.py 的 _enc_wbi）。
 *
 * <p>WBI 签名算法：
 * <ol>
 *   <li>从 /x/web-interface/nav 取 img_url/sub_url，拼接后取文件名</li>
 *   <li>按固定 MIXIN_KEY_ENC_TAB 重排得到 mixin_key</li>
 *   <li>参数按 key 排序 + URL 编码 + 追加 wts，再拼 mixin_key 做 MD5 得 w_rid</li>
 * </ol>
 * </p>
 */
public final class WbiSigner {

    /** 固定重排表（社区公开常量，bilibili 前端与多个开源项目一致）。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WbiSigner() {
    }

    /** 从 nav 接口响应中提取 mixin key。 */
    public static String mixinKeyFromNav(JsonNode navJson) {
        try {
            JsonNode wbi = navJson.path("data").path("wbi_img");
            String imgUrl = wbi.path("img_url").asText("");
            String subUrl = wbi.path("sub_url").asText("");
            String imgKey = fileStem(imgUrl);
            String subKey = fileStem(subUrl);
            String raw = imgKey + subKey;
            StringBuilder key = new StringBuilder();
            for (int idx : MIXIN_KEY_ENC_TAB) {
                if (idx < raw.length()) {
                    key.append(raw.charAt(idx));
                }
                if (key.length() >= 32) break;
            }
            return key.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 对参数做 WBI 签名，返回已带 wts/w_rid 的新 Map。 */
    public static Map<String, String> sign(Map<String, String> params, String mixinKey) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("w_rid");
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        String query = sorted.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));

        sorted.put("w_rid", md5(query + mixinKey));
        return sorted;
    }

    private static String fileStem(String url) {
        if (url == null || url.isEmpty()) return "";
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /** 解析 JSON（便捷方法）。 */
    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
