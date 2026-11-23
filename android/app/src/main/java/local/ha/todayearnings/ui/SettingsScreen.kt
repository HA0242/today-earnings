package local.ha.todayearnings.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.EarningsCalc
import local.ha.todayearnings.core.Fmt
import local.ha.todayearnings.core.Role
import local.ha.todayearnings.core.UpdateMode
import local.ha.todayearnings.core.WorkSegment
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val SALARY_PATTERN = Regex("^\\d{0,8}(\\.\\d{0,2})?$")

private fun salaryToText(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.2f", v)

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    data: AppData,
    calc: EarningsCalc,
    onOpenCalendar: () -> Unit,
) {
    val ctx = LocalContext.current
    val role = data.activeRole

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        RolesCard(vm, data)
        IncomeCard(vm, role, calc, onOpenCalendar)
        SegmentsCard(vm, role, calc)
        RefreshCard(vm, role)
        BackupCard(vm, ctx)
        Text(
            "今天挣了多少钱 1.0.0 · 数据只保存在本机，不联网、不上传。",
            fontSize = 11.sp,
            color = mutedColor(),
        )
    }
}

// ───────────────────────── 角色 ─────────────────────────

@Composable
private fun RolesCard(vm: AppViewModel, data: AppData) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Role?>(null) }
    var deleting by remember { mutableStateOf<Role?>(null) }

    Card {
        CardTitle("角色")
        data.roles.forEach { role ->
            val active = role.id == data.activeRoleId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.switchRole(role.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(role.name, fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    val tags = listOfNotNull(if (active) "当前使用" else null, if (role.isPrimary) "主账户" else null)
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 11.sp, color = if (active) DS.accent else mutedColor())
                }
                TextButton(onClick = { renaming = role }) { Text("改名") }
                if (!role.isPrimary) {
                    TextButton(onClick = { deleting = role }) { Text("删除", color = DS.negative) }
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("＋ 添加角色")
        }
    }

    if (adding) {
        NameDialog("添加角色", "", "创建", onDismiss = { adding = false }) { vm.addRole(it); adding = false }
    }
    renaming?.let { r ->
        NameDialog("改名", r.name, "保存", onDismiss = { renaming = null }) { vm.renameRole(r.id, it); renaming = null }
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除角色「${r.name}」？") },
            text = { Text("该角色下的所有记账数据都会被永久删除，且无法恢复。建议先在下方导出备份。") },
            confirmButton = { TextButton(onClick = { vm.deleteRole(r.id); deleting = null }) { Text("删除", color = DS.negative) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 20) name = it },
                label = { Text("角色名称") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ───────────────────────── 收入参数 ─────────────────────────

@Composable
private fun IncomeCard(vm: AppViewModel, role: Role, calc: EarningsCalc, onOpenCalendar: () -> Unit) {
    val ctx = LocalContext.current
    val ym = YearMonth.from(calc.today)
    var salaryText by remember(role.id) { mutableStateOf(salaryToText(role.settings.monthlySalary)) }
    val parsed = salaryText.toDoubleOrNull()

    Card {
        CardTitle("收入参数")
        OutlinedTextField(
            value = salaryText,
            onValueChange = {
                if (SALARY_PATTERN.matches(it)) {
                    salaryText = it
                    it.toDoubleOrNull()?.let { v -> vm.setSalary(v) }
                }
            },
            label = { Text("月薪（元）") },
            singleLine = true,
            isError = parsed == null,
            supportingText = { if (parsed == null) Text("请输入月薪") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledRow("工作日历") {
            TextButton(onClick = onOpenCalendar) { Text("本月 ${calc.workdayCount(ym)} 天 · 去日历调整") }
        }
        LabeledRow("时薪") {
            Text("¥${Fmt.money(calc.hourlyIncome(ym))}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = secondaryColor())
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LabeledRow("收入起算日") {
            val start = role.ledger.startDate
            TextButton(onClick = {
                val base = start ?: calc.today
                DatePickerDialog(ctx, { _, y, m, d -> vm.setStartDate(LocalDate.of(y, m + 1, d)) }, base.year, base.monthValue - 1, base.dayOfMonth).show()
            }) { Text(start?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: "未设置") }
        }
        Text("早于起算日的日子不计入“本月已挣”。", fontSize = 11.sp, color = mutedColor())
    }
}

// ───────────────────────── 上班时间段 ─────────────────────────

@Composable
private fun SegmentsCard(vm: AppViewModel, role: Role, calc: EarningsCalc) {
    val ctx = LocalContext.current
    val segments = role.settings.segments

    fun pickTime(seg: WorkSegment, isStart: Boolean) {
        val initial = if (isStart) seg.startMinutes else seg.endMinutes
        TimePickerDialog(ctx, { _, h, m ->
            val mins = h * 60 + m
            val updated = if (isStart) seg.copy(startMinutes = mins) else seg.copy(endMinutes = mins)
            if (updated.isValid) {
                vm.setSegments(segments.map { if (it.id == seg.id) updated else it })
            } else {
                toast(ctx, "结束时间必须晚于开始时间")
            }
        }, initial / 60, initial % 60, true).show()
    }

    Card {
        CardTitle("上班时间段")
        segments.forEach { seg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime(seg, true) }) { Text(Fmt.minutesText(seg.startMinutes), style = TabularNums) }
                    Text("  →  ", color = mutedColor())
                    OutlinedButton(onClick = { pickTime(seg, false) }) { Text(Fmt.minutesText(seg.endMinutes), style = TabularNums) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.durationMinutes(seg.durationMinutes), fontSize = 11.sp, color = mutedColor())
                    TextButton(onClick = { vm.setSegments(segments.filter { it.id != seg.id }) }) { Text("删除", color = DS.negative) }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val lastEnd = segments.filter { it.isValid }.maxOfOrNull { it.endMinutes }
                val seg = if (lastEnd == null) {
                    WorkSegment(startMinutes = 9 * 60, endMinutes = 18 * 60)
                } else if (lastEnd <= 1440 - 60) {
                    WorkSegment(startMinutes = lastEnd, endMinutes = minOf(lastEnd + 120, 1440))
                } else {
                    WorkSegment(startMinutes = 9 * 60, endMinutes = 12 * 60)
                }
                vm.setSegments(segments + seg)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text("＋ 添加时间段") }
        LabeledRow("每天工作时长") {
            Text(
                if (calc.workSeconds > 0) Fmt.durationMinutes(calc.workSeconds / 60) + "（按时间段自动计算）" else "未设置",
                fontSize = 12.sp,
                color = mutedColor(),
            )
        }
        Text("结束时间必须晚于开始时间；重叠的时间段会自动合并。", fontSize = 11.sp, color = mutedColor())
    }
}

// ───────────────────────── 刷新频率 ─────────────────────────

@Composable
private fun RefreshCard(vm: AppViewModel, role: Role) {
    Card {
        CardTitle("刷新频率")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateMode.entries.forEach { m ->
                FilterChip(
                    selected = role.settings.updateMode == m,
                    onClick = { vm.setUpdateMode(m) },
                    label = { Text(m.label) },
                )
            }
        }
        Text(
            "当前：${role.settings.updateMode.description} · 金额只在应用打开时按所选频率跳动，回到前台会立即按系统时间重算。",
            fontSize = 11.sp,
            color = mutedColor(),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ───────────────────────── 数据备份 ─────────────────────────

@Composable
private fun BackupCard(vm: AppViewModel, ctx: Context) {
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportJson().toByteArray(Charsets.UTF_8)) }
                toast(ctx, "备份已导出")
            } catch (e: Exception) {
                toast(ctx, "导出失败：${e.message}")
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    Card {
        CardTitle("数据备份")
        Text(
            "数据只存在这台手机里。换手机、卸载重装、更新 APK 之前，请先导出备份。",
            fontSize = 12.sp,
            color = secondaryColor(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = {
                exporter.launch("今天挣了多少钱-备份-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".json")
            }) { Text("导出备份") }
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入备份") }
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份？") },
            text = { Text("导入会覆盖当前所有角色和记账数据，且无法恢复。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    try {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        if (text == null) {
                            toast(ctx, "读取失败")
                        } else {
                            val err = vm.importJson(text)
                            toast(ctx, err ?: "导入成功")
                        }
                    } catch (e: Exception) {
                        toast(ctx, "读取失败：${e.message}")
                    }
                }) { Text("覆盖导入", color = DS.negative) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
}
