import SwiftUI
import Foundation

// MARK: - AppState 属性包装器
// CLT 27 SDK 将 @State 改为宏实现但未附带 SwiftUIMacros 插件，
// State struct 本体仍是 propertyWrapper，用等价包装器绕过宏展开

@propertyWrapper
struct AppState<Value>: DynamicProperty {
    var storage: State<Value>
    init(wrappedValue: Value) { storage = State(initialValue: wrappedValue) }
    var wrappedValue: Value {
        get { storage.wrappedValue }
        nonmutating set { storage.wrappedValue = newValue }
    }
    var projectedValue: Binding<Value> { storage.projectedValue }
}

// MARK: - 设计系统
// 克制的极简风格：跟随系统外观（浅色/深色自动切换），只用一个强调色（系统 accentColor），
// 结构靠灰阶分层，语义色仅在"正/负金额"这类必须区分的地方使用。

enum DS {
    static let surface1 = Color(nsColor: .controlBackgroundColor)
    static let surface2 = Color(nsColor: .underPageBackgroundColor)
    static let hairline = Color(nsColor: .separatorColor).opacity(0.6)

    static let textPrimary = Color.primary
    static let textSecondary = Color.secondary
    static let textTertiary = Color.secondary.opacity(0.65)

    /// 唯一强调色：跟随系统 accentColor，用于核心金额与关键状态
    static let accent = Color.accentColor
    /// 正向金额（如加班费）
    static let positive = Color(nsColor: .systemGreen)
    /// 负向影响（如请假扣款）
    static let negative = Color(nsColor: .systemRed)
}

// MARK: - 刷新模式：每秒 / 每分 / 每时（整点）

enum UpdateMode: String, CaseIterable, Identifiable, Codable {
    case everySecond = "每秒"
    case everyMinute = "每分"
    case everyHour = "整点"

    var id: String { rawValue }

    var interval: TimeInterval {
        switch self {
        case .everySecond: return 1
        case .everyMinute: return 60
        case .everyHour: return 3600
        }
    }

    var description: String {
        switch self {
        case .everySecond: return "每秒跳"
        case .everyMinute: return "每分钟跳"
        case .everyHour: return "每小时整点跳"
        }
    }

    func nextFireDate(after date: Date) -> Date {
        let cal = Calendar.current
        switch self {
        case .everySecond:
            return Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.up))
        case .everyMinute:
            return cal.nextDate(after: date, matching: DateComponents(second: 0), matchingPolicy: .nextTime)
                ?? date.addingTimeInterval(60)
        case .everyHour:
            return cal.nextDate(after: date, matching: DateComponents(minute: 0, second: 0), matchingPolicy: .nextTime)
                ?? date.addingTimeInterval(3600)
        }
    }
}

// MARK: - 上班时间段

struct WorkSegment: Codable, Identifiable, Equatable {
    var id = UUID()
    var startMinutes: Int
    var endMinutes: Int

    var durationMinutes: Int {
        var d = endMinutes - startMinutes
        if d <= 0 { d += 24 * 60 }
        return d
    }
}

struct DayInterval: Equatable {
    let start: Date
    let end: Date
}

enum EarningsPhase {
    case noWork, beforeWork, working, resting, afterWork, dayOff
}

// MARK: - 记账数据模型

struct LeaveRecord: Codable, Equatable {
    var deduct: Bool      // true = 请假扣钱；false = 不扣钱（带薪）
    var note: String

    init(deduct: Bool = true, note: String = "") {
        self.deduct = deduct
        self.note = note
    }
}

struct OvertimeRecord: Codable, Equatable {
    var paid: Bool        // true = 加班加钱；false = 不加钱（仅记录）
    var hours: Double
    var note: String

    init(paid: Bool = true, hours: Double = 2, note: String = "") {
        self.paid = paid
        self.hours = hours
        self.note = note
    }
}

struct DayRecord: Codable, Equatable {
    var leave: LeaveRecord?
    var overtime: OvertimeRecord?

    var isEmpty: Bool { leave == nil && overtime == nil }
}

struct LedgerData: Codable, Equatable {
    var startDate: Date?                  // 记账起始日（v2 首次启动）
    var monthlyPlans: [String: Set<Int>]  // "2026-09" → 当月上班日期集合
    var dayRecords: [String: DayRecord]  // "2026-09-18" → 请假/加班记录

    init() {
        startDate = nil
        monthlyPlans = [:]
        dayRecords = [:]
    }
}

// MARK: - 月历信息

