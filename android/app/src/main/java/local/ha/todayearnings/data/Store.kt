package local.ha.todayearnings.data

import local.ha.todayearnings.core.AppData
import local.ha.todayearnings.core.DayRecord
import local.ha.todayearnings.core.Ledger
import local.ha.todayearnings.core.LeaveRecord
import local.ha.todayearnings.core.OvertimeRecord
import local.ha.todayearnings.core.Role
import local.ha.todayearnings.core.Cycle
import local.ha.todayearnings.core.Settings
import local.ha.todayearnings.core.UpdateMode
import local.ha.todayearnings.core.WorkSegment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** 备份文件与本地存储共用的 JSON 编解码。格式版本 1。 */
object AppDataCodec {
    const val VERSION = 1

    fun encode(data: AppData): String {
        val roles = JSONArray()
        data.roles.forEach { roles.put(encodeRole(it)) }
        return JSONObject()
            .put("app", "today-earnings")
            .put("version", VERSION)
            .put("activeRoleId", data.activeRoleId)
            .put("roles", roles)
            .toString(2)
    }

    /** 解析失败抛 [IllegalArgumentException]，消息可直接展示给用户。 */
    fun decode(text: String): AppData {
        try {
            val root = JSONObject(text)
            val rolesJson = root.optJSONArray("roles") ?: throw IllegalArgumentException("不是有效的备份文件")
            val roles = (0 until rolesJson.length()).map { decodeRole(rolesJson.getJSONObject(it)) }
            if (roles.isEmpty()) throw IllegalArgumentException("备份文件里没有任何角色")
            return AppData(roles, root.optString("activeRoleId", roles.first().id)).normalized()
        } catch (e: JSONException) {
            throw IllegalArgumentException("不是有效的备份文件")
        }
    }

    private fun encodeRole(r: Role): JSONObject {
        val segs = JSONArray()
        r.settings.segments.forEach {
            segs.put(JSONObject().put("id", it.id).put("start", it.startMinutes).put("end", it.endMinutes))
        }
        val settings = JSONObject()
            .put("monthlySalary", r.settings.monthlySalary)
            .put("updateMode", r.settings.updateMode.label)
            .put("cycleStartDay", r.settings.cycleStartDay)
            .put("segments", segs)

        val plans = JSONObject()
        r.ledger.plans.forEach { (k, days) -> plans.put(k, JSONArray(days.sorted())) }
        val records = JSONObject()
        r.ledger.records.forEach { (k, rec) ->
            val o = JSONObject()
            rec.leave?.let {
                o.put("leave", JSONObject().put("deduct", it.deduct).put("half", it.half).put("note", it.note))
            }
            rec.overtime?.let {
                o.put(
                    "overtime",
                    JSONObject().put("paid", it.paid).put("hours", it.hours)
                        .put("multiplier", it.multiplier).put("note", it.note),
                )
            }
            records.put(k, o)
        }
        val ledger = JSONObject().put("plans", plans).put("records", records)
        r.ledger.startDate?.let { ledger.put("startDate", it.toString()) }

        return JSONObject()
            .put("id", r.id)
            .put("name", r.name)
            .put("isPrimary", r.isPrimary)
            .put("settings", settings)
            .put("ledger", ledger)
    }

    private fun decodeRole(o: JSONObject): Role {
        val s = o.getJSONObject("settings")
        val segJson = s.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until segJson.length()).map { segJson.getJSONObject(it) }.map {
            WorkSegment(it.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }, it.getInt("start"), it.getInt("end"))
        }.filter { it.isValid }
        val settings = Settings(
            monthlySalary = s.optDouble("monthlySalary", 10000.0).let { if (it.isNaN() || it < 0) 0.0 else it },
            segments = segments,
            updateMode = UpdateMode.fromLabel(s.optString("updateMode")),
            cycleStartDay = Cycle.normalize(s.optInt("cycleStartDay", 1)),
        )

        val l = o.optJSONObject("ledger") ?: JSONObject()
        val plans = HashMap<String, Set<Int>>()
        l.optJSONObject("plans")?.let { p ->
            p.keys().forEach { k ->
                val arr = p.getJSONArray(k)
                plans[k] = (0 until arr.length()).map { arr.getInt(it) }.toSet()
            }
        }
        val records = HashMap<String, DayRecord>()
        l.optJSONObject("records")?.let { r ->
            r.keys().forEach { k ->
                val rec = r.getJSONObject(k)
                val leave = rec.optJSONObject("leave")?.let {
                    LeaveRecord(it.optBoolean("deduct", true), it.optBoolean("half", false), it.optString("note"))
                }
                val ot = rec.optJSONObject("overtime")?.let {
                    OvertimeRecord(
                        it.optBoolean("paid", true),
                        it.optDouble("hours", 2.0),
                        it.optDouble("multiplier", 1.0),
                        it.optString("note"),
                    )
                }
                val day = DayRecord(leave, ot)
                if (!day.isEmpty) records[k] = day
            }
        }
        val start = if (l.has("startDate")) runCatching { LocalDate.parse(l.getString("startDate")) }.getOrNull() else null

        return Role(
            id = o.getString("id"),
            name = o.optString("name", "角色").ifBlank { "角色" },
            isPrimary = o.optBoolean("isPrimary", false),
            settings = settings,
            ledger = Ledger(start, plans, records),
        )
    }
}

/** 本地存储：filesDir/ledger.json，每次修改立即原子写入。 */
class LedgerStore(private val file: File) {

    fun load(): AppData {
        if (!file.exists()) return AppData.initial().also { save(it) }
        return try {
            AppDataCodec.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 数据损坏：保留一份副本再用全新数据启动，避免静默丢数据。
            runCatching { file.copyTo(File(file.parentFile, "ledger.corrupt-${System.currentTimeMillis()}.json"), overwrite = true) }
            AppData.initial().also { save(it) }
        }
    }

    fun save(data: AppData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppDataCodec.encode(data), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
