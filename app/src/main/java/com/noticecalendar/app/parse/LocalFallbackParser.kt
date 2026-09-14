package com.noticecalendar.app.parse

import com.noticecalendar.app.llm.ParsedEvent
import java.time.LocalDate
import java.time.LocalTime

/**
 * 无网络 / 未配置API Key / API调用失败时的本地规则兜底解析。
 * 支持阿拉伯数字和中文数字的日期/时间表达，支持常见校园和生活地点识别。
 */
object LocalFallbackParser {

    private val EMOJI = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F\\u200D\\u2600-\\u27BF#*\\u20E3]+")
    // 阿拉伯数字时间：下午3点、8:30、晚上7点半
    private val TIME_RE = Regex("(凌晨|早上|上午|中午|下午|晚上|晚)?\\s*(\\d{1,2})[点时:：]\\s*(半|一刻|三刻|(\\d{1,2})分?)?")
    // 中文数字时间：早上八点、下午三点半、八点十五
    private val CN_TIME_RE = Regex("(凌晨|早上|上午|中午|下午|晚上|晚)?\\s*([一二三四五六七八九十两]+)[点时]\\s*(半|一刻|三刻|([一二三四五六七八九十两]+)分?)?")
    private val UPDATE_WORDS = Regex("延期|改期|更改|调整|推迟|顺延|变更|改到|改为|改在|时间改|地点改")
    // "延期到/改期到"等后面跟新时间的模式
    private val UPDATE_TO = Regex("(?:延期到|改期到|调整为|推迟到|顺延至|改到|改为|改在)")
    // 常见事件名后缀：优先从通知中提取核心事件名（如"志愿者面试""互评大会"）
    private val EVENT_SUFFIX = "面试|笔试|考试|测验|会议|例会|班会|讲座|培训|大会|答辩|活动|仪式|演练|彩排|值班|签到|比赛|竞赛|测试|座谈|汇报|研讨|团课|党课|宣讲|招新|纳新|换届|动员会|总结会|分享会|宣讲会|报告会|座谈会|招聘|科目"
    // 考试/活动与事件关键字之间用于连接的动词（提取科目信息时先剔除）
    private val LINK_WORDS = Regex("^(?:于|在|是|为|将于|定于|举办|举行|开考|开展|进行|的|安排)+")
    // 科目信息：如"英语四级和六级""英语四六级""计算机二级"
    // 注意末尾必须用 lookahead（不能用 ?），否则非贪婪会只匹配到"英语四级"就停
    private val SUBJECT_PHRASE = Regex(
        "(?:全国)?(?:大学)?(?:英语|计算机|日语|俄语|法语|德语|四级|六级|四六级|专四|专八|雅思|托福|考研|CET)" +
            "[\\u4e00-\\u9fa5A-Za-z0-9]{0,4}?(?:四六级|四级|六级|AB级|A级|B级|[一二三四五六七八九十]级|考试|笔试)" +
            "(?:[和与、及][\\u4e00-\\u9fa5A-Za-z0-9]{0,3}?(?:四六级|四级|六级|[一二三四五六七八九十]级))?" +
            "(?=[，,。.！!？?；;\\s]|$)"
    )
    // 考试类型：笔试 / 口试 / 机考
    private val EXAM_TYPE = Regex("笔试|口试|机考|上机考试|机试")
    // 科目名开头的限定词（"全国计算机二级考试"→"计算机二级考试"，更贴近学生日常叫法）
    private val SUBJECT_PREFIX = Regex("^(?:全国|大学|本校|我校)+")
    // 事件名词头的噪音前缀：时间词/范围词/虚词（"2点实验室例会""全国计算机二级考试"）
    // 匹配的是"从头开始连续的噪音字"，取其结束位置之后的文本
    private val NOISE_PREFIX = Regex("^[点时分的在于全各本该这个了的和与及晚上下午早中国]+")
    // 事件名里的通告类噪音词（"班会通知""实验室例会请各组成员参加"里的尾巴）
    private val TITLE_NOISE = Regex("(?:通知|公告|提醒|须知|安排|请|，|,|。|\\.)+$")
    // 事件名左侧的边界词：出现这些引导动词/介词，说明其左侧属于句子其他部分
    private val EVENT_BOUNDARY_PHRASES = listOf(
        "召开", "举行", "举办", "开展", "进行", "组织", "安排", "关于", "参加", "报名参加", "定于", "将于"
    )
    // 带前缀的房间号/字母数字地点："面试地点在10110""候场地点在10113B""考试地点：A101"
    private val ROOM_RE = Regex("(面试地点|候场地点|考试地点|集合地点|活动地点|比赛地点|报到地点|演出地点|参赛地点|笔试地点|答辩地点|开会地点|会议地点|上课地点|培训地点|值班地点|地点)(?:为|是|在|：|:)?\\s*([A-Za-z0-9][A-Za-z0-9\\-]{1,19})")
    // 从"本次XX延期""该XX改期"中提取事件名
    private val EVENT_NAME_PATTERNS = listOf(
        Regex("本次(.+?)(?:延期|改期|更改|调整|推迟|顺延|取消|变更)"),
        Regex("该(.+?)(?:延期|改期|更改|调整|推迟|顺延|取消|变更)"),
        Regex("这个(.+?)(?:延期|改期|更改|调整|推迟|顺延|取消|变更)"),
        Regex("原定于.+?的(.+?)(?:延期|改期|更改|调整|推迟|顺延|取消|变更)"),
        Regex("(.+?)(?:延期|改期|更改|调整|推迟|顺延)到")
    )
    // 常见地点后缀关键词（校园+生活）
    private val LOCATION_SUFFIX = (
        "教室|会议室|实验室|报告厅|操场|图书馆|体育馆|教学楼|宿舍|号楼|楼|栋|室|厅|" +
        "医院|卫生院|诊所|卫生所|药店|药房|" +
        "公园|广场|花园|植物园|动物园|游乐园|景区|景点|风景区|" +
        "商场|超市|便利店|市场|菜市场|购物中心|百货|" +
        "车站|火车站|高铁站|动车站|机场|地铁站|公交站|客运站|码头|港口|" +
        "银行|邮局|营业厅|ATM|" +
        "餐厅|饭店|食堂|小吃店|面馆|火锅店|烧烤店|奶茶店|咖啡店|酒吧|" +
        "酒店|宾馆|民宿|旅馆|招待所|" +
        "博物馆|展览馆|美术馆|科技馆|文化馆|少年宫|档案馆|" +
        "电影院|剧院|剧场|音乐厅|KTV|网吧|台球厅|健身房|游泳馆|羽毛球馆|篮球馆|" +
        "大学|学院|学校|幼儿园|小学|中学|高中|初中|培训中心|教育机构|" +
        "公司|工厂|仓库|产业园|工业园|开发区|写字楼|大厦|中心|" +
        "小区|社区|公寓|花园|新村|街道|镇|乡|县|区|市|村|队|组"
    )

