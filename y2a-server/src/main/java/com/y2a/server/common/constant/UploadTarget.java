package com.y2a.server.common.constant;

import java.util.Arrays;
import java.util.List;

/** 投稿平台枚举。both 保持 Python 版语义 = AcFun + bilibili。 */
public enum UploadTarget {

    ACFUN("acfun", "AcFun"),
    BILIBILI("bilibili", "bilibili"),
    DOUYIN("douyin", "抖音"),
    BOTH("both", "双平台");

    private final String value;
    private final String label;

    UploadTarget(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /** 一个 target 展开成实际要投稿的平台列表。 */
    public List<UploadTarget> platforms() {
        return switch (this) {
            case BOTH -> List.of(ACFUN, BILIBILI);
            default -> List.of(this);
        };
    }

    public static UploadTarget fromValue(String value) {
        if (value == null) {
            return ACFUN;
        }
        return Arrays.stream(values())
                .filter(t -> t.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(ACFUN);
    }
}
