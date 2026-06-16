package com.nexusai.protocol.canonical;

import java.util.List;
import java.util.Map;

/**
 * Canonical 消息（中枢中立表示）。内容由 {@code parts}（文本/图片块）承载多模态，
 * {@code toolCalls} 承载 assistant 发起的历史工具调用，{@code toolCallId} 标记 {@code role=tool}
 * 的工具结果消息所应答的调用——三者一起修复跨协议互转时多轮工具对话/视觉请求断裂（评审缺口2）。
 * 其余未知字段仍入 {@code extensions} 兜底（每块必留 extensions，防互转丢字段）。
 *
 * @param role       角色（user/assistant/system/tool）
 * @param parts      内容块（文本/图片）
 * @param toolCalls  assistant 发起的工具调用（仅 assistant 消息有）
 * @param toolCallId 工具结果应答的调用 id（仅 {@code role=tool} 消息有）
 * @param extensions 未知/扩展字段
 * @author nexusai
 */
public record CanonicalMessage(String role, List<CanonicalContentPart> parts,
        List<CanonicalToolCall> toolCalls, String toolCallId, Map<String, Object> extensions) {

    /** 旧入口：纯文本消息（保留让现有 of(role,String) 调用零改动）。 */
    public static CanonicalMessage of(String role, String content) {
        return new CanonicalMessage(role, List.of(CanonicalContentPart.text(content)), List.of(), null, Map.of());
    }

    /** 块入口：多模态/工具消息。 */
    public static CanonicalMessage of(String role, List<CanonicalContentPart> parts,
            List<CanonicalToolCall> toolCalls, String toolCallId) {
        return new CanonicalMessage(role,
                parts == null ? List.of() : parts,
                toolCalls == null ? List.of() : toolCalls,
                toolCallId, Map.of());
    }

    /** 纯文本视图：拼接所有 {@code type=text} 块的文本（给只认 text 的旧路径/回退用）。 */
    public String text() {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CanonicalContentPart part : parts) {
            if (part != null && part.isText() && part.text() != null) {
                sb.append(part.text());
            }
        }
        return sb.toString();
    }
}