    fun parse(raw: String): ParsedEvent {
        val text = raw.replace("\n", " ")
        val today = LocalDate.now()
        val isUpdate = UPDATE_WORDS.containsMatchIn(raw)
        val eventName = if (isUpdate) extractEventName(raw) else null

        // 更新类型：优先取"延期到"后面的文本作为时间来源，避免取到联系截止时间等干扰
        val timeSource = if (isUpdate && eventName != null) {
            val m = UPDATE_TO.find(text)
            if (m != null) {
                val after = text.substring(m.range.first)
                val cut = after.indexOfFirst { it in "，。？?；;,.!！" }
                if (cut > 0) after.substring(0, cut) else after.take(40)
            } else text
        } else text

        // 先找时间（阿拉伯数字优先，再中文数字），再从剩余文本中找日期
        var time: LocalTime? = null
        var matchedTimeRange: IntRange? = null
        val tm = TIME_RE.find(timeSource)
        if (tm != null) {
            time = parseArabicTime(tm)
            matchedTimeRange = tm.range
        } else {
            val ctm = CN_TIME_RE.find(timeSource)
            if (ctm != null) {
                time = parseChineseTime(ctm)
                matchedTimeRange = ctm.range
            }
        }
        val dateText = if (matchedTimeRange != null) timeSource.replaceRange(matchedTimeRange, " ") else timeSource
        val date = findDate(dateText, today)

        // 只有时段没有钟点（如"9月11日晚进行"）：映射默认时刻，避免误判为全天
        if (time == null) {
            time = vaguePeriodTime(timeSource)
        }

        // 更新类型：标题用提取到的事件名，而不是原文第一行（变更通知第一行通常是原因）
        // 非更新类型：优先用"科目+考试类型"或核心事件名（"英语四级和六级笔试""志愿者面试"），最后才退回第一行
        val title = when {
            isUpdate && !eventName.isNullOrBlank() -> eventName
            else -> buildTitle(raw)
        }
        return ParsedEvent(
            title = title,
            date = date?.toString(),
            time = time?.toString(),
            endTime = null,
            allDay = time == null,
            location = findLocation(text),
            description = "",
            type = if (isUpdate && date != null && !eventName.isNullOrBlank()) "update" else "new",
            matchKeyword = if (isUpdate && !eventName.isNullOrBlank()) eventName else null
        )
    }

