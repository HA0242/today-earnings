package local.ha.todayearnings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.DayRecord
import local.ha.todayearnings.core.EarningsCalc
import local.ha.todayearnings.core.Fmt
import local.ha.todayearnings.core.LeaveRecord
import local.ha.todayearnings.core.OvertimeRecord
import local.ha.todayearnings.core.key
import local.ha.todayearnings.core.leadingBlanks
import local.ha.todayearnings.core.title
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    data: AppData,
    calc: EarningsCalc,
    onToggleWorkday: (YearMonth, Int) -> Unit,
    onSetLeave: (LocalDate, LeaveRecord?) -> Unit,
    onSetOvertime: (LocalDate, OvertimeRecord?) -> Unit,
) {
    val today = calc.today
    val current = YearMonth.from(today)
    val maxMonth = current.plusMonths(1) // 只开放到下个月，用来提前调整大小周
    var shownIndex by rememberSaveable { mutableStateOf(current.year * 12 + current.monthValue - 1) }
    val shown = YearMonth.of(shownIndex / 12, shownIndex % 12 + 1)
    var editingDay by rememberSaveable { mutableStateOf<Int?>(null) }
    val canGoNext = shown.isBefore(maxMonth)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 月份切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { shownIndex -= 1; editingDay = null }) { Text("‹", fontSize = 26.sp) }
            Text(shown.title(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp), textAlign = TextAlign.Center)
            TextButton(onClick = { shownIndex += 1; editingDay = null }, enabled = canGoNext) { Text("›", fontSize = 26.sp) }
        }

        SummaryCard(calc, shown)
        MonthGrid(data, calc, shown, onDayClick = { editingDay = it })
        Legend()
    }

    val day = editingDay
    if (day != null && day in 1..shown.lengthOfMonth()) {
        ModalBottomSheet(
            onDismissRequest = { editingDay = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DayEditor(
                calc = calc,
                ym = shown,
                day = day,
                onToggleWorkday = { onToggleWorkday(shown, day) },
                onSetLeave = { onSetLeave(shown.atDay(day), it) },
                onSetOvertime = { onSetOvertime(shown.atDay(day), it) },
            )
        }
    }
}

@Composable
private fun SummaryCard(calc: EarningsCalc, ym: YearMonth) {
    val s = calc.monthSummary(ym)
    Card(padding = 18.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("本月已挣", fontSize = 12.sp, letterSpacing = 1.5.sp, color = mutedColor())
            Text(
                "¥${Fmt.money(calc.monthEarnings(ym))}",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = DS.accent,
                style = TabularNums,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            SummaryItem("工作日", "${s.workdays} 天", secondaryColor(), Modifier.weight(1f))
            SummaryItem("预计全月", "¥${Fmt.money(s.expectedMonth)}", secondaryColor(), Modifier.weight(1.3f))
            SummaryItem(
                "请假扣款",
                if (s.leaveDeduction > 0) "-¥${Fmt.money(s.leaveDeduction)}" else "—",
                if (s.leaveDeduction > 0) DS.negative else mutedColor(),
                Modifier.weight(1.1f),
            )
            SummaryItem(
                "加班费",
                if (s.overtimeFee > 0) "+¥${Fmt.money(s.overtimeFee)}" else "—",
                if (s.overtimeFee > 0) DS.positive else mutedColor(),
                Modifier.weight(1.1f),
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = mutedColor())
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, style = TabularNums, maxLines = 1)
    }
}

@Composable
private fun MonthGrid(data: AppData, calc: EarningsCalc, ym: YearMonth, onDayClick: (Int) -> Unit) {
    val today = calc.today
    val blanks = ym.leadingBlanks()
    val cells: List<Int?> = List(blanks) { null } + (1..ym.lengthOfMonth()).map { it }
    val rows = cells.chunked(7)
    Card(padding = 12.dp) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = mutedColor())
            }
        }
        Spacer(Modifier.height(8.dp))
        rows.forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    if (d == null) {
                        Box(Modifier.weight(1f).height(54.dp))
                    } else {
                        DayCell(
                            calc = calc,
                            record = data.activeRole.ledger.records[ym.atDay(d).key()],
                            date = ym.atDay(d),
                            isToday = ym.atDay(d) == today,
                            modifier = Modifier.weight(1f),
                            onClick = { onDayClick(d) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    calc: EarningsCalc,
    record: DayRecord?,
    date: LocalDate,
    isToday: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val isWorkday = calc.isWorkday(date)
    val leave = record?.leave
    val overtime = record?.overtime
    val dim = if (date.isAfter(calc.today)) 0.55f else 1f
    // 框 = 这天挣钱；实心 = 这一项有钱，空心 = 没钱
    val hasBasePay = isWorkday && leave?.deduct != true
    val hasOvertimePay = overtime?.paid == true
    val showBox = hasBasePay || hasOvertimePay
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        isToday -> DS.accent
        showBox -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .height(54.dp)
            .background(if (showBox) MaterialTheme.colorScheme.background.copy(alpha = 0.6f) else Color.Transparent, shape)
            .border(if (isToday) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            fontSize = 15.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isWorkday) DS.accent.copy(alpha = dim) else mutedColor(),
            style = TabularNums,
        )
        Row(Modifier.height(14.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isWorkday && leave == null) Badge("班", DS.accent, filled = true, alpha = dim)
            if (leave != null) Badge(if (leave.half) "半假" else "假", DS.negative, filled = !leave.deduct, alpha = dim)
            if (overtime != null) Badge("加", DS.positive, filled = overtime.paid, alpha = dim)
        }
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(13.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)))
                Text("挣钱", fontSize = 11.sp, color = mutedColor())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge("班", DS.accent, true); Text("上班", fontSize = 11.sp, color = mutedColor())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge("假", DS.negative, true); Text("请假", fontSize = 11.sp, color = mutedColor())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge("加", DS.positive, true); Text("加班", fontSize = 11.sp, color = mutedColor())
            }
        }
        Text("实心 = 有钱 · 空心 = 没钱。点击日期可编辑上班 / 请假 / 加班；可以翻到下个月提前调整大小周。", fontSize = 11.sp, color = mutedColor())
    }
}