struct MonthInfo: Equatable {
    let year: Int
    let month: Int
    let daysInMonth: Int
    let firstWeekday: Int   // 1=周日 ... 7=周六

    init(date: Date) {
        let cal = Calendar.current
        let c = cal.dateComponents([.year, .month], from: date)
        year = c.year ?? 2026
        month = c.month ?? 1
        daysInMonth = cal.range(of: .day, in: .month, for: date)?.count ?? 30
        let first = cal.date(from: DateComponents(year: year, month: month, day: 1)) ?? date
        firstWeekday = cal.component(.weekday, from: first)
    }

    static func current() -> MonthInfo { MonthInfo(date: Date()) }

    var key: String { String(format: "%04d-%02d", year, month) }
    func dayKey(_ day: Int) -> String { String(format: "%04d-%02d-%02d", year, month, day) }

    func date(_ day: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: year, month: month, day: day)) ?? Date()
    }

    /// 周一开头布局下，第一行前面的空格数
    var leadingBlanks: Int { (firstWeekday + 5) % 7 }

    /// 默认工作日模板：周一至周五
    var defaultWorkdays: Set<Int> {
        var s = Set<Int>()
        for d in 1...daysInMonth {
            let wd = Calendar.current.component(.weekday, from: date(d))
            if (2...6).contains(wd) { s.insert(d) }
        }
        return s
    }

    var next: MonthInfo {
        let cal = Calendar.current
        let d = cal.date(from: DateComponents(year: month == 12 ? year + 1 : year,
                                              month: month == 12 ? 1 : month + 1, day: 15)) ?? Date()
        return MonthInfo(date: d)
    }

    var prev: MonthInfo {
        let cal = Calendar.current
        let d = cal.date(from: DateComponents(year: month == 1 ? year - 1 : year,
                                              month: month == 1 ? 12 : month - 1, day: 15)) ?? Date()
        return MonthInfo(date: d)
    }

    var title: String { "\(year)年\(month)月" }
    var shortTitle: String { "\(month)月" }

    func contains(_ date: Date) -> Bool {
        let c = Calendar.current.dateComponents([.year, .month], from: date)
        return c.year == year && c.month == month
    }
}

// MARK: - 设置与收入计算

struct EarningsSettings: Codable, Equatable {
    var monthlySalary: Double
    var workdayCount: Int
    var segments: [WorkSegment]
    var updateMode: UpdateMode

    init(monthlySalary: Double = 10000,
         workdayCount: Int = 22,
         segments: [WorkSegment] = EarningsSettings.defaultSegments,
         updateMode: UpdateMode = .everySecond) {
        self.monthlySalary = monthlySalary
        self.workdayCount = workdayCount
        self.segments = segments
        self.updateMode = updateMode
    }

    static let defaultSegments = [
        WorkSegment(startMinutes: 9 * 60, endMinutes: 12 * 60),
        WorkSegment(startMinutes: 13 * 60, endMinutes: 18 * 60)
    ]

    // MARK: 时间段计算

    func mergedIntervals(now: Date) -> [DayInterval] {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: now)
        let raw = segments.map { seg -> DayInterval in
            let s = dayStart.addingTimeInterval(TimeInterval((seg.startMinutes % 1440) * 60))
            var e = dayStart.addingTimeInterval(TimeInterval((seg.endMinutes % 1440) * 60))
            if e <= s { e = e.addingTimeInterval(86400) }
            return DayInterval(start: s, end: e)
        }.sorted { $0.start < $1.start }