    /** 解析阿拉伯数字时间匹配结果 */
    private fun parseArabicTime(m: MatchResult): LocalTime? {
        val hourRaw = m.groupValues[2].toIntOrNull() ?: return null
        val period = m.groupValues[1]
        val minPart = m.groupValues[3]
        val minute = when {
            minPart == "半" -> 30
            minPart == "一刻" -> 15
            minPart == "三刻" -> 45
            minPart.isEmpty() -> 0
            else -> minPart.removeSuffix("分").toIntOrNull() ?: 0
        }
        return normalizeTime(hourRaw, minute, period)
    }

    /** 解析中文数字时间匹配结果 */
    private fun parseChineseTime(m: MatchResult): LocalTime? {
        val hourRaw = cnNumToInt(m.groupValues[2]) ?: return null
        val period = m.groupValues[1]
        val minPart = m.groupValues[3]
        val minute = when {
            minPart == "半" -> 30
            minPart == "一刻" -> 15
            minPart == "三刻" -> 45
            minPart.isEmpty() -> 0
            else -> cnNumToInt(minPart.removeSuffix("分")) ?: 0
        }
        return normalizeTime(hourRaw, minute, period)
    }

    /** 根据时段（下午/晚上等）调整小时，确保在合法范围 */
    private fun normalizeTime(hour: Int, minute: Int, period: String): LocalTime? {
        var h = hour
        if (period == "下午" || period == "晚上" || period == "晚") {
            if (h in 1..11) h += 12
        } else if (period == "中午") {
            h = 12
        }
        if (h !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(h, minute)
    }

    /** 中文数字转整数（支持1-31，用于日期和时间） */
    private fun cnNumToInt(s: String): Int? {
        val str = s.trim()
        if (str.isEmpty()) return null
        // 直接映射单个数字
        val single = mapOf(
            "零" to 0, "〇" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3,
            "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        single[str]?.let { return it }
        // 十X = 10+X（十一、十二...十九）
        if (str.startsWith("十") && str.length == 2) {
            val unit = single[str[1].toString()] ?: return null
            return 10 + unit
        }
        // X十 = X*10（二十、三十）
        if (str.endsWith("十") && str.length == 2) {
            val tens = single[str[0].toString()] ?: return null
            return tens * 10
        }
        // X十Y = X*10+Y（二十一、三十一...）
        if (str.length == 3 && str[1] == '十') {
            val tens = single[str[0].toString()] ?: return null
            val unit = single[str[2].toString()] ?: return null
            return tens * 10 + unit
        }
        return null
    }

    /**
     * 从通知中提取核心事件名（如"发展对象互评大会""班会""英语四六级考试"）。
     *
     * 做法是确定性的：定位事件类型关键词（大会/面试/考试…），向前扫到最近的标点或引导动词，
     * 取其中的修饰语组成事件名，最后剥掉词头噪音与"通知"类尾词。不使用复杂正则，便于测试与维护。
     */
    private fun extractEventTitle(raw: String): String? {
        val text = raw.replace("\n", " ")
        if (text.isBlank()) return null
        // 长关键词优先，避免"动员会"被"会"抢先命中
        val keywords = EVENT_SUFFIX.split("|").sortedByDescending { it.length }
        for (kw in keywords) {
            var from = 0
            while (true) {
                val at = text.indexOf(kw, from)
                if (at < 0) break
                val candidate = buildEventCandidate(text, at, kw)
                if (candidate != null) return candidate.take(20)
                from = at + kw.length
            }
        }
        return null
    }

    /** 取出关键词及其前面的修饰语，切成一个干净的事件名；不合法返回 null */
    private fun buildEventCandidate(text: String, at: Int, kw: String): String? {
        val end = at + kw.length
        // 向前扫描放宽到 12 字：真正的边界由标点/引导动词决定，
        // 窄上限会在"全国计算机二级考试"这类里把"全"截掉留下"国"
        val maxPrefix = 12

        // 往前最多取 maxPrefix 个汉字
        var scan = at
        while (scan > 0 && at - scan < maxPrefix && text[scan - 1] in '\u4e00'..'\u9fa5') scan--
        val before = text.substring(0, at)

        // 找最近的边界：标点/空格，或"召开/举行"这类引导动词
        val boundaryChar = before.indexOfLast { it !in '\u4e00'..'\u9fa5' }
        val boundaryPhrase = EVENT_BOUNDARY_PHRASES.maxOfOrNull { p ->
            val i = before.lastIndexOf(p)
            if (i < 0) -1 else i + p.length
        } ?: -1
        val start = maxOf(scan, boundaryChar + 1, boundaryPhrase)

        var phrase = text.substring(start, end)
        // 从"首个不是时间/范围/虚词的字"开始截取（"全国计算机二级考试"→"计算机二级考试"）
        val noise = NOISE_PREFIX.find(phrase)
        if (noise != null) phrase = phrase.substring(noise.range.last + 1)
        phrase = trimEventName(phrase)
        if (phrase.length !in 2..8) return null
        if (phrase == kw && kw.length < 2) return null
        return phrase
    }

    /** 剪掉事件名上的通告类尾巴（"班会通知"→"班会"）与动词/助词残渣 */
    private fun trimEventName(n: String): String {
        var name = n.replace(TITLE_NOISE, "").trim()
        // 单个"通"（"班会通"）或"通知"残缺时直接去掉
        if (name.length >= 3 && name.endsWith("通")) name = name.dropLast(1)
        name = name.replace(Regex("(?:将于|定于|于|在|是|为|的|安排|时间|地点)+$"), "").trim()
        return name
    }

    /** 提取通知里的科目/等级信息（"英语四级和六级""计算机二级"） */
    private fun extractSubjectInfo(text: String): String? {
        val m = SUBJECT_PHRASE.find(text) ?: return null
        val cleaned = m.value
            .replace(LINK_WORDS, "")
            .replace(SUBJECT_PREFIX, "")
            .replace(Regex("[\\s，,。.！!？?、～\\-]+"), "")
            .trim()
        return cleaned.takeIf { it.length in 2..16 }
    }

    /**
     * 生成标题：先在整个通知里找"科目+考试类型"这种信息量最大的组合，
     * 再找核心事件名，最后才退回第一行（并清理）。
     *
     * 之所以不再只取第一行：群通知第一行常常是"笔试（CET）于2026年12月12日举行"，
     * 挖掉日期后只剩下无意义的残句。
     */
    private fun buildTitle(raw: String): String? {
        val text = raw.replace("\n", " ")
        val subject = extractSubjectInfo(text)
        val examType = EXAM_TYPE.find(text)?.value
        if (subject != null) {
            // 科目名里已含"考试/笔试"等字样时不再重复拼接
            val hasType = Regex("考试|笔试|口试|机考").containsMatchIn(subject)
            val combined = if (hasType) subject else "$subject${examType ?: "考试"}"
            return combined.take(20)
        }
        extractEventTitle(raw)?.let { return it }
        return firstTitle(raw)
    }

    /** 从变更通知中提取被修改的事件名（如"本次互评大会延期"→"互评大会"） */
    private fun extractEventName(raw: String): String? {
        for (pattern in EVENT_NAME_PATTERNS) {
            val m = pattern.find(raw)
            if (m != null) {
                var name = m.groupValues[1].trim()
                name = name.removePrefix("的").removeSuffix("的").trim()
                name = name.removePrefix("本次").removePrefix("该").removePrefix("这个").trim()
                name = name.replace(Regex("[，。！!？?、\\s]+$"), "").trim()
                if (name.length in 2..20) return name
            }
        }
        return null
    }

    private fun findDate(text: String, today: LocalDate): LocalDate? {
        if (Regex("大后天").containsMatchIn(text)) return today.plusDays(3)
        if (Regex("后天").containsMatchIn(text)) return today.plusDays(2)
        if (Regex("明天|明日").containsMatchIn(text)) return today.plusDays(1)
        if (Regex("今天|今日|今晚|今早").containsMatchIn(text)) return today

        // 下下周X / 下周X
        val mNext = Regex("(下下|下)(?:周|星期|礼拜)([一二三四五六日天])").find(text)
        if (mNext != null) {
            val target = cnWeekday(mNext.groupValues[2])
            if (target > 0) {
                val weeksAhead = if (mNext.groupValues[1] == "下下") 2 else 1
                val dow = today.dayOfWeek.value
                return today.plusDays(((8 - dow) + (target - 1) + 7L * (weeksAhead - 1)))
            }
        }
        // 本周X / 周X / 星期X（取最近的一天，含今天）
        val mThis = Regex("(?:周|星期|礼拜)([一二三四五六日天])").find(text)
        if (mThis != null) {
            val target = cnWeekday(mThis.groupValues[1])
            if (target > 0) {
                val dow = today.dayOfWeek.value
                return today.plusDays(((target - dow + 7) % 7).toLong())
            }
        }
        // 完整日期 2026-09-03 / 2026/9/3 / 2026.9.3
        val mFull = Regex("(\\d{4})[-/年.](\\d{1,2})[-/月.](\\d{1,2})").find(text)
        if (mFull != null) {
            val d = buildDate(mFull.groupValues[1].toIntOrNull(), mFull.groupValues[2].toIntOrNull(), mFull.groupValues[3].toIntOrNull(), today)
            if (d != null) return d
        }
        // 阿拉伯数字日期：9月10日 / 9月10号
        val mMd = Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(text)
        if (mMd != null) {
            val d = buildDate(today.year, mMd.groupValues[1].toIntOrNull(), mMd.groupValues[2].toIntOrNull(), today)
            if (d != null) return d
        }
        // 中文数字日期：九月十日 / 九月十号 / 九月二十一日
        val mCn = Regex("([一二三四五六七八九十两]+)月([一二三四五六七八九十两]+)[日号]").find(text)
        if (mCn != null) {
            val month = cnNumToInt(mCn.groupValues[1])
            val day = cnNumToInt(mCn.groupValues[2])
            val d = buildDate(today.year, month, day, today)
            if (d != null) return d
        }
        return null
    }

    private fun buildDate(y: Int?, mo: Int?, d: Int?, today: LocalDate): LocalDate? {
        if (y == null || mo == null || d == null) return null
        if (mo !in 1..12 || d !in 1..31) return null
        return try {
            var candidate = LocalDate.of(y, mo, d)
            // 只给"9月10日"没写年份时，若已过去超过半年则按明年理解
            if (candidate.isBefore(today.minusDays(180))) candidate = candidate.plusYears(1)
            candidate
        } catch (_: Exception) {
            null
        }
    }

    /** "今晚/晚上/下午"等只有时段没有钟点时的兜底映射 */
    private fun vaguePeriodTime(text: String): LocalTime? = when {
        Regex("晚上|傍晚|晚间|今晚|今夜|夜里|深夜|晚").containsMatchIn(text) -> LocalTime.of(19, 0)
        Regex("中午|午间").containsMatchIn(text) -> LocalTime.of(12, 0)
        Regex("下午").containsMatchIn(text) -> LocalTime.of(14, 0)
        Regex("上午|早上|清晨|凌晨").containsMatchIn(text) -> LocalTime.of(9, 0)
        else -> null
    }

    private fun cnWeekday(c: String): Int = when (c) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> 0
    }

    private fun firstTitle(raw: String): String? {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.length >= 2 } ?: return null
        var t = EMOJI.replace(line, "")
        t = t.replace(Regex("^[\\s@，。！!？?：:、～\\-]+"), "").trim()
        t = t.removePrefix("通知：").removePrefix("通知:").trim()
        // 清理标题：去掉日期、时间、连接词，只保留核心事件（日历上已显示时间，标题无需重复）
        val cleaned = cleanTitle(t)
        val result = if (cleaned.length >= 2) cleaned else t
        return if (result.length > 30) result.take(30) else result.ifBlank { null }
    }

