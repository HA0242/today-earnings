package local.ha.todayearnings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.DayTime
import local.ha.todayearnings.core.Cycle
import local.ha.todayearnings.core.EarningsCalc
import local.ha.todayearnings.core.Fmt
import local.ha.todayearnings.core.Phase
import local.ha.todayearnings.core.UpdateMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

@Composable
fun HomeScreen(
    data: AppData,
    now: ZonedDateTime,
    calc: EarningsCalc,
    onSwitchRole: (String) -> Unit,
    onSetStartDate: (LocalDate) -> Unit,
) {
    val role = data.activeRole
    val today = calc.today
    val ym = calc.periodOf(today)

    // 首次使用：问“本月已挣”从哪天算起。恰好是 1 号就不用问。
    if (role.ledger.startDate == null) {
        if (today.dayOfMonth == 1) {
            LaunchedEffect(role.id) { onSetStartDate(today) }
        } else {
            StartDateDialog(today = today, onPick = onSetStartDate)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RoleSwitcher(data, onSwitchRole)
        Hero(calc)
        ProgressBlock(calc)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RateCard("日薪", calc.dailyIncome(ym), 2, Modifier.weight(1f))
            RateCard("时薪", calc.hourlyIncome(ym), 2, Modifier.weight(1f))
            RateCard("每秒", calc.perSecondIncome(ym), 4, Modifier.weight(1f))
        }
        MonthRow(calc, ym)
        if (role.settings.updateMode == UpdateMode.EVERY_HOUR) {
            val next = role.settings.updateMode.nextFire(now)
            Text(
                "整点模式 · 下次刷新 ${String.format("%02d:%02d", next.hour, next.minute)}",
                fontSize = 11.sp,
                color = mutedColor(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StartDateDialog(today: LocalDate, onPick: (LocalDate) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("从哪天开始计入收入？") },
        text = {
            Text(
                "今天是 ${today.monthValue} 月 ${today.dayOfMonth} 日，不是月初。" +
                    "请选择“本月已挣”从哪天开始算；之后可以在设置里修改。",
            )
        },
        confirmButton = {
            TextButton(onClick = { onPick(today.withDayOfMonth(1)) }) { Text("从本月 1 日算") }
        },
        dismissButton = {
            TextButton(onClick = { onPick(today) }) { Text("从今天算") }
        },
    )
}

@Composable
fun RoleSwitcher(data: AppData, onSwitchRole: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = true }, enabled = data.roles.size > 1) {
            Text(
                data.activeRole.name + if (data.roles.size > 1) " ▾" else "",
                color = secondaryColor(),
                fontSize = 13.sp,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            data.roles.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.name + if (r.id == data.activeRoleId) "  ✓" else "") },
                    onClick = {
                        open = false
                        onSwitchRole(r.id)
                    },
                )
            }
        }
    }
}

private fun phaseTitle(c: EarningsCalc): String {
    if (!c.todayIsWorkday) return "今天不上班 · 好好休息"
    c.todayRecord?.leave?.let { lv ->
        val kind = if (lv.half) "今日半天假" else "今日请假"
        return "$kind · " + if (lv.deduct) "扣薪" else "带薪"
    }
    return when (c.phase) {
        Phase.NO_WORK -> "今天还没有安排上班时间段"
        Phase.BEFORE_WORK -> "还没到上班时间"
        Phase.WORKING -> "正在赚钱中"
        Phase.RESTING -> "休息中收入暂停"
        Phase.AFTER_WORK -> "今日收入已到账"
        Phase.DAY_OFF -> "今天不上班"
    }
}

