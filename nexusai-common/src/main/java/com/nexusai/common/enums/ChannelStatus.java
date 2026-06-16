package com.nexusai.common.enums;

/**
 * 渠道状态（nai_channel）。约定 1=积极/启用，0=消极/停用。
 *
 * @author nexusai
 */
public enum ChannelStatus {
    /** 停用（0·不参与路由选渠道） */
    DISABLED(0),
    /** 启用（1·正常参与路由） */
    ENABLED(1),
    /** 归档（2·保留配置但不参与路由） */
    ARCHIVED(2);

    private final int code;

    ChannelStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ChannelStatus of(int code) {
        for (ChannelStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return DISABLED;
    }
}
