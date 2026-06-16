package com.nexusai.protocol.transform;

import com.nexusai.protocol.canonical.CanonicalContentPart;
import com.nexusai.protocol.canonical.CanonicalMessage;
import com.nexusai.protocol.canonical.CanonicalToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 工具配对修复器。
 * <p>Anthropic {@code /v1/messages} 对工具历史有强不变式：每个 {@code tool_use} 必须由紧随其后的 user 消息中
 * 对应 {@code tool_use_id} 的 {@code tool_result} 应答；每个 {@code tool_result} 必须在前一条 assistant 消息里
 * 有对应 {@code tool_use}；且 user/assistant 角色严格交替。Canonical 逐条转换会在调用与结果之间插入消息时打破邻接，
 * 直接发上游得 400。
 * <p>本修复在 {@link CanonicalMessage} 序列上：① 按 {@link CanonicalMessage#toolCallId()} 索引全部
 * {@code role=tool} 结果（重复后者胜）；② 每条 assistant 消息只保留有应答的 {@code toolCalls}（丢悬空调用·
 * 全悬空且无其他内容则丢该消息），紧接其后按调用顺序补出对应结果（每结果一条 {@code role=tool} 消息）；
 * ③ 原位置的孤立 tool 结果丢弃（已在调用旁重发）；④ 合并相邻同角色 user/assistant（恢复交替）。
 * 把并行调用的多条 {@code role=tool} 聚成 Anthropic 单条 user 多 {@code tool_result} 块由出站端
 * {@link AnthropicChatTransformer} 完成（职责归位）。两次合并夹一次配对：先合并把并行调用聚到一起
 * 供配对看见，配对后再合并复原交替。
 *
 * @author nexusai
 */
final class AnthropicToolPairing {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";

    private AnthropicToolPairing() {
    }

    static List<CanonicalMessage> repair(List<CanonicalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        List<CanonicalMessage> merged = mergeConsecutive(messages);
        List<CanonicalMessage> paired = pair(merged);
        return mergeConsecutive(paired);
    }

    /** 配对核心：丢悬空调用、孤立结果，按调用顺序把结果重发到对应 assistant 调用之后。 */
    private static List<CanonicalMessage> pair(List<CanonicalMessage> messages) {
        // 索引每个工具结果（按 toolCallId·后者胜）
        Map<String, CanonicalMessage> results = new HashMap<>();
        for (CanonicalMessage m : messages) {
            if (ROLE_TOOL.equals(m.role()) && m.toolCallId() != null) {
                results.put(m.toolCallId(), m);
            }
        }

        List<CanonicalMessage> out = new ArrayList<>(messages.size());
        for (CanonicalMessage m : messages) {
            if (ROLE_ASSISTANT.equals(m.role())) {
                List<CanonicalToolCall> calls = m.toolCalls();
                if (calls == null || calls.isEmpty()) {
                    out.add(m);
                    continue;
                }
                // 只保留有应答的调用（丢悬空）
                List<CanonicalToolCall> kept = new ArrayList<>(calls.size());
                for (CanonicalToolCall call : calls) {
                    if (call.id() != null && results.containsKey(call.id())) {
                        kept.add(call);
                    }
                }
                if (kept.isEmpty()) {
                    // 无应答调用：保留非工具内容（文本/图片）·全无则整条丢
                    if (hasContent(m.parts())) {
                        out.add(CanonicalMessage.of(ROLE_ASSISTANT, m.parts(), List.of(), null));
                    }
                    continue;
                }
                out.add(CanonicalMessage.of(ROLE_ASSISTANT, m.parts(), kept, null));
                // 按调用顺序紧接补出结果（每结果一条 role=tool 消息·出站端再聚为单条 user）
                for (CanonicalToolCall call : kept) {
                    out.add(results.get(call.id()));
                }
            } else if (ROLE_TOOL.equals(m.role())) {
                // 孤立工具结果：已在调用旁重发·此处丢弃
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean hasContent(List<CanonicalContentPart> parts) {
        if (parts == null) {
            return false;
        }
        for (CanonicalContentPart p : parts) {
            if (p != null && ((p.isText() && p.text() != null && !p.text().isEmpty()) || p.isImage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并相邻同角色 user/assistant（Anthropic 要求交替）：合并 parts + toolCalls。
     * {@code role=tool} 不参与合并（每结果保持独立 toolCallId·由出站端聚块）。
     */
    private static List<CanonicalMessage> mergeConsecutive(List<CanonicalMessage> messages) {
        if (messages.size() <= 1) {
            return messages;
        }
        List<CanonicalMessage> merged = new ArrayList<>(messages.size());
        for (CanonicalMessage m : messages) {
            CanonicalMessage last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last == null || ROLE_TOOL.equals(m.role()) || !sameRole(last, m)) {
                merged.add(m);
                continue;
            }
            List<CanonicalContentPart> parts = new ArrayList<>(last.parts());
            parts.addAll(m.parts());
            List<CanonicalToolCall> calls = new ArrayList<>(last.toolCalls());
            calls.addAll(m.toolCalls());
            merged.set(merged.size() - 1, CanonicalMessage.of(last.role(), parts, calls, last.toolCallId()));
        }
        return merged;
    }

    private static boolean sameRole(CanonicalMessage a, CanonicalMessage b) {
        return a.role() != null && a.role().equals(b.role());
    }
}