private fun phaseDetail(c: EarningsCalc): String {
    val fee = c.overtimeFee(c.today)
    val extra = if (fee > 0) " · 已含加班费 +¥${Fmt.money(fee)}" else ""
    if (!c.todayIsWorkday) return "今天不在本月工作日历中，收入已暂停$extra"
    c.todayRecord?.leave?.let { lv ->
        val note = if (lv.note.isEmpty()) "" else "备注：${lv.note}"
        return when {
            lv.deduct && lv.half -> "半天假 · 当天收入最多到日薪的一半" + (if (note.isEmpty()) "" else " · $note") + extra
            lv.deduct -> (if (note.isEmpty()) "今天收入已暂停 · 记一笔加班仍会计入" else note) + extra
            else -> "带薪假 · " + (if (note.isEmpty()) "收入照常累计" else note) + extra
        }
    }
    return when (c.phase) {
        Phase.NO_WORK -> "到设置里添加上班时间段"
        Phase.BEFORE_WORK -> "${Fmt.duration(c.remainingSeconds)} 后开始上班$extra"
        Phase.WORKING -> "已工作 ${Fmt.duration(c.elapsedWorkSeconds)} · 距下班还有 ${Fmt.duration(c.remainingSeconds)}$extra"
        Phase.RESTING -> "${Fmt.duration(c.remainingSeconds)} 后继续开工 · 已工作 ${Fmt.duration(c.elapsedWorkSeconds)}$extra"
        Phase.AFTER_WORK -> "今日共工作 ${Fmt.duration(c.workSeconds.toDouble())} · 日薪已全部到账$extra"
        Phase.DAY_OFF -> "今天不上班"
    }
}

@Composable
private fun Hero(c: EarningsCalc) {
    val dotColor = when {
        c.todayRecord?.leave != null -> mutedColor()
        c.phase == Phase.WORKING -> DS.positive
        c.phase == Phase.RESTING -> DS.accent
        else -> mutedColor()
    }
    val amount = Fmt.money(c.displayEarnedToday)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusDot(dotColor)
            Text(phaseTitle(c), fontSize = 12.sp, letterSpacing = 1.5.sp, color = mutedColor())
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "¥",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = DS.accent.copy(alpha = 0.75f),
                modifier = Modifier.padding(end = 4.dp, bottom = 8.dp),
            )
            Text(
                amount,
                fontSize = if (amount.length > 9) 44.sp else 60.sp,
                fontWeight = FontWeight.Black,
                color = DS.accent,
                style = TabularNums,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        Text(phaseDetail(c), fontSize = 13.sp, color = secondaryColor(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProgressBlock(c: EarningsCalc) {
    val intervals = DayTime.mergedIntervals(c.settings.segments)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { c.todayProgress.coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = DS.accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("今日进度", fontSize = 11.sp, color = mutedColor())
                Text("${Math.round(c.todayProgress * 100)}%", fontSize = 11.sp, color = secondaryColor(), style = TabularNums)
            }
            Text(
                if (intervals.isEmpty()) "未设置上班时间段"
                else intervals.joinToString(" · ") { "${Fmt.time(it.startSec)} – ${Fmt.time(it.endSec)}" },
                fontSize = 11.sp,
                color = mutedColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun RateCard(title: String, value: Double, fraction: Int, modifier: Modifier) {
    Card(modifier = modifier, padding = 14.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 11.sp, letterSpacing = 1.5.sp, color = mutedColor())
            Text(
                "¥${Fmt.money(value, fraction)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                style = TabularNums,
                maxLines = 1,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun MonthRow(c: EarningsCalc, ym: YearMonth) {
    Card(padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (c.settings.cycleStartDay > 1) "本期已挣" else "本月已挣", fontSize = 12.sp, letterSpacing = 1.5.sp, color = mutedColor())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "¥${Fmt.money(c.monthEarnings(ym))}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = DS.accent,
                    style = TabularNums,
                )
                Text("· ${Cycle.rangeText(ym, c.settings.cycleStartDay) ?: "本月"}工作日 ${c.workdayCount(ym)} 天", fontSize = 11.sp, color = mutedColor(), style = TabularNums)
            }
        }
    }
}
