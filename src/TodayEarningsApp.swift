import SwiftUI
import AppKit

// MARK: - 数据模型

struct MonthSummary {
    var workdays: Int = 0
    var expectedMonth: Double = 0
    var leaveDeduction: Double = 0
    var overtimeFee: Double = 0
    var leaveDays: Int = 0
    var leaveDeductDays: Int = 0
    var overtimeHours: Double = 0
}

struct MonthDayRecord: Identifiable {
    let day: Int
    let record: DayRecord
    var id: Int { day }
}

final class AppModel: ObservableObject {
    let roleId: UUID
    /// 数据变化时回调给外面（RolesManager）持久化到对应角色身上
    var onChange: ((EarningsSettings, LedgerData) -> Void)?

    @Published var now = Date()
    @Published var nextTick: Date?

    @Published var monthlySalary: Double { didSet { notifyChange(); touch() } }
    @Published var segments: [WorkSegment] { didSet { notifyChange(); touch() } }
    @Published var updateMode: UpdateMode { didSet { notifyChange(); restartTimer() } }
    @Published var ledger: LedgerData { didSet { notifyChange(); touch() } }

    private var timer: Timer?

    init(role: Role) {
        roleId = role.id
        monthlySalary = role.settings.monthlySalary
        segments = role.settings.segments
        updateMode = role.settings.updateMode

        var l = role.ledger
        if l.startDate == nil { l.startDate = Date() }
        ledger = l

        restartTimer()

        NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.touch()
        }
    }

    deinit { timer?.invalidate() }

    private func touch() { now = Date() }

    private func notifyChange() {
        onChange?(EarningsSettings(
            monthlySalary: monthlySalary,
            workdayCount: currentWorkdayCount,
            segments: segments,
            updateMode: updateMode
        ), ledger)
    }

    private func restartTimer() {
        timer?.invalidate()
        let fire = updateMode.nextFireDate(after: Date())
        let t = Timer(fire: fire, interval: updateMode.interval, repeats: true) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.now = Date()
                self.nextTick = self.timer?.fireDate
            }
        }
        RunLoop.main.add(t, forMode: RunLoop.Mode.common)
        timer = t
        nextTick = fire
        now = Date()
    }

    // MARK: 记账读写

    func workdays(in month: MonthInfo) -> Set<Int> {
        ledger.monthlyPlans[month.key] ?? month.defaultWorkdays
    }

    func setPlan(month: MonthInfo, days: Set<Int>) {
        ledger.monthlyPlans[month.key] = days
    }

    func toggleWorkday(month: MonthInfo, day: Int) {
        var days = workdays(in: month)
        if days.contains(day) { days.remove(day) } else { days.insert(day) }
        setPlan(month: month, days: days)
    }

    func record(month: MonthInfo, day: Int) -> DayRecord? {
        ledger.dayRecords[month.dayKey(day)]
    }

    func updateRecord(month: MonthInfo, day: Int, mutate: (inout DayRecord) -> Void) {
        var rec = record(month: month, day: day) ?? DayRecord()
        mutate(&rec)
        if rec.isEmpty {
            ledger.dayRecords.removeValue(forKey: month.dayKey(day))
        } else {
            ledger.dayRecords[month.dayKey(day)] = rec
        }
    }

    func setLeave(month: MonthInfo, day: Int, leave: LeaveRecord?) {
        updateRecord(month: month, day: day) { $0.leave = leave }
    }

    func updateLeave(month: MonthInfo, day: Int, mutate: (inout LeaveRecord) -> Void) {
        updateRecord(month: month, day: day) {
            if $0.leave != nil { mutate(&$0.leave!) }
        }
    }

    func setOvertime(month: MonthInfo, day: Int, overtime: OvertimeRecord?) {
        updateRecord(month: month, day: day) { $0.overtime = overtime }
    }

    func updateOvertime(month: MonthInfo, day: Int, mutate: (inout OvertimeRecord) -> Void) {
        updateRecord(month: month, day: day) {
            if $0.overtime != nil { mutate(&$0.overtime!) }
        }
    }

    // MARK: 当日状态

    var currentMonth: MonthInfo { MonthInfo(date: now) }

    var todayDay: Int { Calendar.current.component(.day, from: now) }

    var todayRecord: DayRecord? {
        record(month: currentMonth, day: todayDay)
    }

    var todayLeave: LeaveRecord? { todayRecord?.leave }
    var todayOvertime: OvertimeRecord? { todayRecord?.overtime }

    var todayIsWorkday: Bool {
        workdays(in: currentMonth).contains(todayDay)
    }

    /// 当前月的时薪（加班费计算用）
    var currentHourlyIncome: Double {
        let c = workdays(in: currentMonth).count
        let dIncome = c > 0 ? monthlySalary / Double(c) : 0
        let hours = workSeconds / 3600
        return hours > 0 ? dIncome / hours : 0
    }

    /// 今日加班费（加钱才有）
    var todayOvertimeFee: Double {
        guard let ot = todayOvertime, ot.paid else { return 0 }
        return currentHourlyIncome * ot.hours
    }

    func overtimeFee(month: MonthInfo, day: Int) -> Double? {
        guard let ot = record(month: month, day: day)?.overtime, ot.paid else { return nil }
        let c = workdays(in: month).count
        let dIncome = c > 0 ? monthlySalary / Double(c) : 0
        let hours = workSeconds / 3600
        let hIncome = hours > 0 ? dIncome / hours : 0
        return hIncome * ot.hours
    }

    /// 主页大数字：今日显示金额（基础工资：非工作日或扣薪假 = 0；加班费：只要加钱就算，不管是不是工作日）
    var displayEarnedToday: Double {
        let base: Double = (todayIsWorkday && todayLeave?.deduct != true) ? earnedToday : 0
        return base + todayOvertimeFee
    }

    /// 日历弹层顶部说明：这天的日薪 / 状态
    func dayIncomeNote(month m: MonthInfo, day: Int) -> String? {
        let c = workdays(in: m).count
        guard c > 0, workdays(in: m).contains(day) else { return "休息日 · 不计薪" }
        let dIncome = monthlySalary / Double(c)
        return "工作日 · 日薪 ¥\(Fmt.money(dIncome))"
    }

    // MARK: 月度计算

    var currentWorkdayCount: Int { workdays(in: currentMonth).count }

    var settings: EarningsSettings {
        EarningsSettings(monthlySalary: monthlySalary,
                         workdayCount: currentWorkdayCount,
                         segments: segments,
                         updateMode: updateMode)
    }

    var mergedIntervals: [DayInterval] { settings.mergedIntervals(now: now) }
    var workSeconds: TimeInterval { settings.workSeconds(now: now) }
    var elapsedWorkSeconds: TimeInterval { settings.elapsedWorkSeconds(now: now) }
    var dailyIncome: Double { settings.dailyIncome }
    var hourlyIncome: Double { settings.hourlyIncome(now: now) }
    var perSecondIncome: Double { settings.perSecondIncome(now: now) }
    var earnedToday: Double { settings.earnedToday(now: now) }
    var progress: Double { settings.progress(now: now) }
    /// 首页进度条用这个：非工作日不再显示"正在走动"的进度，跟"今天不上班"的文案保持一致
    var todayProgress: Double { todayIsWorkday ? progress : 0 }
    var phase: EarningsPhase {
        todayIsWorkday ? settings.phase(now: now) : .dayOff
    }
    var remainingSeconds: TimeInterval { settings.remainingSeconds(now: now) }

    private func dailyIncome(in m: MonthInfo) -> Double {
        let c = workdays(in: m).count
        return c > 0 ? monthlySalary / Double(c) : 0
    }

    private func hourlyIncome(in m: MonthInfo) -> Double {
        let hours = workSeconds / 3600
        return hours > 0 ? dailyIncome(in: m) / hours : 0
    }

    /// 单日基础挣钱与加班费（月度累计与柱状图共用）
    /// 基础：工作日且非扣薪假才有（今天按实时 earnedToday）；加班费：加钱就有，休息日加班也计
    func dayEarnedSplit(_ m: MonthInfo, day: Int) -> (base: Double, fee: Double) {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: m.date(day))
        let today = cal.startOfDay(for: now)
        guard dayStart <= today else { return (0, 0) }

        let rec = ledger.dayRecords[m.dayKey(day)]
        var base = 0.0
        if workdays(in: m).contains(day), rec?.leave?.deduct != true {
            base = (dayStart == today) ? earnedToday : dailyIncome(in: m)
        }
        let fee = (rec?.overtime?.paid == true) ? (rec?.overtime?.hours ?? 0) * hourlyIncome(in: m) : 0
        return (base, fee)
    }

    /// 单日挣钱合计
    func dayEarned(_ m: MonthInfo, day: Int) -> Double {
        let s = dayEarnedSplit(m, day: day)
        return s.base + s.fee
    }

    /// 月已挣：自然月 1 日至今所有工作日已挣 + 加班费 - 扣薪
    func monthEarnings(_ m: MonthInfo) -> Double {
        (1...m.daysInMonth).reduce(0) { $0 + dayEarned(m, day: $1) }
    }

    /// 当月请假/加班记录（按日期升序），月度报告明细用
    func monthRecords(_ m: MonthInfo) -> [MonthDayRecord] {
        (1...m.daysInMonth)
            .compactMap { d in ledger.dayRecords[m.dayKey(d)].map { MonthDayRecord(day: d, record: $0) } }
            .sorted { $0.day < $1.day }
    }

    /// 报告明细：一条记录对当月收入的净影响（负=扣薪、正=加班费）
    /// 请假扣薪只在"这天本来是工作日"时才真的扣了钱（跟 monthSummary/dayEarnedSplit 的口径保持一致）
    func recordImpact(_ m: MonthInfo, day: Int) -> Double {
        let rec = record(month: m, day: day)
        var delta = 0.0
        if workdays(in: m).contains(day), rec?.leave?.deduct == true { delta -= dailyIncome(in: m) }
        if let ot = rec?.overtime, ot.paid { delta += ot.hours * hourlyIncome(in: m) }
        return delta
    }

    /// 月度汇总：工作日数、预计全月、请假扣款、加班费
    func monthSummary(_ m: MonthInfo) -> MonthSummary {
        let wds = workdays(in: m)
        let dIncome = dailyIncome(in: m)
        let hIncome = hourlyIncome(in: m)
        var s = MonthSummary()
        s.workdays = wds.count
        s.expectedMonth = dIncome * Double(wds.count)

        for day in 1...m.daysInMonth {
            guard let rec = ledger.dayRecords[m.dayKey(day)] else { continue }
            if wds.contains(day), let lv = rec.leave {
                s.leaveDays += 1
                if lv.deduct {
                    s.leaveDeductDays += 1
                    s.leaveDeduction += dIncome
                    s.expectedMonth -= dIncome
                }
            }
            if let ot = rec.overtime {
                s.overtimeHours += ot.hours
                if ot.paid {
                    let fee = ot.hours * hIncome
                    s.overtimeFee += fee
                    s.expectedMonth += fee
                }
            }
        }
        return s
    }

}