        var out: [DayInterval] = []
        for iv in raw {
            if let last = out.last, iv.start <= last.end {
                if iv.end > last.end {
                    out[out.count - 1] = DayInterval(start: last.start, end: iv.end)
                }
            } else {
                out.append(iv)
            }
        }
        return out
    }

    func workSeconds(now: Date) -> TimeInterval {
        mergedIntervals(now: now).reduce(0) { $0 + $1.end.timeIntervalSince($1.start) }
    }

    /// 跨零点的时间段（结束 <= 开始，代表这个班次会持续到第二天凌晨）
    private var overnightSegments: [WorkSegment] { segments.filter { $0.endMinutes <= $0.startMinutes } }

    /// 把跨零点时间段按"昨天"重新锚定一次，得到它延续到今天凌晨那一段的真实区间。
    /// mergedIntervals(now:) 永远以"今天 0 点"为基准重算，所以昨晚开始、今天凌晨还没结束的夜班，
    /// 不会出现在 mergedIntervals 里——过了 0 点它就"凭空消失"了，导致误判成"还没上班"。
    private func overnightCarryover(now: Date) -> [DayInterval] {
        guard !overnightSegments.isEmpty else { return [] }
        let cal = Calendar.current
        let yesterdayStart = cal.date(byAdding: .day, value: -1, to: cal.startOfDay(for: now))!
        return overnightSegments.map { seg in
            let s = yesterdayStart.addingTimeInterval(TimeInterval((seg.startMinutes % 1440) * 60))
            let e = yesterdayStart.addingTimeInterval(TimeInterval((seg.endMinutes % 1440) * 60 + 86400))
            return DayInterval(start: s, end: e)
        }
    }

    private func activeOvernightCarryover(now: Date) -> DayInterval? {
        overnightCarryover(now: now).first { $0.start <= now && now < $0.end }
    }

    func elapsedWorkSeconds(now: Date) -> TimeInterval {
        if let carry = activeOvernightCarryover(now: now) {
            return min(now, carry.end).timeIntervalSince(carry.start)
        }
        return mergedIntervals(now: now).reduce(0) { acc, iv in
            guard now > iv.start else { return acc }
            return acc + min(now, iv.end).timeIntervalSince(iv.start)
        }
    }

    var dailyIncome: Double { workdayCount > 0 ? monthlySalary / Double(workdayCount) : 0 }

    func hourlyIncome(now: Date) -> Double {
        let hours = workSeconds(now: now) / 3600
        return hours > 0 ? dailyIncome / hours : 0
    }

    func perSecondIncome(now: Date) -> Double {
        hourlyIncome(now: now) / 3600
    }

    func earnedToday(now: Date) -> Double {
        perSecondIncome(now: now) * elapsedWorkSeconds(now: now)
    }

    func progress(now: Date) -> Double {
        let total = workSeconds(now: now)
        return total > 0 ? elapsedWorkSeconds(now: now) / total : 0
    }

    func phase(now: Date) -> EarningsPhase {
        if activeOvernightCarryover(now: now) != nil { return .working }
        let ivs = mergedIntervals(now: now)
        guard let first = ivs.first, let last = ivs.last else { return .noWork }
        if now < first.start { return .beforeWork }
        if now >= last.end { return .afterWork }
        if ivs.contains(where: { $0.start <= now && now < $0.end }) { return .working }
        return .resting
    }

    func remainingSeconds(now: Date) -> TimeInterval {
        if let carry = activeOvernightCarryover(now: now) {
            return max(0, carry.end.timeIntervalSince(now))
        }
        let ivs = mergedIntervals(now: now)
        switch phase(now: now) {
        case .working:
            return max(0, ivs.last!.end.timeIntervalSince(now))
        case .resting:
            if let next = ivs.first(where: { now < $0.start }) {
                return next.start.timeIntervalSince(now)
            }
            return 0
        case .beforeWork:
            return ivs.first!.start.timeIntervalSince(now)
        case .noWork, .afterWork, .dayOff:
            return 0
        }
    }
}

// MARK: - App 共享存储

enum SharedStore {
    static let suiteName = "local.ha.today-earnings.shared"
    private static let d = UserDefaults(suiteName: suiteName) ?? .standard

    static func load() -> EarningsSettings {
        var s = EarningsSettings()
        var hasData = false

        if let v = d.object(forKey: "monthlySalary") as? Double { s.monthlySalary = v; hasData = true }
        if let raw = d.string(forKey: "updateMode"), let m = UpdateMode(rawValue: raw) { s.updateMode = m; hasData = true }
        if let data = d.data(forKey: "workSegments"),
           let segs = try? JSONDecoder().decode([WorkSegment].self, from: data),
           !segs.isEmpty {
            s.segments = segs
            hasData = true
        }

        // 一次性迁移：把主 App 旧 UserDefaults 里的数据搬进共享存储
        if !hasData {
            let old = UserDefaults.standard
            var migrated = false
            if let v = old.object(forKey: "monthlySalary") as? Double { s.monthlySalary = v; migrated = true }
            if let raw = old.string(forKey: "updateMode"), let m = UpdateMode(rawValue: raw) { s.updateMode = m; migrated = true }
            if let data = old.data(forKey: "workSegments"),
               let segs = try? JSONDecoder().decode([WorkSegment].self, from: data),
               !segs.isEmpty {
                s.segments = segs
                migrated = true
            } else if old.object(forKey: "startHour") != nil {
                let startMin = ((old.object(forKey: "startHour") as? Int) ?? 9) * 60
                    + ((old.object(forKey: "startMinute") as? Int) ?? 0)
                let wh = old.object(forKey: "workHours") as? Double ?? 8
                var endMin = startMin + Int((wh * 60).rounded())
                if endMin >= 24 * 60 { endMin -= 24 * 60 }
                s.segments = [WorkSegment(startMinutes: startMin, endMinutes: endMin)]
                migrated = true
            }
            if migrated { save(s) }
        }
        return s
    }

