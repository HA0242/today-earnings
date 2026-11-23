package local.ha.todayearnings.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.DayRecord
import local.ha.todayearnings.core.EarningsCalc
import local.ha.todayearnings.core.LeaveRecord
import local.ha.todayearnings.core.OvertimeRecord
import local.ha.todayearnings.core.Role
import local.ha.todayearnings.core.UpdateMode
import local.ha.todayearnings.core.WorkSegment
import local.ha.todayearnings.core.defaultWorkdays
import local.ha.todayearnings.core.key
import local.ha.todayearnings.data.AppDataCodec
import local.ha.todayearnings.data.LedgerStore
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LedgerStore(File(app.filesDir, "ledger.json"))

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _now = MutableStateFlow(ZonedDateTime.now())
    val now: StateFlow<ZonedDateTime> = _now.asStateFlow()

    // ── 时间 ──

    /** 立即按系统时间刷新（回到前台、系统时间/时区变化时调用）。 */
    fun refreshNow() {
        _now.value = ZonedDateTime.now()
    }

    /** 前台期间按当前角色的刷新频率驱动“现在”。生命周期离开 STARTED 后由调用方取消。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runTicker() {
        refreshNow()
        _data.map { it.activeRole.settings.updateMode }.distinctUntilChanged().collectLatest { mode ->
            while (true) {
                val n = ZonedDateTime.now()
                _now.value = n
                val wait = Duration.between(n, mode.nextFire(n)).toMillis()
                delay(if (wait < 20) 20 else wait)
            }
        }
    }

    fun calc(data: AppData, now: ZonedDateTime): EarningsCalc {
        val role = data.activeRole
        return EarningsCalc(
            role.settings,
            role.ledger,
            now.toLocalDate(),
            now.toLocalTime().toSecondOfDay() + now.nano / 1_000_000_000.0,
        )
    }

    // ── 修改（每次立即持久化） ──

    private fun commit(newData: AppData) {
        val n = newData.normalized()
        _data.value = n
        store.save(n)
    }

    private fun updateActive(transform: (Role) -> Role) {
        val d = _data.value
        commit(d.copy(roles = d.roles.map { if (it.id == d.activeRoleId) transform(it) else it }))
    }

    fun setSalary(v: Double) = updateActive { it.copy(settings = it.settings.copy(monthlySalary = v)) }

    fun setUpdateMode(m: UpdateMode) = updateActive { it.copy(settings = it.settings.copy(updateMode = m)) }

    fun setSegments(list: List<WorkSegment>) = updateActive { it.copy(settings = it.settings.copy(segments = list)) }

    /** 记账起始日：首次使用时由用户选择“从本月 1 日算”还是“从今天算”。 */
    fun setStartDate(date: LocalDate) = updateActive { it.copy(ledger = it.ledger.copy(startDate = date)) }

    // ── 工作日 ──

    fun toggleWorkday(ym: YearMonth, day: Int) = updateActive { role ->
        val current = role.ledger.plans[ym.key()] ?: ym.defaultWorkdays()
        val next = if (current.contains(day)) current - day else current + day
        role.copy(ledger = role.ledger.copy(plans = role.ledger.plans + (ym.key() to next)))
    }

    // ── 请假 / 加班 ──

    private fun updateRecord(date: LocalDate, mutate: (DayRecord) -> DayRecord) = updateActive { role ->
        val k = date.key()
        val next = mutate(role.ledger.records[k] ?: DayRecord())
        val records = if (next.isEmpty) role.ledger.records - k else role.ledger.records + (k to next)
        role.copy(ledger = role.ledger.copy(records = records))
    }

    fun setLeave(date: LocalDate, leave: LeaveRecord?) = updateRecord(date) { it.copy(leave = leave) }

    fun setOvertime(date: LocalDate, ot: OvertimeRecord?) = updateRecord(date) { it.copy(overtime = ot) }

    // ── 角色 ──

    fun switchRole(id: String) {
        val d = _data.value
        if (d.roles.any { it.id == id } && id != d.activeRoleId) commit(d.copy(activeRoleId = id))
    }

    fun addRole(name: String) {
        val d = _data.value
        val role = Role(name = name.trim().ifEmpty { "新角色" })
        commit(d.copy(roles = d.roles + role))
    }

    fun renameRole(id: String, name: String) {
        val d = _data.value
        commit(d.copy(roles = d.roles.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }))
    }

    /** 主账户不可删除，至少保留 1 个角色；删的是当前角色时切到第一个剩下的。 */
    fun deleteRole(id: String) {
        val d = _data.value
        val target = d.roles.firstOrNull { it.id == id } ?: return
        if (target.isPrimary || d.roles.size <= 1) return
        val rest = d.roles.filter { it.id != id }
        commit(d.copy(roles = rest, activeRoleId = if (d.activeRoleId == id) rest.first().id else d.activeRoleId))
    }

    // ── 备份 ──

    fun exportJson(): String = AppDataCodec.encode(_data.value)

    /** 成功返回 null；失败返回可展示的错误信息。 */
    fun importJson(text: String): String? = try {
        commit(AppDataCodec.decode(text))
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "导入失败"
    }
}