// MARK: - 状态指示点（静态，不做呼吸动画）

func statusDot(_ color: Color) -> some View {
    Circle().fill(color).frame(width: 6, height: 6)
}

// MARK: - 主窗口（主页 / 日历 / 统计 / 设置 四页切换）

enum Page {
    case main, calendar, stats, settings
}

struct ContentView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var rolesManager: RolesManager
    @AppState private var page: Page = .main

    var body: some View {
        Group {
            switch page {
            case .main:
                mainPage
                    .transition(.opacity)
            case .calendar:
                CalendarPage(model: model) { dismiss(.main) }
                    .transition(.opacity)
            case .stats:
                StatsPage(model: model) { dismiss(.main) }
                    .transition(.opacity)
            case .settings:
                SettingsPage(model: model, rolesManager: rolesManager,
                             onClose: { dismiss(.main) },
                             onOpenCalendar: { dismiss(.calendar) })
                    .transition(.opacity)
            }
        }
        // 背景放在 .background 里，跟着前景内容的实际尺寸走；
        // 之前用 ZStack 把一个没有 frame 约束、靠 ignoresSafeArea 撑满的 Color
        // 跟内容放在一起，会让整个窗口的"理想尺寸"变得不确定，
        // 实测触发过 900×492 这种跟代码里 440×460 完全对不上的默认尺寸。
        .background(Color(nsColor: .windowBackgroundColor).ignoresSafeArea())
    }

    private func dismiss(_ target: Page) {
        withAnimation(.easeOut(duration: 0.2)) { page = target }
    }

    // MARK: 主页面

    private var mainPage: some View {
        VStack(spacing: 22) {
            hero

            progressBar

            statsRow

            monthEarnedRow

            if model.updateMode == .everyHour {
                nextTickView
            }
        }
        .padding(28)
        .frame(minWidth: 440, idealWidth: 440, maxWidth: 440, minHeight: 460, idealHeight: 460)
        .overlay(alignment: .topTrailing) {
            navButtons
        }
    }

    private var navButtons: some View {
        HStack(spacing: 6) {
            navButton(icon: "calendar", help: "日历 · 工作日与请假加班记账", page: .calendar)
            navButton(icon: "chart.bar.fill", help: "本月挣钱情况", page: .stats)
            navButton(icon: "gearshape", help: "打开设置", page: .settings)
        }
        .padding(.trailing, 8)
        .padding(.top, 4)
    }

    @AppState private var hoveredNav: String? = nil

    private func navButton(icon: String, help: String, page target: Page) -> some View {
        Button {
            dismiss(target)
        } label: {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(hoveredNav == icon ? DS.accent : DS.textTertiary)
                .frame(width: 30, height: 30)
                .background(Circle().fill(DS.surface1))
                .overlay(Circle().strokeBorder(DS.hairline))
        }
        .buttonStyle(.plain)
        .help(help)
        .onHover { h in if h { hoveredNav = icon } else if hoveredNav == icon { hoveredNav = nil } }
    }

    private var hero: some View {
        VStack(spacing: 14) {
            HStack(spacing: 7) {
                statusDot(phaseDotColor)
                Text(phaseTitle)
                    .font(.system(size: 11, weight: .medium))
                    .tracking(2)
                    .foregroundStyle(DS.textTertiary)
            }

            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text("¥")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(DS.accent.opacity(0.75))
                Text(model.displayEarnedToday.formatted(.number.precision(.fractionLength(2))))
                    .font(.system(size: 64, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.accent)
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
                    .contentTransition(.numericText(value: model.displayEarnedToday))
                    .animation(
                        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
                            ? nil
                            : .linear(duration: 0.2),
                        value: model.displayEarnedToday
                    )
            }

            Text(phaseDetail)
                .font(.system(size: 12.5))
                .foregroundStyle(DS.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }

    private var phaseDotColor: Color {
        switch model.phase {
        case .working: return DS.positive
        case .resting: return DS.accent
        case .dayOff: return DS.textTertiary
        default: return DS.textTertiary
        }
    }

    private var phaseTitle: String {
        if !model.todayIsWorkday { return "今天不上班 · 好好休息" }
        if let lv = model.todayLeave {
            return lv.deduct ? "今日请假 · 扣薪" : "今日请假 · 带薪"
        }
        switch model.phase {
        case .noWork: return "今天还没有安排上班时间段"
        case .beforeWork: return "还没到上班时间"
        case .working: return "正在赚钱中"
        case .resting: return "休息中收入暂停"
        case .afterWork: return "今日收入已到账"
        case .dayOff: return "今天不上班"
        }
    }

    private var phaseDetail: String {
        if !model.todayIsWorkday {
            return "今天不在本月工作日历中，收入已暂停"
        }
        if let lv = model.todayLeave {
            if lv.deduct {
                return lv.note.isEmpty ? "今天收入已暂停 · 记一笔加班仍会计入" : "备注：\(lv.note)"
            } else {
                return lv.note.isEmpty ? "带薪假 · 收入照常累计" : "带薪假 · 备注：\(lv.note)"
            }
        }
        var extra = ""
        if model.todayOvertimeFee > 0 {
            extra = " · 已含加班费 +¥\(Fmt.money(model.todayOvertimeFee))"
        }
        switch model.phase {
        case .noWork:
            return "点击右上角齿轮打开设置，添加上班时间段"
        case .beforeWork:
            return "\(Fmt.duration(model.remainingSeconds)) 后开始上班\(extra)"
        case .working:
            return "已工作 \(Fmt.duration(model.elapsedWorkSeconds)) · 距下班还有 \(Fmt.duration(model.remainingSeconds))\(extra)"
        case .resting:
            return "\(Fmt.duration(model.remainingSeconds)) 后继续开工 · 已工作 \(Fmt.duration(model.elapsedWorkSeconds))\(extra)"
        case .afterWork:
            return "今日共工作 \(Fmt.duration(model.workSeconds)) · 日薪已全部到账\(extra)"
        case .dayOff:
            return "今天不上班"
        }
    }

    private var progressBar: some View {
        VStack(spacing: 8) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(DS.surface2)
                    Capsule()
                        .fill(DS.accent)
                        .frame(width: max(0, geo.size.width * model.todayProgress))
                        .animation(.easeOut(duration: 0.35), value: model.todayProgress)
                }
            }
            .frame(height: 6)

            HStack(spacing: 5) {
                Text("今日进度")
                    .foregroundStyle(DS.textTertiary)
                Text("\(Int((model.todayProgress * 100).rounded()))%")
                    .foregroundStyle(DS.textSecondary)
                    .monospacedDigit()
                Spacer()
                Text(model.mergedIntervals.isEmpty
                     ? "未设置上班时间段"
                     : model.mergedIntervals.map { "\(Fmt.time($0.start)) – \(Fmt.time($0.end))" }
                         .joined(separator: " · "))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .foregroundStyle(DS.textTertiary)
            }
            .font(.system(size: 10.5))
        }
    }

    private var statsRow: some View {
        HStack(spacing: 12) {
            statCard(title: "日薪", value: model.dailyIncome, fraction: 2)
            statCard(title: "时薪", value: model.hourlyIncome, fraction: 2)
            statCard(title: "每秒", value: model.perSecondIncome, fraction: 4)
        }
    }

    private func statCard(title: String, value: Double, fraction: Int) -> some View {
        VStack(spacing: 5) {
            Text(title)
                .font(.system(size: 10, weight: .medium))
                .tracking(1.5)
                .foregroundStyle(DS.textTertiary)
            Text("¥\(value.formatted(.number.precision(.fractionLength(fraction))))")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(DS.textPrimary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 12).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(DS.hairline))
    }

    private var monthEarnedRow: some View {
        HStack {
            Text("本月已挣")
                .font(.system(size: 10.5, weight: .medium))
                .tracking(1.5)
                .foregroundStyle(DS.textTertiary)
            Spacer()
            Text("¥\(Fmt.money(model.monthEarnings(model.currentMonth)))")
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(DS.accent)
                .contentTransition(.numericText(value: model.monthEarnings(model.currentMonth)))
                .animation(.linear(duration: 0.2), value: model.monthEarnings(model.currentMonth))
            Text("· 本月工作日 \(model.currentWorkdayCount) 天")
                .font(.system(size: 10.5))
                .monospacedDigit()
                .foregroundStyle(DS.textTertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 12).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(DS.hairline))
    }

    private var nextTickView: some View {
        HStack(spacing: 4) {
            Image(systemName: "clock.arrow.circlepath")
            if let next = model.nextTick {
                Text("整点模式 · 下次刷新 \(Fmt.time(next))")
            } else {
                Text("整点模式 · 等待整点刷新")
            }
        }
        .font(.system(size: 10.5))
        .foregroundStyle(DS.textTertiary)
    }
}

