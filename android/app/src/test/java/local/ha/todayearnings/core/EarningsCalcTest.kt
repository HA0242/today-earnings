package local.ha.todayearnings.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** 对应需求文档第 8.2 节的验收用例。 */
class EarningsCalcTest {

    private val nov = YearMonth.of(2026, 11)
    private val day = LocalDate.of(2026, 11, 10) // 周二
    private val eps = 0.005

    /** 11 月上班 22 天（1~22 号），便于与文档里的 22 天口径对齐。 */
    private fun plan22(): Map<String, Set<Int>> = mapOf("2026-11" to (1..22).toSet())

    private fun calc(
        h: Int, m: Int = 0, s: Int = 0,
        ledger: Ledger = Ledger(plans = plan22()),
        settings: Settings = Settings(),
        today: LocalDate = day,
    ) = EarningsCalc(settings, ledger, today, (h * 3600 + m * 60 + s).toDouble())

    // 1. 基准用例
    @Test fun baseline_rates_and_earned() {
        val c = calc(15, 30)
        assertEquals(8 * 3600, c.workSeconds)
        assertEquals(454.545, c.dailyIncome(nov), 0.001)
        assertEquals(56.818, c.hourlyIncome(nov), 0.001)
        assertEquals(0.015783, c.perSecondIncome(nov), 0.000001)
        assertEquals(5.5 * 3600, c.elapsedWorkSeconds, 0.001)
        assertEquals(312.50, c.displayEarnedToday, eps)
        assertEquals(Phase.WORKING, c.phase)
    }

    // 2. 午休
    @Test fun lunch_break_pauses() {
        val noon = calc(12, 30)
        assertEquals(Phase.RESTING, noon.phase)
        assertEquals(3 * 3600.0, noon.elapsedWorkSeconds, 0.001)
        assertEquals(1800.0, noon.remainingSeconds, 0.001)
        val later = calc(12, 59, 59)
        assertEquals(noon.displayEarnedToday, later.displayEarnedToday, 0.001)
    }

    // 3. 已下班
    @Test fun after_work_earns_full_day() {
        val c = calc(18, 30)
        assertEquals(Phase.AFTER_WORK, c.phase)
        assertEquals(c.dailyIncome(nov), c.displayEarnedToday, eps)
        assertEquals(1.0, c.progress, 1e-9)
    }

    @Test fun before_work_status() {
        val c = calc(8, 0)
        assertEquals(Phase.BEFORE_WORK, c.phase)
        assertEquals(3600.0, c.remainingSeconds, 0.001)
        assertEquals(0.0, c.displayEarnedToday, 1e-9)
    }

    // 4. 休息日
    @Test fun rest_day_is_zero() {
        val c = calc(15, 0, today = LocalDate.of(2026, 11, 28)) // 28 号不在 1~22 里
        assertFalse(c.todayIsWorkday)
        assertEquals(Phase.DAY_OFF, c.phase)
        assertEquals(0.0, c.todayProgress, 1e-9)
        assertEquals(0.0, c.displayEarnedToday, 1e-9)
    }

    // 5. 整天扣钱请假
    @Test fun full_day_deduct_leave() {
        val l = Ledger(plans = plan22(), records = mapOf("2026-11-10" to DayRecord(leave = LeaveRecord(deduct = true))))
        val c = calc(15, 30, ledger = l)
        assertEquals(0.0, c.displayEarnedToday, 1e-9)
        val s = c.monthSummary(nov)
        assertEquals(454.545, s.leaveDeduction, 0.001)
        assertEquals(1.0, s.leaveDays, 1e-9)
        assertEquals(1.0, s.leaveDeductDays, 1e-9)
        assertEquals(-454.545, c.recordImpact(nov, 10), 0.001)
    }

    @Test fun paid_leave_keeps_pay() {
        val l = Ledger(plans = plan22(), records = mapOf("2026-11-10" to DayRecord(leave = LeaveRecord(deduct = false))))
        val c = calc(18, 30, ledger = l)
        assertEquals(c.dailyIncome(nov), c.displayEarnedToday, eps)
        assertEquals(0.0, c.monthSummary(nov).leaveDeduction, 1e-9)
    }

    // 6. 加班
    @Test fun overtime_paid_counts_even_on_rest_day() {
        val ot = DayRecord(overtime = OvertimeRecord(paid = true, hours = 2.0))
        val restDay = LocalDate.of(2026, 11, 28)
        val l = Ledger(plans = plan22(), records = mapOf(restDay.key() to ot))
        val c = calc(10, 0, ledger = l, today = restDay)
        assertEquals(113.64, c.displayEarnedToday, eps)
    }

