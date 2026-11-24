package local.ha.todayearnings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ha.todayearnings.core.DayRecord
import local.ha.todayearnings.core.EarningsCalc
import local.ha.todayearnings.core.Fmt
import local.ha.todayearnings.core.title
import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.Cycle
import java.time.LocalDate
import java.time.YearMonth

private fun daysText(d: Double): String =
    if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.1f", d)

@Composable
fun StatsScreen(data: AppData, calc: EarningsCalc, onSwitchRole: (String) -> Unit) {
    val today = calc.today
    val current = calc.periodOf(today)
    val startDay = calc.settings.cycleStartDay
    var shownIndex by rememberSaveable { mutableStateOf(current.year * 12 + current.monthValue - 1) }
    val shown = YearMonth.of(shownIndex / 12, shownIndex % 12 + 1)
    val isCurrent = shown == current
    val canGoNext = shown.isBefore(current) // 统计只到当前月
    val s = calc.monthSummary(shown)
    val records = calc.monthRecords(shown)
    val elapsedWorkdays = calc.elapsedWorkdays(shown)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RoleSwitcher(data, onSwitchRole)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { shownIndex -= 1 }) { Text("‹", fontSize = 26.sp) }
            Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${shown.title()} 挣钱报告", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Cycle.rangeText(shown, startDay)?.let { Text(it, fontSize = 11.sp, color = mutedColor()) }
            }
            TextButton(onClick = { shownIndex += 1 }, enabled = canGoNext) { Text("›", fontSize = 26.sp) }
        }

        // 月度累计
        Card(padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("¥", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DS.accent.copy(alpha = 0.75f), modifier = Modifier.padding(end = 4.dp, bottom = 6.dp))
                    Text(Fmt.money(calc.monthEarnings(shown)), fontSize = 44.sp, fontWeight = FontWeight.Black, color = DS.accent, style = TabularNums, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("预计全月 ¥${Fmt.money(s.expectedMonth)}", fontSize = 11.sp, color = secondaryColor(), style = TabularNums)
                    Text("工作日 ${s.workdays} 天", fontSize = 11.sp, color = secondaryColor(), style = TabularNums)
                }
            }
        }

        // 三张汇总卡
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryCard(
                "工作日", "${s.workdays} 天",
                if (isCurrent) "已过 $elapsedWorkdays 天" else null,
                Modifier.weight(1f),
            )
            SummaryCard(
                "请假", "${daysText(s.leaveDays)} 天",
                if (s.leaveDays > 0) "扣薪 ${daysText(s.leaveDeductDays)} · 带薪 ${daysText(s.leaveDays - s.leaveDeductDays)}" else null,
                Modifier.weight(1f),
            )
            SummaryCard(
                "加班", "${Fmt.hours(s.overtimeHours)} 小时",
                if (s.overtimeFee > 0) "+¥${Fmt.money(s.overtimeFee)}" else null,
                Modifier.weight(1f),
            )
        }

        // 当月记录明细
        if (records.isNotEmpty()) {
            Card(padding = 16.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CardTitle("当月记录")
                    Text("${records.size} 条", fontSize = 11.sp, color = mutedColor())
                }
                records.forEachIndexed { i, (date, rec) ->
                    RecordRow(calc, date, rec, isToday = date == today)
                    if (i != records.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, sub: String?, modifier: Modifier) {
    Card(modifier = modifier, padding = 12.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = mutedColor())
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, style = TabularNums, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            if (sub != null) {
                Text(sub, fontSize = 10.sp, color = mutedColor(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RecordRow(calc: EarningsCalc, date: LocalDate, rec: DayRecord, isToday: Boolean) {
    val impact = calc.recordImpact(date)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${date.monthValue}月${date.dayOfMonth}日 · ${Fmt.weekday(date)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = TabularNums)
                if (isToday) Tag("今天", DS.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                rec.leave?.let { lv ->
                    val kind = if (lv.half) "半天假" else "请假"
                    Tag("$kind · ${if (lv.deduct) "扣薪" else "带薪"}", if (lv.deduct) DS.negative else DS.accent)
                }
                rec.overtime?.let { ot ->
                    val mult = if (ot.multiplier != 1.0) " ×${ot.multiplier}" else ""
                    Tag("加班 ${Fmt.hours(ot.hours)} 小时$mult${if (ot.paid) "" else " · 不加钱"}", DS.positive)
                }
            }
            val note = listOfNotNull(rec.leave?.note, rec.overtime?.note).filter { it.isNotEmpty() }.joinToString(" / ")
            if (note.isNotEmpty()) Text(note, fontSize = 11.sp, color = mutedColor(), maxLines = 1)
        }
        when {
            impact > 0.005 -> Text("+¥${Fmt.money(impact)}", color = DS.positive, fontWeight = FontWeight.Bold, fontSize = 14.sp, style = TabularNums)
            impact < -0.005 -> Text("-¥${Fmt.money(-impact)}", color = DS.negative, fontWeight = FontWeight.Bold, fontSize = 14.sp, style = TabularNums)
            else -> Text(if (rec.leave != null) "不扣钱" else "不加钱", color = mutedColor(), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.13f)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}