    /** 从标题中去掉日期、时间、连接词，只保留核心事件名 */
    private fun cleanTitle(t: String): String {
        var s = t
        // 1. 去掉完整日期 2026-09-10 / 2026/9/10 / 2026.9.10
        s = s.replace(Regex("\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}[日号]?"), " ")
        // 2. 去掉阿拉伯数字月日 9月10日 / 9月10号
        s = s.replace(Regex("\\d{1,2}月\\d{1,2}[日号]"), " ")
        // 3. 去掉中文数字月日 九月十日 / 九月二十一号
        s = s.replace(Regex("[一二三四五六七八九十两]+月[一二三四五六七八九十两]+[日号]"), " ")
        // 4. 去掉相对日期
        s = s.replace(Regex("大后天|后天|明天|明日|今天|今日|今晚|今早|明早|明晚"), " ")
        // 5. 去掉周几（含下下周/下周/本周前缀）
        s = s.replace(Regex("(?:下下|下|本)?(?:周|星期|礼拜)[一二三四五六日天]"), " ")
        // 6. 去掉阿拉伯数字时间 下午3点、8:30、晚上7点半
        s = s.replace(Regex("(?:凌晨|早上|上午|中午|下午|晚上|晚)?\\s*\\d{1,2}[点时:：]\\s*(?:半|一刻|三刻|\\d{1,2}分?)?"), " ")
        // 7. 去掉中文数字时间 早上八点、下午三点半
        s = s.replace(Regex("(?:凌晨|早上|上午|中午|下午|晚上|晚)?\\s*[一二三四五六七八九十两]+[点时]\\s*(?:半|一刻|三刻|[一二三四五六七八九十两]+分?)?"), " ")
        // 8. 去掉单独的时段词（残留的）
        s = s.replace(Regex("凌晨|早上|上午|中午|下午|晚上|傍晚|晚间|今晚|夜里|深夜|清晨|明早|明晚"), " ")
        // 8. 去掉"具体场次安排如下"这类承接句（OCR 截断时它会变成粘在标题尾巴上的残渣）
        s = s.replace(Regex("[，,。.！!？?]?\\s*(?:具体[\\u4e00-\\u9fa5]{0,10}|详情|时间|地点|场次|安排)?(?:安排)?如下.*$"), " ")
        // 9. 去掉连接词前缀（要去、去、到、前往、赴、在、于、有个、需要、记得等）
        s = s.replace(Regex("^[\\s，,。.！!？?、]+"), "")
        s = s.replace(Regex("^(?:要去|要到|想去|想到|去|到|前往|赴|在|于|有个|有一个|需要|记得|别忘了|要|将|需|得|准备|打算)"), "")
        // 10. 中文标点只替换成空格，不能让两侧汉字粘连（"…六级，具体…"曾被粘成"级具"）
        s = s.replace(Regex("[，,。.！!？?、～]+"), " ")
        s = s.replace(Regex("[\\s\\-]+"), " ").trim()
        return s
    }