    @Test fun overtime_not_paid_gives_nothing() {
        val ot = DayRecord(overtime = OvertimeRecord(paid = false, hours = 3.0))
        val l = Ledger(plans = plan22(), records = mapOf("2026-11-10" to ot))
        val c = calc(18, 30, ledger = l)
        assertEquals(c.dailyIncome(nov), c.displayEarnedToday, eps)
        assertEquals(3.0, c.monthSummary(nov).overtimeHours, 1e-9)
        assertEquals(0.0, c.monthSummary(nov).overtimeFee, 1e-9)
    }

    @Test fun overtime_multiplier() {
        val ot = DayRecord(overtime = OvertimeRecord(paid = true, hours = 2.0, multiplier = 1.5))
        val l = Ledger(plans = plan22(), records = mapOf("2026-11-10" to ot))
        val c = calc(18, 30, ledger = l)
        assertEquals(c.dailyIncome(nov) + 113.636 * 1.5, c.displayEarnedToday, eps)
    }

    // 8. 大小周：下个月不复制本月，默认周一至周五
    @Test fun new_month_defaults_to_weekdays_not_copy() {
        val c = calc(10)
        val dec = YearMonth.of(2026, 12)
        assertEquals(dec.defaultWorkdays(), c.workdays(dec))
        assertTrue(c.workdays(dec).none { dec.atDay(it).dayOfWeek.value >= 6 })
        assertEquals(21, YearMonth.of(2026, 11).defaultWorkdays().size)
        // 11 月自己存的计划不受影响
        assertEquals((1..22).toSet(), c.workdays(nov))
    }

    // 半天假
    @Test fun half_day_leave_deduct_past_day() {
        val l = Ledger(
            plans = plan22(),
            records = mapOf("2026-11-05" to DayRecord(leave = LeaveRecord(deduct = true, half = true))),
        )
        val c = calc(10, 0, ledger = l)
        assertEquals(227.27, c.dayBase(LocalDate.of(2026, 11, 5)), eps)
        val s = c.monthSummary(nov)
        assertEquals(227.27, s.leaveDeduction, eps)
        assertEquals(0.5, s.leaveDays, 1e-9)
        assertEquals(-227.27, c.recordImpact(nov, 5), eps)
    }

    @Test fun half_day_leave_today_is_capped_at_half() {
        val l = Ledger(plans = plan22(), records = mapOf("2026-11-10" to DayRecord(leave = LeaveRecord(deduct = true, half = true))))
        assertEquals(56.82, calc(10, 0, ledger = l).displayEarnedToday, eps)  // 1 小时，未到上限
        assertEquals(227.27, calc(15, 30, ledger = l).displayEarnedToday, eps) // 已超过一半，封顶
        assertEquals(227.27, calc(18, 30, ledger = l).displayEarnedToday, eps)
    }

    // 月度累计
    @Test fun month_earnings_counts_past_workdays_plus_today() {
        val c = calc(15, 30) // 11/10，计划 1~22 都是上班日
        val daily = c.dailyIncome(nov)
        assertEquals(9 * daily + 312.50, c.monthEarnings(nov), 0.01) // 1~9 号整天 + 今天
        assertEquals(10_000.0, c.monthSummary(nov).expectedMonth, 0.01)
    }

    @Test fun start_date_cuts_earlier_days() {
        val l = Ledger(startDate = day, plans = plan22())
        val c = calc(18, 30, ledger = l)
        assertEquals(c.dailyIncome(nov), c.monthEarnings(nov), 0.01) // 只有今天
        assertEquals(13 * c.dailyIncome(nov), c.monthSummary(nov).expectedMonth, 0.01) // 10~22 共 13 天
    }

    @Test fun future_month_has_no_earnings() {
        val c = calc(18, 30)
        assertEquals(0.0, c.monthEarnings(YearMonth.of(2026, 12)), 1e-9)
    }

    // 时间段
    @Test fun invalid_segment_is_ignored_not_24h() {
        val segs = listOf(WorkSegment(startMinutes = 9 * 60, endMinutes = 9 * 60))
        assertEquals(0, DayTime.workSeconds(segs))
        assertEquals(Phase.NO_WORK, DayTime.phase(segs, 10 * 3600.0))
        val c = calc(10, settings = Settings(segments = segs))
        assertEquals(0.0, c.hourlyIncome(nov), 1e-9)
    }

