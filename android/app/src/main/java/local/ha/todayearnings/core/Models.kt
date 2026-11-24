package local.ha.todayearnings.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.UUID

// ───────────────────────── 刷新频率 ─────────────────────────

enum class UpdateMode(val label: String, val description: String) {
    EVERY_SECOND("每秒", "每秒跳"),
    EVERY_MINUTE("每分", "每分钟跳"),
    EVERY_HOUR("整点", "每小时整点跳");

    /** 下一次触发时刻：对齐到下一个整秒 / 整分 / 整点（与 macOS 版一致）。 */
    fun nextFire(now: ZonedDateTime): ZonedDateTime = when (this) {
        EVERY_SECOND -> now.withNano(0).plusSeconds(1)
        EVERY_MINUTE -> now.withNano(0).withSecond(0).plusMinutes(1)
        EVERY_HOUR -> now.withNano(0).withSecond(0).withMinute(0).plusHours(1)
    }

    companion object {
        fun fromLabel(label: String?): UpdateMode = entries.firstOrNull { it.label == label } ?: EVERY_SECOND
    }
}

// ───────────────────────── 上班时间段 ─────────────────────────

/**
 * 一个上班时间段，分钟数从 0 点起算。
 * 安卓版暂无夜班：要求 0 <= start < end <= 1440，不合法的时间段在计算里被忽略。
 */
data class WorkSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val isValid: Boolean get() = startMinutes in 0 until 1440 && endMinutes in 1..1440 && endMinutes > startMinutes
    val durationMinutes: Int get() = if (isValid) endMinutes - startMinutes else 0
}

// ───────────────────────── 记账数据 ─────────────────────────

/** half = true 表示半天假；deduct = true 表示扣钱（半天假扣日薪的一半）。 */
data class LeaveRecord(
    val deduct: Boolean = true,
    val half: Boolean = false,
    val note: String = "",
) {
    val factor: Double get() = if (half) 0.5 else 1.0
}

/** paid = true 表示加班加钱；multiplier 为加班倍率（默认 1.0）。 */
data class OvertimeRecord(
    val paid: Boolean = true,
    val hours: Double = 2.0,
    val multiplier: Double = 1.0,
    val note: String = "",
)

data class DayRecord(
    val leave: LeaveRecord? = null,
    val overtime: OvertimeRecord? = null,
) {
    val isEmpty: Boolean get() = leave == null && overtime == null
}

/**
 * 记账本。
 * - startDate：开始计入收入的日期，早于它的日子不计入“已挣”；null 表示不限制。
 * - plans：“yyyy-MM” → 当月上班的日期集合。没有记录的月份一律使用默认（周一至周五），不再复制上月。
 * - records：“yyyy-MM-dd” → 请假/加班记录。
 */
data class Ledger(
    val startDate: LocalDate? = null,
    val plans: Map<String, Set<Int>> = emptyMap(),
    val records: Map<String, DayRecord> = emptyMap(),
)

data class Settings(
    val monthlySalary: Double = 10000.0,
    val segments: List<WorkSegment> = defaultSegments(),
    val updateMode: UpdateMode = UpdateMode.EVERY_SECOND,
    /** 每月计费起算日（1~28）。1 = 自然月；25 = 上月 25 日 ~ 本月 24 日算作“本月”。 */
    val cycleStartDay: Int = 1,
) {
    companion object {
        fun defaultSegments(): List<WorkSegment> = listOf(
            WorkSegment(startMinutes = 9 * 60, endMinutes = 12 * 60),
            WorkSegment(startMinutes = 13 * 60, endMinutes = 18 * 60),
        )
    }
}

data class Role(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isPrimary: Boolean = false,
    val settings: Settings = Settings(),
    val ledger: Ledger = Ledger(),
)

data class AppData(
    val roles: List<Role>,
    val activeRoleId: String,
) {
    val activeRole: Role get() = roles.firstOrNull { it.id == activeRoleId } ?: roles.first()

    /** 保证：至少 1 个角色；有且只有 1 个主账户（缺失时把第一个补成主账户）；当前角色存在。 */
    fun normalized(): AppData {
        var list = roles
        if (list.isEmpty()) list = listOf(Role(name = "主账户", isPrimary = true))
        if (list.none { it.isPrimary }) {
            list = list.mapIndexed { i, r -> if (i == 0) r.copy(isPrimary = true) else r }
        } else if (list.count { it.isPrimary } > 1) {
            val first = list.first { it.isPrimary }.id
            list = list.map { it.copy(isPrimary = it.id == first) }
        }
        val active = if (list.any { it.id == activeRoleId }) activeRoleId else list.first().id
        return AppData(list, active)
    }

    companion object {
        fun initial(): AppData {
            val role = Role(name = "主账户", isPrimary = true)
            return AppData(listOf(role), role.id)
        }
    }
}

// ───────────────────────── 日期工具 ─────────────────────────

fun YearMonth.key(): String = String.format("%04d-%02d", year, monthValue)

fun LocalDate.key(): String = toString() // ISO：yyyy-MM-dd

/** 默认工作日模板：周一至周五，不含法定节假日与调休。 */
fun YearMonth.defaultWorkdays(): Set<Int> =
    (1..lengthOfMonth()).filter { d ->
        val w = atDay(d).dayOfWeek
        w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY
    }.toSet()

/** 周一开头布局下，第一行前面的空格数。 */
fun YearMonth.leadingBlanks(): Int = atDay(1).dayOfWeek.value - 1

fun YearMonth.title(): String = "${year}年${monthValue}月"


// ───────────────────────── 计费周期 ─────────────────────────

/**
 * 计费周期，按“结束日所在月”命名：起算日 25 时，10 月 25 日 ~ 11 月 24 日 = “11 月”。
 * 起算日为 1 时就是自然月。工作日安排仍按自然日期存储，所以改起算日不会丢数据。
 */
object Cycle {
    fun normalize(startDay: Int): Int = startDay.coerceIn(1, 28)

    fun start(ym: YearMonth, startDay: Int): LocalDate {
        val d = normalize(startDay)
        return if (d == 1) ym.atDay(1) else ym.minusMonths(1).atDay(d)
    }

    fun end(ym: YearMonth, startDay: Int): LocalDate = start(ym.plusMonths(1), startDay).minusDays(1)

    /** 某一天属于哪个计费周期（用结束日所在月表示）。 */
    fun periodOf(date: LocalDate, startDay: Int): YearMonth {
        val d = normalize(startDay)
        val ym = YearMonth.from(date)
        return if (d > 1 && date.dayOfMonth >= d) ym.plusMonths(1) else ym
    }

    fun dates(ym: YearMonth, startDay: Int): List<LocalDate> {
        val s = start(ym, startDay)
        val n = java.time.temporal.ChronoUnit.DAYS.between(s, end(ym, startDay)).toInt() + 1
        return List(n) { s.plusDays(it.toLong()) }
    }

    /** 例如 "10/25 – 11/24"；自然月返回 null。 */
    fun rangeText(ym: YearMonth, startDay: Int): String? {
        if (normalize(startDay) == 1) return null
        val s = start(ym, startDay); val e = end(ym, startDay)
        return "${s.monthValue}/${s.dayOfMonth} – ${e.monthValue}/${e.dayOfMonth}"
    }
}
