package local.ha.todayearnings.core

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 一天内的区间，单位：距当天 0 点的秒数。 */
data class Interval(val startSec: Int, val endSec: Int)

enum class Phase { NO_WORK, BEFORE_WORK, WORKING, RESTING, AFTER_WORK, DAY_OFF }

/** 单日时间段相关的纯计算（与具体日期无关）。 */
object DayTime {
    /** 排序并合并重叠 / 首尾相接的时间段；不合法的时间段被忽略。 */
    fun mergedIntervals(segments: List<WorkSegment>): List<Interval> {
        val raw = segments.filter { it.isValid }
            .map { Interval(it.startMinutes * 60, it.endMinutes * 60) }
            .sortedBy { it.startSec }
        val out = ArrayList<Interval>()
        for (iv in raw) {
            val last = out.lastOrNull()
            if (last != null && iv.startSec <= last.endSec) {
                if (iv.endSec > last.endSec) out[out.size - 1] = Interval(last.startSec, iv.endSec)
            } else {
                out.add(iv)
            }
        }
        return out
    }

    fun workSeconds(segments: List<WorkSegment>): Int =
        mergedIntervals(segments).sumOf { it.endSec - it.startSec }

    /** 到 [nowSec] 为止已经工作的秒数（休息段不计）。 */
    fun elapsedWorkSeconds(segments: List<WorkSegment>, nowSec: Double): Double =
        mergedIntervals(segments).sumOf { iv ->
            if (nowSec <= iv.startSec) 0.0 else min(nowSec, iv.endSec.toDouble()) - iv.startSec
        }

    fun phase(segments: List<WorkSegment>, nowSec: Double): Phase {
        val ivs = mergedIntervals(segments)
        val first = ivs.firstOrNull() ?: return Phase.NO_WORK
        val last = ivs.last()
        return when {
            nowSec < first.startSec -> Phase.BEFORE_WORK
            nowSec >= last.endSec -> Phase.AFTER_WORK
            ivs.any { it.startSec <= nowSec && nowSec < it.endSec } -> Phase.WORKING
            else -> Phase.RESTING
        }
    }

    /** 当前状态下“距下一个状态”的秒数：上班中 = 距下班；休息中 = 距下一段开始；上班前 = 距第一段开始。 */
    fun remainingSeconds(segments: List<WorkSegment>, nowSec: Double): Double {
        val ivs = mergedIntervals(segments)
        return when (phase(segments, nowSec)) {
            Phase.WORKING -> max(0.0, ivs.last().endSec - nowSec)
            Phase.RESTING -> ivs.firstOrNull { nowSec < it.startSec }?.let { it.startSec - nowSec } ?: 0.0
            Phase.BEFORE_WORK -> ivs.first().startSec - nowSec
            else -> 0.0
        }
    }
}

data class MonthSummary(
    val workdays: Int = 0,
    val expectedMonth: Double = 0.0,
    val leaveDeduction: Double = 0.0,
    val overtimeFee: Double = 0.0,
    val leaveDays: Double = 0.0,
    val leaveDeductDays: Double = 0.0,
    val overtimeHours: Double = 0.0,
)

/**
 * 某一时刻的完整收入计算。所有金额都由“当前时间 + 设置 + 记账数据”即时算出，不存累计值，
 * 所以应用被杀、手机锁屏后再打开金额依然正确。
 *
 * @param today 今天的日期
 * @param nowSec 此刻距今天 0 点的秒数（可带小数）
 */