    @Test fun overlapping_segments_merge() {
        val segs = listOf(
            WorkSegment(startMinutes = 9 * 60, endMinutes = 12 * 60),
            WorkSegment(startMinutes = 11 * 60, endMinutes = 13 * 60),
            WorkSegment(startMinutes = 13 * 60, endMinutes = 15 * 60),
        )
        assertEquals(listOf(Interval(9 * 3600, 15 * 3600)), DayTime.mergedIntervals(segs))
    }

    @Test fun zero_workdays_gives_zero_income() {
        val l = Ledger(plans = mapOf("2026-11" to emptySet()))
        val c = calc(10, ledger = l)
        assertEquals(0.0, c.dailyIncome(nov), 1e-9)
        assertEquals(0.0, c.displayEarnedToday, 1e-9)
    }

    // 刷新对齐
    @Test fun update_mode_alignment() {
        val t = ZonedDateTime.of(2026, 11, 10, 15, 30, 42, 500_000_000, ZoneId.of("Asia/Shanghai"))
        assertEquals(43, UpdateMode.EVERY_SECOND.nextFire(t).second)
        assertEquals(31, UpdateMode.EVERY_MINUTE.nextFire(t).minute)
        assertEquals(0, UpdateMode.EVERY_MINUTE.nextFire(t).second)
        assertEquals(16, UpdateMode.EVERY_HOUR.nextFire(t).hour)
        assertEquals(0, UpdateMode.EVERY_HOUR.nextFire(t).minute)
    }

    // 数据规范化
    @Test fun app_data_normalizes_primary() {
        val a = Role(name = "A")
        val b = Role(name = "B")
        val n = AppData(listOf(a, b), "missing").normalized()
        assertEquals(1, n.roles.count { it.isPrimary })
        assertTrue(n.roles.first().isPrimary)
        assertEquals(a.id, n.activeRoleId)
        assertEquals(1, AppData(emptyList(), "x").normalized().roles.size)
    }

    // ── 计费周期（起算日 25：上月 25 日 ~ 本月 24 日算作本月，按结束日所在月命名） ──
    private val cyc25 = Settings(cycleStartDay = 25)

    @Test fun cycle_period_boundaries() {
        assertEquals(LocalDate.of(2026, 10, 25), Cycle.start(nov, 25))
        assertEquals(LocalDate.of(2026, 11, 24), Cycle.end(nov, 25))
        assertEquals(YearMonth.of(2026, 11), Cycle.periodOf(LocalDate.of(2026, 11, 24), 25))
        assertEquals(YearMonth.of(2026, 12), Cycle.periodOf(LocalDate.of(2026, 11, 25), 25))
        assertEquals(YearMonth.of(2027, 1), Cycle.periodOf(LocalDate.of(2026, 12, 31), 25))
        assertEquals(nov, Cycle.periodOf(LocalDate.of(2026, 11, 30), 1)) // 自然月
        assertEquals(30, Cycle.dates(nov, 25).size)
    }

    @Test fun cycle_workdays_and_daily_income_span_two_months() {
        // 10/26~11/24 默认周一至周五共 22 天
        val c = EarningsCalc(cyc25, Ledger(), day, 0.0)
        assertEquals(22, c.workdayCount(nov))
        assertEquals(10000.0 / 22, c.dailyIncome(nov), 1e-6)
    }

    @Test fun cycle_month_earnings_counts_from_previous_month() {
        // 11/10 零点：10/26~11/9 共 11 个工作日已过，今天还没上班
        val c = EarningsCalc(cyc25, Ledger(), day, 0.0)
        assertEquals(11 * 10000.0 / 22, c.monthEarnings(nov), 1e-6)
    }

    @Test fun cycle_leave_in_previous_month_belongs_to_this_period() {
        val rec = DayRecord(leave = LeaveRecord(deduct = true))
        val ledger = Ledger(records = mapOf("2026-10-28" to rec))
        val c = EarningsCalc(cyc25, ledger, day, 0.0)
        assertEquals(-10000.0 / 22, c.recordImpact(LocalDate.of(2026, 10, 28)), 1e-6)
        assertEquals(1, c.monthRecords(nov).size)
        assertEquals(1.0, c.monthSummary(nov).leaveDays, 1e-9)
    }

    @Test fun cycle_workday_plan_is_by_calendar_date() {
        // 改起算日不影响自然日期上的工作日安排
        val ledger = Ledger(plans = mapOf("2026-11" to setOf(2, 3)))
        val c = EarningsCalc(cyc25, ledger, day, 0.0)
        assertTrue(c.isWorkday(LocalDate.of(2026, 11, 2)))
        assertFalse(c.isWorkday(LocalDate.of(2026, 11, 4)))
    }
}
