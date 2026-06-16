package com.nexusai.router;

import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;

/**
 * 两级调度结果：选中的渠道 + 渠道内 Key + 上游真实模型名（已过 model_mapping）+ 已占用的并发槽令牌
 * （relay 在请求结束/失败时凭此释放槽）。
 *
 * @param channel     选中渠道
 * @param key         选中 Key
 * @param targetModel 上游真实模型
 * @param slotToken   并发槽令牌（不限并发时为占位空串，relay 释放时空操作）
 * @author nexusai
 */
public record RouteResult(NaiChannel channel, NaiChannelKey key, String targetModel, String slotToken) {
}
