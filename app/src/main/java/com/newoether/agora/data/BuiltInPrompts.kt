package com.newoether.agora.data

object BuiltInPrompts {
    const val TITLE_GENERATION_SYSTEM =
        "You are a title generator. Output only a short title in the same language as the conversation."

    val CONTEXT_COMPACT_SYSTEM = """
        你在整合下面的对话原文：整理归纳，去掉句式上的冗余，用更简洁的文字表达同样意思。输出称为整合文本。原则上不可丢失下文「整合原则」所定义的必要信息。

        原文按时间排列，可能有事实冲突。工程与技术支持可以概括错误尝试或推导过程，结论不能损失细节。个人相关的事件过程和结论都要保留。

        整合原则：

        原则一：与用户个人相关的信息必须完全保留，只做语句的整理和归纳，事件的过程和结论都要保留，不得丢失任何信息。包括但不限于：用户本人；用户提到的朋友、家人（重要性等同用户）；情感、日常生活、过往经历等对故事线有帮助的事件；用户的经验总结和人生感悟；带强烈个人色彩的知识；和模型一起聊出来的、训练库里大致不会有的理论、结论、经验、感悟。

        原则二：工程与技术支持（包括但不限于编程、运维、错误排查）只留已确认的事实和结论。工具调用尤其要概括：调用本身不是目的，是达到目的的过程。现行要落地的方案记清楚；作废的旧版少记或一句带过。

        原则三：通用知识可以只提一句，只说明曾经问过，可不保留具体细节。包括训练库里大概率已有的通识、公开资料、标准做法。你本来就掌握的知识不必写进整合文本。本原则必须在不违背原则一、二时才能生效：用户的取舍、配置、实测、和模型一起得出的结论，一律走原则一或二。

        不要 JSON。不要「以下是总结」这类标题套话。不要翻译，语言跟对话走。你不是在跟用户对话。不要道歉、不要「我明白了」。只输出整合文本。
        """.trimIndent()

    const val CONTEXT_COMPACT_DIGEST_SYSTEM =
        "你在维护这份会话的一份发现用归档索引。" +
            "不是短的现行世界观，也不是按批编年复述，也不是把各批中间稿粘成一篇设计说明书。" +
            "工作模型只靠正文决定查哪个 batchId。专有名词、结论、未决、以后能搜到的短语要留下，并在相关条目旁挂上闭集里的完整 batchId。" +
            "改写上一份 Digest：过时数字和已完成事项覆盖或删掉；跑题收成一句并挂对应完整 batchId。" +
            "结构随对话变，不要套固定模板，也不要把「原位改写」「归档索引 Digest」这类提示套话写成标题。" +
            "引用规则必须同时满足：" +
            "只能从用户消息闭集整段抄写 batchId，禁止编造、拼接、改写；" +
            "每个号必须从 batch_ 抄到最后一个十六进制字符，中间连字符也要在；禁止截成前几位，禁止加省略号；" +
            "闭集里每一个 batchId 都必须在正文里至少完整出现一次；拿不准挂哪条时，仍把完整号挂在最相关的一条上；" +
            "同一号可出现多次；推荐用反引号包住闭集里的完整号。" +
            "锁定轮和最新保留窗口不由这份 Digest 覆盖。" +
            "用户活跃记忆和人设只作词汇表，不是要整合的内容，不要写进 Digest，不要当第二份世界观；和本会话冲突时以本会话为准。" +
            "篇幅大约最多 3000 token（约 12000 字）。超预算就合并主题、收掉过时条目，不要只追加。" +
            "语言跟对话走，不要翻译。只输出 Digest 正文，不要 JSON，不要 notes。"

    const val CONTEXT_INTEGRATE_SYSTEM =
        "你在为 Agora 写「整合带」的一轮驻留稿，不是归档索引，也不是对话复盘。" +
            "宿主会把本轮 id 写在 JSON 的 turn_id 上；工作模型用那个字段查细节库。你这一次只整合这一轮原文。" +
            "只做信息整合：去掉口语和结构上的冗余，用更短更准的话复述本轮；轮内把同类事实归并到一处。" +
            "不要跨轮改写更早的对话。不要输出 turnId、不要抄 --- turn --- 行、不要标题套话。" +
            "工具调用不要保留协议行或结果全文：改为一句总括（调过哪些工具、有没有查到、大约几条）。有用的结论、数字、路径写进正文。" +
            "空搜若会挡住以后用同一查询，留这一句即可。日志、网页正文、命令输出原文留在细节库，不要贴进来。" +
            "尽量无损：可行动事实不要丢。只输出整合正文，不要 JSON。"

    const val CONTEXT_INTEGRATE_USER = "现在整合这一轮已完成的对话。"

    const val IMAGE_TRANSCRIPTION_SYSTEM =
        "You are an image describer. Describe the given image in detail."

    const val IMAGE_TRANSCRIPTION_USER =
        "Please describe this image in detail. Include all visible text, data, charts, layout, and visual elements. Preserve the original language of any text shown."
}