    static func save(_ s: EarningsSettings) {
        d.set(s.monthlySalary, forKey: "monthlySalary")
        d.set(s.updateMode.rawValue, forKey: "updateMode")
        if let data = try? JSONEncoder().encode(s.segments) {
            d.set(data, forKey: "workSegments")
        }
    }
}

// MARK: - 记账存储

enum LedgerStore {
    private static let d = UserDefaults(suiteName: SharedStore.suiteName) ?? .standard

    static func load() -> LedgerData {
        guard let data = d.data(forKey: "ledgerData"),
              let ledger = try? JSONDecoder().decode(LedgerData.self, from: data) else {
            return LedgerData()
        }
        return ledger
    }

    static func save(_ ledger: LedgerData) {
        if let data = try? JSONEncoder().encode(ledger) {
            d.set(data, forKey: "ledgerData")
        }
    }
}

// MARK: - 多角色记账

struct Role: Codable, Identifiable, Equatable {
    var id: UUID
    var name: String
    var settings: EarningsSettings
    var ledger: LedgerData
    /// 主账户：不允许被删除。只有迁移/首次创建时产生的那一个角色会是 true
    var isPrimary: Bool

    init(id: UUID = UUID(), name: String, settings: EarningsSettings, ledger: LedgerData, isPrimary: Bool = false) {
        self.id = id
        self.name = name
        self.settings = settings
        self.ledger = ledger
        self.isPrimary = isPrimary
    }

    enum CodingKeys: String, CodingKey { case id, name, settings, ledger, isPrimary }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        settings = try c.decode(EarningsSettings.self, forKey: .settings)
        ledger = try c.decode(LedgerData.self, forKey: .ledger)
        // 兼容"加这个字段之前"存进去的旧数据：没有这个 key 就先当 false，
        // RolesStore.load() 里会再兜底保证永远有且只有一个主账户。
        isPrimary = try c.decodeIfPresent(Bool.self, forKey: .isPrimary) ?? false
    }
}

enum RolesStore {
    private static let d = UserDefaults(suiteName: SharedStore.suiteName) ?? .standard
    private static let key = "roles_v1"

    struct Payload: Codable {
        var roles: [Role]
        var activeRoleId: UUID
    }

    static func load() -> Payload {
        if let data = d.data(forKey: key),
           let payload = try? JSONDecoder().decode(Payload.self, from: data),
           !payload.roles.isEmpty {
            return normalize(payload)
        }

        // 一次性迁移：把现在这份单一账户的数据包成"主账户"，旧 key 原样保留不删
        let role = Role(name: "主账户", settings: SharedStore.load(), ledger: LedgerStore.load(), isPrimary: true)
        let payload = Payload(roles: [role], activeRoleId: role.id)
        save(payload)
        return payload
    }

    /// 保证任何时候都有且只有一个 isPrimary 角色——兼容"加 isPrimary 字段之前"就已经写进去的旧 roles_v1 数据：
    /// 那批数据里没有角色带 isPrimary，这里把排第一个（也就是当初迁移出来的那个"主账户"）补成主账户。
    private static func normalize(_ payload: Payload) -> Payload {
        guard !payload.roles.contains(where: { $0.isPrimary }) else { return payload }
        var p = payload
        p.roles[0].isPrimary = true
        save(p)
        return p
    }

    static func save(_ payload: Payload) {
        if let data = try? JSONEncoder().encode(payload) {
            d.set(data, forKey: key)
        }
    }
}

// MARK: - 工具

enum Fmt {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "M月d日"
        return f
    }()

    static func time(_ d: Date) -> String { timeFormatter.string(from: d) }
    static func day(_ d: Date) -> String { dayFormatter.string(from: d) }
    static func money(_ v: Double) -> String {
        v.formatted(.number.precision(.fractionLength(2)))
    }

    static func duration(_ t: TimeInterval) -> String {
        let s = max(0, Int(t))
        let h = s / 3600
        let m = (s % 3600) / 60
        if h > 0 { return "\(h) 小时 \(m) 分" }
        if m > 0 { return "\(m) 分钟" }
        return "不到 1 分钟"
    }

    static func date(fromDayKey key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    static func weekdayText(_ date: Date) -> String {
        let names = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        return names[Calendar.current.component(.weekday, from: date) - 1]
    }
}
