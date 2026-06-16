package com.nexusai.control.domain.monitor.observe.vo;

import com.nexusai.persistence.log.RequestLogRecord;
import com.nexusai.persistence.po.NaiUsageLog;

import java.util.List;

/**
 * 单次请求贯穿钻取：请求日志行 + 同 requestId 的用量明细列表。
 *
 * @author nexusai
 */
public record RequestDetailVo(RequestLogRecord request, List<NaiUsageLog> usages) {
}
