package com.nexusai.common.enums;

/**
 * 模态类型（决定走哪条管线 / 哪类 Provider 能力）。
 *
 * @author nexusai
 */
public enum ModalityType {
    /** 对话/补全（文本·按 token 计费） */
    CHAT,
    /** 向量嵌入 */
    EMBEDDING,
    /** 图片生成/编辑（按张计费） */
    IMAGE,
    /** 语音（转写/翻译/TTS） */
    AUDIO,
    /** 文档重排序 */
    RERANK,
    /** 视频生成（异步任务） */
    VIDEO,
    /** 其它/未归类 */
    OTHER
}
