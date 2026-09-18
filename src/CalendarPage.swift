import SwiftUI

// MARK: - 日历·月度页（工作日勾选 + 请假/加班记账 + 月度汇总）

struct CalendarPage: View {
    @ObservedObject var model: AppModel
    var onClose: () -> Void

    @AppState private var displayed: Date = Date()
    @AppState private var editingDay: Int? = nil
    @AppState private var hoverDay: Int? = nil

    private var month: MonthInfo { MonthInfo(date: displayed) }

    var body: some View {
        VStack(spacing: 16) {
            topBar

            monthSummaryCard

            calendarGrid

            legendRow
        }
        .padding(28)
        .frame(minWidth: 480, idealWidth: 480, maxWidth: 480, minHeight: 600, idealHeight: 600)
        .onAppear { ensurePlan() }
        .onChange(of: displayed) { _, _ in ensurePlan() }
    }

    // MARK: 顶部导航

    private var topBar: some View {
        ZStack {
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

            HStack(spacing: 18) {
                Button {
                    changeMonth(-1)
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(DS.textTertiary)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("上个月")

                Text(month.title)
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.textPrimary)

                Button {
                    changeMonth(1)
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(isFutureMonth ? DS.textTertiary.opacity(0.4) : DS.textTertiary)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(isFutureMonth)
                .help("下个月")
            }
        }
    }

    private var isFutureMonth: Bool {
        month.year > MonthInfo.current().year
            || (month.year == MonthInfo.current().year && month.month > MonthInfo.current().month)
    }

    private func changeMonth(_ delta: Int) {
        withAnimation(.easeOut(duration: 0.18)) {
            displayed = (delta > 0 ? month.next : month.prev).date(15)
        }
    }

    // MARK: 月度汇总卡

    private var monthSummaryCard: some View {
        let s = model.monthSummary(month)
        return VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text("本月已挣")
                    .font(.system(size: 11, weight: .medium))
                    .tracking(2)
                    .foregroundStyle(DS.textTertiary)
                Spacer()
                Text("¥")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(DS.accent)
                Text(Fmt.money(model.monthEarnings(month)))
                    .font(.system(size: 30, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.accent)
                    .contentTransition(.numericText(value: model.monthEarnings(month)))
                    .animation(.linear(duration: 0.2), value: model.monthEarnings(month))
            }

            HStack(spacing: 0) {
                summaryItem(label: "工作日", value: "\(s.workdays) 天", color: DS.textSecondary)
                summaryDivider
                summaryItem(label: "预计全月", value: "¥\(Fmt.money(s.expectedMonth))", color: DS.textSecondary)
                summaryDivider
                summaryItem(label: "请假扣款", value: s.leaveDeduction > 0 ? "-¥\(Fmt.money(s.leaveDeduction))" : "—",
                            color: s.leaveDeduction > 0 ? DS.negative : DS.textTertiary)
                summaryDivider
                summaryItem(label: "加班费", value: s.overtimeFee > 0 ? "+¥\(Fmt.money(s.overtimeFee))" : "—",
                            color: s.overtimeFee > 0 ? DS.positive : DS.textTertiary)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private var summaryDivider: some View {
        Rectangle().fill(DS.hairline).frame(width: 1, height: 26)
    }

    private func summaryItem(label: String, value: String, color: Color) -> some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(DS.textTertiary)
            Text(value)
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: 月历网格

    private var calendarGrid: some View {
        VStack(spacing: 8) {
            weekdayHeader

            let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 7)
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(0..<month.leadingBlanks, id: \.self) { _ in
                    Color.clear.frame(height: 52)
                }
                ForEach(1...month.daysInMonth, id: \.self) { day in
                    dayCell(day)
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private var weekdayHeader: some View {
        HStack(spacing: 6) {
            ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) { w in
                Text(w)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(DS.textTertiary)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func dayCell(_ day: Int) -> some View {
        let isWorkday = model.workdays(in: month).contains(day)
        let record = model.record(month: month, day: day)
        let hasLeave = record?.leave != nil
        let hasOvertime = record?.overtime != nil
        let isToday = isCurrentMonth && day == todayDay
        let isFutureDay = isCurrentMonth && day > todayDay
        // 框=这天挣钱；底部文字标签：班=上班 假=请假 加=加班；实心=该项有钱，空心=没钱
        let hasBasePay = isWorkday && !(record?.leave?.deduct == true)
        let hasOvertimePay = record?.overtime?.paid == true
        let showBox = hasBasePay || hasOvertimePay
        let dim = isFutureDay ? 0.55 : 1.0

        return VStack(spacing: 4) {
            Text("\(day)")
                .font(.system(size: 14, weight: isToday ? .bold : .medium, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(isWorkday ? DS.accent.opacity(dim) : DS.textTertiary)

            HStack(spacing: 3) {
                if isWorkday && !hasLeave {
                    statusBadge("班", color: DS.accent, filled: true, dim: dim)
                }
                if hasLeave {
                    statusBadge("假", color: DS.negative,
                                filled: !(record?.leave?.deduct == true), dim: dim)
                }
                if hasOvertime {
                    statusBadge("加", color: DS.positive,
                                filled: record?.overtime?.paid == true, dim: dim)
                }
            }
            .frame(height: 13)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 52)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(hoverDay == day ? DS.surface2 : (showBox ? DS.surface2.opacity(0.55) : Color.clear))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(isToday ? DS.accent : (showBox ? DS.hairline : Color.clear),
                              lineWidth: isToday ? 1.5 : 1)
        )
        .contentShape(Rectangle())
        .onTapGesture {
            editingDay = day
        }
        .onHover { h in hoverDay = h ? day : nil }
        .help(cellHelp(day, isWorkday: isWorkday, record: record))
        .popover(isPresented: editingBinding(day)) {
            DayEditor(model: model, month: month, day: day)
        }
        .id("day-\(month.key)-\(day)")
    }

    /// 底部状态小标签：实心=该项挣钱，空心=该项没钱
    private func statusBadge(_ text: String, color: Color, filled: Bool, dim: Double) -> some View {
        Text(text)
            .font(.system(size: 8, weight: .semibold))
            .foregroundStyle(filled ? Color.white : color.opacity(0.75 * dim))
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(Capsule().fill(filled ? color.opacity(dim) : Color.clear))
            .overlay(Capsule().strokeBorder(color.opacity(filled ? 0 : 0.55 * dim), lineWidth: 0.8))
    }

    private func editingBinding(_ day: Int) -> Binding<Bool> {
        Binding(
            get: { editingDay == day },
            set: { showing in
                if !showing && editingDay == day { editingDay = nil }
            }
        )
    }

    private var isCurrentMonth: Bool { month == MonthInfo.current() }

    private var todayDay: Int {
        Calendar.current.component(.day, from: Date())
    }

    private func cellHelp(_ day: Int, isWorkday: Bool, record: DayRecord?) -> String {
        var parts: [String] = []
        parts.append(isWorkday ? "工作日" : "休息日")
        if let lv = record?.leave {
            parts.append(lv.deduct ? "请假 · 扣钱" : "请假 · 不扣钱")
            if !lv.note.isEmpty { parts.append("备注：\(lv.note)") }
        }
        if let ot = record?.overtime {
            parts.append(ot.paid ? "加班 \(ot.hours)h · 加钱" : "加班 \(ot.hours)h · 不加钱")
            if !ot.note.isEmpty { parts.append("备注：\(ot.note)") }
        }
        let earns = (isWorkday && !(record?.leave?.deduct == true)) || (record?.overtime?.paid == true)
        parts.append(earns ? "这天挣钱" : "这天没挣钱")
        parts.append("点击编辑这一天")
        return parts.joined(separator: "\n")
    }

    // MARK: 图例

    private var legendRow: some View {
        VStack(spacing: 6) {
            HStack(spacing: 14) {
                // 带框=这天挣钱
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 4)
                        .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(DS.hairline, lineWidth: 1))
                        .frame(width: 13, height: 13)
                    Text("挣钱")
                }
                legendBadge("班", color: DS.accent, filled: true, label: "上班")
                legendBadge("假", color: DS.negative, filled: true, label: "请假")
                legendBadge("加", color: DS.positive, filled: true, label: "加班")
                Spacer()
                Text("实心=有钱 · 空心=没钱")
            }
            Text("点击任意日期，编辑上班 / 请假 / 加班")
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 10.5))
        .foregroundStyle(DS.textTertiary)
        .padding(.horizontal, 4)
    }

    private func legendBadge(_ text: String, color: Color, filled: Bool, label: String) -> some View {
        HStack(spacing: 5) {
            statusBadge(text, color: color, filled: filled, dim: 1)
            Text(label)
        }
    }

    // MARK: 月计划初始化

    private func ensurePlan() {
        if ledgerPlan == nil {
            let prevPlan = model.ledger.monthlyPlans[month.prev.key]
            model.setPlan(month: month, days: prevPlan ?? month.defaultWorkdays)
        }
    }

    private var ledgerPlan: Set<Int>? {
        model.ledger.monthlyPlans[month.key]
    }
}

// MARK: - 单日编辑弹层

struct DayEditor: View {
    @ObservedObject var model: AppModel
    let month: MonthInfo
    let day: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header

            workdayToggle

            leaveSection

            Divider().overlay(DS.hairline)

            overtimeSection
        }
        .padding(18)
        .frame(width: 300)
    }

    private var record: DayRecord? {
        model.record(month: month, day: day)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("\(month.month)月\(day)日 · \(Fmt.weekdayText(month.date(day)))")
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(DS.textPrimary)
            if let note = model.dayIncomeNote(month: month, day: day) {
                Text(note)
                    .font(.system(size: 10.5))
                    .foregroundStyle(DS.textTertiary)
            }
        }
    }

    private var workdayToggle: some View {
        Toggle(isOn: workdayBinding) {
            Text("这天上班")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(DS.textPrimary)
        }
        .tint(DS.accent)
    }

    private var workdayBinding: Binding<Bool> {
        Binding(
            get: { model.workdays(in: month).contains(day) },
            set: { _ in model.toggleWorkday(month: month, day: day) }
        )
    }

    // MARK: 请假

    private var leaveSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(isOn: leaveOnBinding) {
                Text("请假")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(DS.textPrimary)
            }
            .tint(DS.negative)

            if record?.leave != nil {
                Picker("", selection: leaveDeductBinding) {
                    Text("扣钱").tag(true)
                    Text("不扣钱").tag(false)
                }
                .pickerStyle(.segmented)

                noteField(placeholder: "请假备注（可选）", text: leaveNoteBinding)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(DS.surface2))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DS.hairline))
    }

    private var leaveOnBinding: Binding<Bool> {
        Binding(
            get: { record?.leave != nil },
            set: { on in
                model.setLeave(month: month, day: day,
                               leave: on ? LeaveRecord(deduct: true, note: "") : nil)
            }
        )
    }

    private var leaveDeductBinding: Binding<Bool> {
        Binding(
            get: { record?.leave?.deduct ?? true },
            set: { nv in
                model.updateLeave(month: month, day: day) { $0.deduct = nv }
            }
        )
    }

    private var leaveNoteBinding: Binding<String> {
        Binding(
            get: { record?.leave?.note ?? "" },
            set: { nv in
                model.updateLeave(month: month, day: day) { $0.note = nv }
            }
        )
    }

    // MARK: 加班

    private var overtimeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(isOn: overtimeOnBinding) {
                Text("加班")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(DS.textPrimary)
            }
            .tint(DS.positive)

            if record?.overtime != nil {
                Picker("", selection: otPaidBinding) {
                    Text("加钱").tag(true)
                    Text("不加钱").tag(false)
                }
                .pickerStyle(.segmented)

                HStack {
                    Text("时长")
                        .font(.system(size: 12))
                        .foregroundStyle(DS.textSecondary)
                    Spacer()
                    Stepper(value: otHoursBinding, in: 0.5...24, step: 0.5) {
                        Text(String(format: "%.1f 小时", record?.overtime?.hours ?? 0))
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(DS.textPrimary)
                    }
                    .fixedSize()
                }

                if record?.overtime?.paid == true, let fee = model.overtimeFee(month: month, day: day) {
                    Text("加班费 +¥\(Fmt.money(fee))")
                        .font(.system(size: 10.5, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(DS.positive)
                }

                noteField(placeholder: "加班备注（可选）", text: otNoteBinding)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(DS.surface2))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DS.hairline))
    }

    private var overtimeOnBinding: Binding<Bool> {
        Binding(
            get: { record?.overtime != nil },
            set: { on in
                model.setOvertime(month: month, day: day,
                                  overtime: on ? OvertimeRecord(paid: true, hours: 2, note: "") : nil)
            }
        )
    }

    private var otPaidBinding: Binding<Bool> {
        Binding(
            get: { record?.overtime?.paid ?? true },
            set: { nv in
                model.updateOvertime(month: month, day: day) { $0.paid = nv }
            }
        )
    }

    private var otHoursBinding: Binding<Double> {
        Binding(
            get: { record?.overtime?.hours ?? 2 },
            set: { nv in
                model.updateOvertime(month: month, day: day) { $0.hours = nv }
            }
        )
    }

    private var otNoteBinding: Binding<String> {
        Binding(
            get: { record?.overtime?.note ?? "" },
            set: { nv in
                model.updateOvertime(month: month, day: day) { $0.note = nv }
            }
        )
    }

    private func noteField(placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .textFieldStyle(.plain)
            .font(.system(size: 12))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 8).fill(DS.surface1))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(DS.hairline))
    }
}