// ───────────────────────── 单日编辑 ─────────────────────────

@Composable
private fun DayEditor(
    calc: EarningsCalc,
    ym: YearMonth,
    day: Int,
    onToggleWorkday: () -> Unit,
    onSetLeave: (LeaveRecord?) -> Unit,
    onSetOvertime: (OvertimeRecord?) -> Unit,
) {
    val date = ym.atDay(day)
    val isWorkday = calc.isWorkday(date)
    val record = calc.ledger.records[date.key()]
    val leave = record?.leave
    val overtime = record?.overtime

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("${ym.monthValue}月${day}日 · ${Fmt.weekday(date)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (isWorkday) "工作日 · 日薪 ¥${Fmt.money(calc.dailyIncome(ym))}" else "休息日 · 不计薪",
            fontSize = 13.sp,
            color = secondaryColor(),
        )

        // 这天上班
        SwitchRow("这天上班", isWorkday) { onToggleWorkday() }
        Text(
            "提示：修改工作日会改变当月的日薪（月薪 ÷ 工作日数），本月已过去的日子也会跟着变。",
            fontSize = 11.sp,
            color = mutedColor(),
        )
        HorizontalDivider()

        // 请假：休息日不能记请假（已有记录时仍显示，方便删除）
        if (isWorkday || leave != null) {
            SwitchRow("请假", leave != null) { on -> onSetLeave(if (on) LeaveRecord() else null) }
            if (leave != null) {
                ChipRow(
                    options = listOf("整天", "半天"),
                    selected = if (leave.half) 1 else 0,
                    onSelect = { onSetLeave(leave.copy(half = it == 1)) },
                )
                ChipRow(
                    options = listOf("扣钱", "不扣钱"),
                    selected = if (leave.deduct) 0 else 1,
                    onSelect = { onSetLeave(leave.copy(deduct = it == 0)) },
                )
                if (leave.deduct) {
                    val cut = calc.dailyIncome(ym) * leave.factor
                    Text("扣款 -¥${Fmt.money(cut)}（${if (leave.half) "半天，日薪的一半" else "整天日薪"}）", fontSize = 12.sp, color = DS.negative)
                }
                NoteField("请假备注（可选）", leave.note) { onSetLeave(leave.copy(note = it)) }
            }
            if (!isWorkday) {
                Text("这天是休息日，请假不会扣钱，可以关掉。", fontSize = 11.sp, color = mutedColor())
            }
            HorizontalDivider()
        } else {
            Text("休息日不能记请假。", fontSize = 12.sp, color = mutedColor())
            HorizontalDivider()
        }

        // 加班
        SwitchRow("加班", overtime != null) { on -> onSetOvertime(if (on) OvertimeRecord() else null) }
        if (overtime != null) {
            ChipRow(
                options = listOf("加钱", "不加钱"),
                selected = if (overtime.paid) 0 else 1,
                onSelect = { onSetOvertime(overtime.copy(paid = it == 0)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("时长", fontSize = 14.sp, color = secondaryColor())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onSetOvertime(overtime.copy(hours = (overtime.hours - 0.5).coerceAtLeast(0.5))) },
                        enabled = overtime.hours > 0.5,
                    ) { Text("−", fontSize = 20.sp) }
                    Text("${Fmt.hours(overtime.hours)} 小时", fontSize = 15.sp, style = TabularNums, modifier = Modifier.width(76.dp), textAlign = TextAlign.Center)
                    TextButton(
                        onClick = { onSetOvertime(overtime.copy(hours = (overtime.hours + 0.5).coerceAtMost(24.0))) },
                        enabled = overtime.hours < 24.0,
                    ) { Text("+", fontSize = 20.sp) }
                }
            }
            if (overtime.paid) {
                ChipRow(
                    options = listOf("1 倍", "1.5 倍", "2 倍", "3 倍"),
                    selected = listOf(1.0, 1.5, 2.0, 3.0).indexOf(overtime.multiplier).coerceAtLeast(0),
                    onSelect = { onSetOvertime(overtime.copy(multiplier = listOf(1.0, 1.5, 2.0, 3.0)[it])) },
                )
                val fee = calc.hourlyIncome(ym) * overtime.hours * overtime.multiplier
                Text("加班费 +¥${Fmt.money(fee)}", fontSize = 12.sp, color = DS.positive)
            }
            NoteField("加班备注（可选）", overtime.note) { onSetOvertime(overtime.copy(note = it)) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}

@Composable
private fun NoteField(placeholder: String, value: String, onChange: (String) -> Unit) {
    // 本地保存输入内容，避免每次写盘后光标跳动
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
