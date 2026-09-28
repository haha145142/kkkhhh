package com.jev.probe.core.kb

import com.jev.probe.core.ChatSnapshot

object BuiltinLoveKnowledge {
    data class Topic(
        val id: String,
        val title: String,
        val tags: List<String>,
        val content: String
    )

    val coreNotes: List<Note> = listOf(
        Note(
            id = "builtin_core_evidence",
            title = "狗头军师核心：证据边界",
            content = "只把当前聊天中可直接看到的原文、说话人、顺序和明确时间当事实。潜台词、喜欢不喜欢、在试探我等只能作为推测。缺信息就写未知；不能为了完整而补脑。",
            tags = listOf("事实", "推测", "未知", "证据"),
            alwaysOn = true
        ),
        Note(
            id = "builtin_core_action",
            title = "狗头军师核心：单轮一个动作",
            content = "一轮消息只做一个主动作。先接住情绪，再分析关系，最后给现在能执行的小动作。持续主动、兑现、投入、边界和冲突修复比单次回复更有信息量。",
            tags = listOf("承接", "回答", "澄清", "邀约", "修复", "收口"),
            alwaysOn = true
        )
    )

    private val topics = listOf(
        Topic("emotion", "情绪承接", listOf("累","烦","难过","委屈","压力","不开心","崩溃","生气","心情"),
            "对方表达疲惫、委屈或难过时，先承接和确认感受，不急着讲道理、说教或连续追问。"),
        Topic("cold", "冷淡与回复波动", listOf("不回","没回","已读","敷衍","很冷","冷淡","忙","随便","哦","嗯","哈哈"),
            "短回复、延迟和一个表情不能单独证明不喜欢。结合持续模式、主动度、替代方案、兑现来判断；低证据时不连发、不查岗。"),
        Topic("ambiguity", "暧昧与试探", listOf("在乎","忘了","记得","想我","喜欢","暧昧","试探","你是不是","是不是不在乎"),
            "先区分表面问题和潜在需求。试探只能作为假设，先看上下文与过去行为，不把潜台词写成事实。"),
        Topic("date", "邀约与推进", listOf("吃饭","看电影","见面","周末","约","出去","一起","有空吗","什么时候见"),
            "具体、低压力地提出一个可执行安排，并给对方改时间或拒绝的空间。推进依据是回应、参与和兑现。"),
        Topic("conflict", "冲突与修复", listOf("生气","吵架","失望","对不起","道歉","怪我","不理解","受不了","分手"),
            "先确认能证明的责任和伤害，再决定道歉、解释、承诺还是收口。不翻旧账、不攻击人格、不逼表态。"),
        Topic("investment", "投入与互惠", listOf("主动","付出","只有我","都是我","不主动","消息都是我","冷处理"),
            "看一段时间内的双向投入：主动联系、回应质量、兑现、替代方案、现实支持、冲突修复，而不是单次高峰低谷。"),
        Topic("stage", "关系阶段", listOf("初识","认识","暧昧","追求","约会","对象","女朋友","男朋友","伴侣","分手"),
            "阶段来自档案与行为证据。初识重安全与自然；暧昧重双向筛选；确定关系重互信和边界；冲突期重修复。"),
        Topic("reply", "即时回复编排", listOf("怎么回","回复","回什么","一句","话术","聊天","接话"),
            "候选最多三条：真诚稳妥、轻松调侃、高情商推进。每条只做一个动作，短、口语、像真人微信。"),
        Topic("humor", "轻松调侃与松弛感", listOf("哈哈","笑死","开玩笑","逗","调侃","好玩","尴尬","冷场"),
            "上下文轻松时才调侃；建立在当下共同情境，不贬低、不测试、不制造嫉妒。冲突或低落时先收起幽默。"),
        Topic("boundary", "拒绝与边界", listOf("别联系","不要联系","别找我","不想聊","不舒服","拒绝","算了","别来"),
            "明确拒绝或要求停止联系时，不生成推进型回复。优先尊重边界；不要用一句含糊话覆盖持续明确的拒绝。"),
        Topic("safety", "安全与高风险", listOf("威胁","跟踪","骚扰","偷拍","诈骗","借钱","暴力","勒索","自伤"),
            "出现人身安全、跟踪、胁迫、诈骗、财务控制等信号时，安全和边界优先于关系推进。"),
        Topic("memory", "长期关系记忆", listOf("上次","以前","之前","记得吗","你忘了","去年","约定"),
            "涉及过去事件时优先查找当前对象的历史与档案；记不清就承认不确定，不凭感觉补记忆。")
    )

    fun allTopics(): List<Topic> = topics
    fun builtinCount(): Int = coreNotes.size + topics.size

    fun forSnapshot(snapshot: ChatSnapshot): List<Note> {
        val haystack = buildString {
            append(snapshot.title?.lowercase().orEmpty())
            snapshot.messages.takeLast(12).forEach { append('\\n').append(it.text.lowercase()) }
        }
        val hits = topics.filter { topic ->
            topic.tags.any { tag -> tag.isNotBlank() && haystack.contains(tag.lowercase()) }
        }.take(4)
        val matched = hits.map { t ->
            Note("builtin_" + t.id, t.title, t.content, t.tags, false, true)
        }
        return coreNotes + matched
    }

    fun promptFor(snapshot: ChatSnapshot): String =
        forSnapshot(snapshot).joinToString("\n") { n -> "- " + n.title + "：" + n.content }
}