class EarningsCalc(
    val settings: Settings,
    val ledger: Ledger,
    val today: LocalDate,
    val nowSec: Double,
) {
    private val startDay = Cycle.normalize(settings.cycleStartDay)
    private val todayMonth = Cycle.periodOf(today, startDay)

    // ── 工作日 ──

    /** 自然月的工作日安排（按日期存储，与计费周期无关）。 */
    fun workdays(ym: YearMonth): Set<Int> = ledger.plans[ym.key()] ?: ym.defaultWorkdays()
    fun isWorkday(date: LocalDate): Boolean = workdays(YearMonth.from(date)).contains(date.dayOfMonth)

    // ── 计费周期（ym = 结束日所在月） ──
    fun periodOf(date: LocalDate): YearMonth = Cycle.periodOf(date, startDay)
    fun periodDates(ym: YearMonth): List<LocalDate> = Cycle.dates(ym, startDay)
    fun workdayCount(ym: YearMonth): Int = periodDates(ym).count { isWorkday(it) }

    // ── 速率 ──

    val workSeconds: Int get() = DayTime.workSeconds(settings.segments)

    fun dailyIncome(ym: YearMonth): Double {
        val c = workdayCount(ym)
        return if (c > 0) settings.monthlySalary / c else 0.0
    }

    fun hourlyIncome(ym: YearMonth): Double {
        val hours = workSeconds / 3600.0
        return if (hours > 0) dailyIncome(ym) / hours else 0.0
    }

    fun perSecondIncome(ym: YearMonth): Double = hourlyIncome(ym) / 3600.0

    // ── 今天的状态 ──

    val todayIsWorkday: Boolean get() = isWorkday(today)
    val todayRecord: DayRecord? get() = ledger.records[today.key()]
    val elapsedWorkSeconds: Double get() = DayTime.elapsedWorkSeconds(settings.segments, nowSec)
    val remainingSeconds: Double get() = DayTime.remainingSeconds(settings.segments, nowSec)
    val progress: Double get() = if (workSeconds > 0) elapsedWorkSeconds / workSeconds else 0.0
    val todayProgress: Double get() = if (todayIsWorkday) progress else 0.0
    val phase: Phase get() = if (todayIsWorkday) DayTime.phase(settings.segments, nowSec) else Phase.DAY_OFF

    /** 今天按时间累计的基础收入（未考虑请假）。 */
    val earnedTodayRaw: Double get() = perSecondIncome(todayMonth) * elapsedWorkSeconds

    // ── 单日收入 ──

    private fun beforeStart(date: LocalDate): Boolean {
        val s = ledger.startDate
        return s != null && date.isBefore(s)
    }

    /**
     * 单日基础收入：
     * - 未来的日子、休息日、早于起算日的日子 = 0
     * - 扣钱的整天假 = 0；扣钱的半天假 = 当天基础收入最多到日薪的一半
     * - 其余：今天 = 实时累计，过去的日子 = 整天日薪
     */
    fun dayBase(date: LocalDate): Double {
        if (date.isAfter(today) || beforeStart(date)) return 0.0
        if (!isWorkday(date)) return 0.0
        val daily = dailyIncome(periodOf(date))
        val isToday = date == today
        val leave = ledger.records[date.key()]?.leave
        if (leave != null && leave.deduct) {
            if (!leave.half) return 0.0
            val cap = daily * 0.5
            return if (isToday) min(earnedTodayRaw, cap) else cap
        }
        return if (isToday) earnedTodayRaw else daily
    }

    /** 加班费 = 时薪 × 加班小时 × 倍率；只有“加钱”才有，休息日加班也计。 */
    fun overtimeFee(date: LocalDate): Double {
        if (beforeStart(date)) return 0.0
        val ot = ledger.records[date.key()]?.overtime ?: return 0.0
        if (!ot.paid) return 0.0
        return hourlyIncome(periodOf(date)) * ot.hours * ot.multiplier
    }

    fun dayEarned(date: LocalDate): Double = dayBase(date) + overtimeFee(date)

    /** 首页大字金额。 */
    val displayEarnedToday: Double get() = dayEarned(today)

    // ── 月度 ──

    /** 周期已挣：周期起算日至今所有日子的基础收入 + 加班费。 */
    fun monthEarnings(ym: YearMonth): Double = periodDates(ym).sumOf { dayEarned(it) }

    fun monthRecords(ym: YearMonth): List<Pair<LocalDate, DayRecord>> =
        periodDates(ym).mapNotNull { d -> ledger.records[d.key()]?.let { d to it } }

    /** 周期内已过的工作日数（统计到今天，历史周期统计到周期末）。 */
    fun elapsedWorkdays(ym: YearMonth): Int =
        periodDates(ym).count { !it.isAfter(today) && isWorkday(it) }

    /** 一条记录对当期收入的净影响：扣钱请假为负（仅工作日），加钱加班为正。 */
    fun recordImpact(date: LocalDate): Double {
        if (beforeStart(date)) return 0.0
        val rec = ledger.records[date.key()]
        var delta = 0.0
        val leave = rec?.leave
        if (leave != null && leave.deduct && isWorkday(date)) delta -= dailyIncome(periodOf(date)) * leave.factor
        delta += overtimeFee(date)
        return delta
    }

    fun recordImpact(ym: YearMonth, day: Int): Double = recordImpact(ym.atDay(day))

    fun monthSummary(ym: YearMonth): MonthSummary {
        val dates = periodDates(ym)
        val daily = dailyIncome(ym)
        val wdCount = dates.count { isWorkday(it) }
        val counted = dates.count { isWorkday(it) && !beforeStart(it) }
        var expected = daily * counted
        var leaveDays = 0.0
        var leaveDeductDays = 0.0
        var leaveDeduction = 0.0
        var overtimeHours = 0.0
        var overtimeFee = 0.0
        for (date in dates) {
            if (beforeStart(date)) continue
            val rec = ledger.records[date.key()] ?: continue
            val leave = rec.leave
            if (leave != null && isWorkday(date)) {
                leaveDays += leave.factor
                if (leave.deduct) {
                    leaveDeductDays += leave.factor
                    leaveDeduction += daily * leave.factor
                    expected -= daily * leave.factor
                }
            }
            val ot = rec.overtime
            if (ot != null) {
                overtimeHours += ot.hours
                if (ot.paid) {
                    val fee = overtimeFee(date)
                    overtimeFee += fee
                    expected += fee
                }
            }
        }
        return MonthSummary(wdCount, expected, leaveDeduction, overtimeFee, leaveDays, leaveDeductDays, overtimeHours)
    }
}

// ───────────────────────── 展示格式 ─────────────────────────

object Fmt {
    fun money(v: Double, fraction: Int = 2): String = String.format(Locale.CHINA, "%,.${fraction}f", v)

    fun time(sec: Int): String = String.format(Locale.CHINA, "%02d:%02d", sec / 3600, (sec % 3600) / 60)

    fun minutesText(minutes: Int): String = String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)

    fun duration(seconds: Double): String {
        val s = max(0, seconds.toInt())
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分钟"
            else -> "不到 1 分钟"
        }
    }

    /** 时间段时长文字，例如 "3 小时"、"1 小时 30 分"。 */
    fun durationMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分"
            h > 0 -> "$h 小时"
            else -> "$m 分钟"
        }
    }

    private val weekdayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    fun weekday(date: LocalDate): String = weekdayNames[date.dayOfWeek.value - 1]

    fun hours(h: Double): String = String.format(Locale.CHINA, "%.1f", h)
}