    private fun findLocation(text: String): String {
        // 优先：带前缀的房间号/字母数字地点（"面试地点在10110，候场地点在10113B"→"10110；候场10113B"）
        val rooms = ROOM_RE.findAll(text).toList()
        if (rooms.isNotEmpty()) {
            val parts = rooms.mapIndexed { i, m ->
                val prefix = m.groupValues[1].removeSuffix("地点")
                val room = m.groupValues[2]
                if (i == 0) room else if (prefix.isBlank()) room else "$prefix$room"
            }
            return parts.distinct().joinToString("；").take(50)
        }
        // 显式"地点：XXX"格式优先
        val m1 = Regex("地点[:：]\\s*([^\\s，,。；;]+)").find(text)
        if (m1 != null) return m1.groupValues[1]
        // "在/于 + 地点后缀"格式
        val m2 = Regex("(?:在|于)([\\u4e00-\\u9fa5A-Za-z0-9\\-]{1,20}?(?:$LOCATION_SUFFIX))").find(text)
        if (m2 != null) return m2.groupValues[1]
        // "去/到 + 地点后缀"格式（如"去四川省人民医院"）
        val m3 = Regex("(?:去|到|前往|赴)([\\u4e00-\\u9fa5A-Za-z0-9\\-]{1,20}?(?:$LOCATION_SUFFIX))").find(text)
        if (m3 != null) return m3.groupValues[1]
        return ""
    }
}