// MARK: - 设置页

struct SettingsPage: View {
    @ObservedObject var model: AppModel
    @ObservedObject var rolesManager: RolesManager
    var onClose: () -> Void
    var onOpenCalendar: () -> Void

    @AppState private var editingRole: Role? = nil
    @AppState private var addingRole = false
    @AppState private var deletingRole: Role? = nil

    var body: some View {
        VStack(spacing: 18) {
            topBar

            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 16) {
                    roleCard
                    incomeCard
                    segmentsCard
                    refreshCard
                }
                .padding(.bottom, 8)
            }
        }
        .padding(28)
        .frame(minWidth: 440, idealWidth: 440, maxWidth: 440, minHeight: 520, idealHeight: 520, maxHeight: 520)
    }

    // MARK: 角色管理

    private var roleCard: some View {
        VStack(spacing: 10) {
            cardTitle("角色")

            ForEach(rolesManager.roles) { role in
                roleRow(role)
            }

            Button {
                addingRole = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "plus")
                    Text("添加角色")
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(DS.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(DS.accent.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4]))
                )
            }
            .buttonStyle(.plain)
            .popover(isPresented: $addingRole) {
                RoleNameEditor(title: "添加角色", initialName: "", confirmLabel: "创建") { name in
                    rolesManager.addRole(name: name)
                    addingRole = false
                }
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
        .confirmationDialog(
            deletingRole.map { "删除角色「\($0.name)」？" } ?? "",
            isPresented: Binding(get: { deletingRole != nil }, set: { if !$0 { deletingRole = nil } }),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let role = deletingRole { rolesManager.delete(role.id) }
                deletingRole = nil
            }
            Button("取消", role: .cancel) { deletingRole = nil }
        } message: {
            Text("该角色下的所有记账数据都会被永久删除，且无法恢复。")
        }
    }

    private func roleRow(_ role: Role) -> some View {
        let isActive = role.id == rolesManager.activeRoleId
        return HStack(spacing: 10) {
            Button {
                rolesManager.switchTo(role.id)
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: isActive ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 14))
                        .foregroundStyle(isActive ? DS.accent : DS.textTertiary)
                    Text(role.name)
                        .font(.system(size: 13, weight: isActive ? .semibold : .regular))
                        .foregroundStyle(DS.textPrimary)
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(isActive ? "当前正在使用的角色" : "切换到「\(role.name)」")

            Button {
                editingRole = role
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 11))
                    .foregroundStyle(DS.textTertiary)
            }
            .buttonStyle(.borderless)
            .help("改名")
            .popover(item: editingRoleBinding(for: role)) { role in
                RoleNameEditor(title: "改名", initialName: role.name, confirmLabel: "保存") { name in
                    rolesManager.rename(role.id, to: name)
                    editingRole = nil
                }
            }

            if !role.isPrimary {
                Button(role: .destructive) {
                    deletingRole = role
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.red.opacity(0.75))
                }
                .buttonStyle(.borderless)
                .help("删除该角色")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 10).fill(DS.surface2))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DS.hairline))
    }

    /// SwiftUI 的 popover(item:) 需要一个 Binding<Role?>，这里只在这一行对应的角色被选中编辑时才非 nil
    private func editingRoleBinding(for role: Role) -> Binding<Role?> {
        Binding(
            get: { editingRole?.id == role.id ? editingRole : nil },
            set: { if $0 == nil { editingRole = nil } }
        )
    }

    private var topBar: some View {
        ZStack {
            Text("设置")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(DS.textSecondary)

            HStack {
                Button(action: onClose) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text("返回")
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(DS.accent)
                }
                .buttonStyle(.plain)
                .help("返回主页面")
                Spacer()
            }
        }
    }

    private var incomeCard: some View {
        VStack(spacing: 15) {
            cardTitle("收入参数")

            HStack {
                Text("月薪（元）").rowLabel
                Spacer()
                numberField($model.monthlySalary)
            }

            Button(action: onOpenCalendar) {
                HStack {
                    Text("工作日历").rowLabel
                    Spacer()
                    Text("本月 \(model.currentWorkdayCount) 天 · 去设置")
                        .font(.system(size: 12.5, weight: .medium))
                        .monospacedDigit()
                        .foregroundStyle(DS.accent)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(DS.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("在日历上勾选本月上班的日子，支持请假/加班记账")

            HStack {
                Text("时薪").rowLabel.foregroundStyle(DS.textSecondary)
                Spacer()
                Text("¥\(model.hourlyIncome.formatted(.number.precision(.fractionLength(2))))")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.textSecondary)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private var segmentsCard: some View {
        VStack(spacing: 13) {
            cardTitle("上班时间段")

            ForEach($model.segments) { $seg in
                HStack(spacing: 10) {
                    DatePicker("", selection: timeBinding($seg, isStart: true),
                               displayedComponents: .hourAndMinute)
                        .labelsHidden()
                        .frame(width: 92)
                    Image(systemName: "arrow.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(DS.textTertiary)
                    DatePicker("", selection: timeBinding($seg, isStart: false),
                               displayedComponents: .hourAndMinute)
                        .labelsHidden()
                        .frame(width: 92)
                    Spacer()
                    Text(durationText(seg.durationMinutes))
                        .font(.system(size: 11))
                        .foregroundStyle(DS.textTertiary)
                        .frame(minWidth: 68, alignment: .trailing)
                    Button(role: .destructive) {
                        model.segments.removeAll { $0.id == seg.id }
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.red.opacity(0.75))
                    }
                    .buttonStyle(.borderless)
                    .help("删除该时间段")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(RoundedRectangle(cornerRadius: 10).fill(DS.surface2))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DS.hairline))
            }

            Button {
                model.segments.append(WorkSegment(startMinutes: 14 * 60, endMinutes: 18 * 60))
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "plus")
                    Text("添加时间段")
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(DS.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(DS.accent.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [4]))
                )
            }
            .buttonStyle(.plain)

            HStack {
                Text("每天工作时长").rowLabel.foregroundStyle(DS.textSecondary)
                Spacer()
                Text(durationText(Int((model.workSeconds / 60).rounded())) + "（按时间段自动计算）")
                    .font(.system(size: 12))
                    .foregroundStyle(DS.textTertiary)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private var refreshCard: some View {
        VStack(spacing: 13) {
            cardTitle("刷新频率")

            Picker("", selection: $model.updateMode) {
                ForEach(UpdateMode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)

            Text("当前：\(model.updateMode.description) · 金额按所选频率自动跳动")
                .font(.system(size: 11))
                .foregroundStyle(DS.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private func cardTitle(_ text: String) -> some View {
        HStack {
            Text(text)
                .font(.system(size: 10.5, weight: .semibold))
                .tracking(1.5)
                .foregroundStyle(DS.textTertiary)
            Spacer()
        }
    }

    private func numberField(_ binding: Binding<Double>) -> some View {
        TextField("", value: binding, format: .number)
            .textFieldStyle(.plain)
            .font(.system(size: 13, design: .rounded))
            .multilineTextAlignment(.trailing)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(width: 150)
            .background(RoundedRectangle(cornerRadius: 8).fill(DS.surface2))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(DS.hairline))
    }

    private func timeBinding(_ seg: Binding<WorkSegment>, isStart: Bool) -> Binding<Date> {
        Binding(
            get: {
                let cal = Calendar.current
                var c = cal.dateComponents([.year, .month, .day], from: Date())
                let mins = isStart ? seg.wrappedValue.startMinutes : seg.wrappedValue.endMinutes
                c.hour = (mins % 1440) / 60
                c.minute = (mins % 1440) % 60
                return cal.date(from: c) ?? Date()
            },
            set: { newValue in
                let c = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                let mins = (c.hour ?? 0) * 60 + (c.minute ?? 0)
                if isStart {
                    seg.wrappedValue.startMinutes = mins
                } else {
                    seg.wrappedValue.endMinutes = mins
                }
            }
        )
    }

    private func durationText(_ minutes: Int) -> String {
        let h = minutes / 60
        let m = minutes % 60
        if h > 0 && m > 0 { return "\(h) 小时 \(m) 分" }
        if h > 0 { return "\(h) 小时" }
        return "\(m) 分钟"
    }
}

// MARK: - 角色名称编辑弹层（新增/改名共用）

struct RoleNameEditor: View {
    let title: String
    let confirmLabel: String
    var onConfirm: (String) -> Void

    @AppState private var name: String

    init(title: String, initialName: String, confirmLabel: String, onConfirm: @escaping (String) -> Void) {
        self.title = title
        self.confirmLabel = confirmLabel
        self.onConfirm = onConfirm
        self._name = AppState(wrappedValue: initialName)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .foregroundStyle(DS.textPrimary)

            TextField("角色名称", text: $name)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 8).fill(DS.surface2))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(DS.hairline))

            Button {
                let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                onConfirm(trimmed)
            } label: {
                Text(confirmLabel)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 8).fill(DS.accent))
            }
            .buttonStyle(.plain)
            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(16)
        .frame(width: 240)
    }
}

extension Text {
    var rowLabel: some View {
        font(.system(size: 13)).foregroundStyle(DS.textPrimary)
    }
}

// MARK: - 多角色管理

final class RolesManager: ObservableObject {
    @Published private(set) var roles: [Role]
    @Published private(set) var activeRoleId: UUID
    @Published private(set) var model: AppModel

    init() {
        let payload = RolesStore.load()
        roles = payload.roles
        activeRoleId = payload.activeRoleId
        let active = payload.roles.first { $0.id == payload.activeRoleId } ?? payload.roles[0]
        let m = AppModel(role: active)
        model = m
        bind(m)
    }

    func switchTo(_ id: UUID) {
        guard id != activeRoleId, let role = roles.first(where: { $0.id == id }) else { return }
        activeRoleId = id
        let m = AppModel(role: role)
        model = m
        bind(m)
        persist()
    }

    func addRole(name: String) {
        roles.append(Role(name: name, settings: EarningsSettings(), ledger: LedgerData()))
        persist()
    }

    func rename(_ id: UUID, to name: String) {
        guard let i = roles.firstIndex(where: { $0.id == id }) else { return }
        roles[i].name = name
        persist()
    }

    /// 主账户不允许删除；删的是当前激活角色时，自动切到列表里第一个剩下的角色
    func delete(_ id: UUID) {
        guard roles.count > 1, let i = roles.firstIndex(where: { $0.id == id }), !roles[i].isPrimary else { return }
        roles.remove(at: i)
        if activeRoleId == id {
            switchTo(roles[0].id)
        } else {
            persist()
        }
    }

    private func bind(_ m: AppModel) {
        m.onChange = { [weak self, weak m] settings, ledger in
            guard let self, let m, let i = self.roles.firstIndex(where: { $0.id == m.roleId }) else { return }
            self.roles[i].settings = settings
            self.roles[i].ledger = ledger
            self.persist()
        }
    }

    private func persist() {
        RolesStore.save(.init(roles: roles, activeRoleId: activeRoleId))
    }
}

// MARK: - 入口

@main
struct TodayEarningsApp: App {
    @StateObject private var rolesManager = RolesManager()

    init() {
        // macOS 会把窗口尺寸自动存进 UserDefaults（键名形如 "NSWindow Frame ..."），
        // 下次启动直接按记住的尺寸开窗，导致改代码里的 idealWidth/idealHeight 也不生效。
        // 这里在窗口创建前清掉这些记录，确保每次打开都严格按最小尺寸展示。
        let d = UserDefaults.standard
        for key in d.dictionaryRepresentation().keys where key.hasPrefix("NSWindow Frame") {
            d.removeObject(forKey: key)
        }
    }

    var body: some Scene {
        WindowGroup("今天挣了多少钱") {
            ContentView(model: rolesManager.model, rolesManager: rolesManager)
        }
        .windowResizability(.contentSize)
    }
}
