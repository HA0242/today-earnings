import SwiftUI

// MARK: - 统计·月度页（自然月挣钱情况 + 请假/加班明细）

struct StatsPage: View {
    @ObservedObject var model: AppModel
    var onClose: () -> Void

    @AppState private var displayed: Date = Date()

    private var month: MonthInfo { MonthInfo(date: displayed) }
    private var isCurrentMonth: Bool { month == MonthInfo.current() }
    private var todayDay: Int { Calendar.current.component(.day, from: model.now) }
    /// "已过 X 天"统计到哪一天：当月 = 今天，历史月 = 月末
    private var lastDay: Int { isCurrentMonth ? todayDay : month.daysInMonth }
    private var records: [MonthDayRecord] { model.monthRecords(month) }

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: 16) {
                topBar

                monthTotalCard

                summaryCards

                if !records.isEmpty {
                    recordsCard
                }
            }
            .padding(28)
        }
        .frame(minWidth: 500, idealWidth: 500, maxWidth: 500, minHeight: 620, idealHeight: 620, maxHeight: 620)
    }

    // MARK: 顶部导航（返回 + 月份切换）

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

                Text("\(month.title) 挣钱报告")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.textSecondary)

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

    // MARK: 月度累计卡

    private var monthTotalCard: some View {
        let s = model.monthSummary(month)
        return VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text("¥")
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundStyle(DS.accent.opacity(0.75))
                Text(Fmt.money(model.monthEarnings(month)))
                    .font(.system(size: 46, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(DS.accent)
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
                    .contentTransition(.numericText(value: model.monthEarnings(month)))
                    .animation(.linear(duration: 0.2), value: model.monthEarnings(month))
            }

            HStack(spacing: 16) {
                Text("预计全月 ¥\(Fmt.money(s.expectedMonth))")
                    .font(.system(size: 10.5))
                    .monospacedDigit()
                    .foregroundStyle(DS.textSecondary)
                Text("工作日 \(s.workdays) 天")
                    .font(.system(size: 10.5))
                    .monospacedDigit()
                    .foregroundStyle(DS.textSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    // MARK: 汇总三卡

    private var elapsedWorkdays: Int {
        (1...max(lastDay, 1)).filter { model.workdays(in: month).contains($0) }.count
    }

    private var summaryCards: some View {
        let s = model.monthSummary(month)
        return HStack(spacing: 12) {
            summaryCard(icon: "briefcase.fill", tint: DS.accent,
                        title: "工作日",
                        value: "\(s.workdays) 天",
                        sub: isCurrentMonth ? "已过 \(elapsedWorkdays) 天" : nil)
            summaryCard(icon: "figure.walk.departure", tint: DS.negative,
                        title: "请假",
                        value: "\(s.leaveDays) 天",
                        sub: s.leaveDays > 0
                            ? "扣薪 \(s.leaveDeductDays) · 带薪 \(s.leaveDays - s.leaveDeductDays)"
                            : nil)
            summaryCard(icon: "moon.fill", tint: DS.positive,
                        title: "加班",
                        value: String(format: "%.1f 小时", s.overtimeHours),
                        sub: s.overtimeFee > 0
                            ? "加班 \(String(format: "%.1f", s.overtimeHours)) 小时 · +¥\(Fmt.money(s.overtimeFee))"
                            : "加班 \(String(format: "%.1f", s.overtimeHours)) 小时")
        }
    }

    private func summaryCard(icon: String, tint: Color, title: String, value: String, sub: String? = nil) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 16, height: 16)
                .foregroundStyle(tint)

            Text(title)
                .font(.system(size: 10))
                .tracking(1)
                .foregroundStyle(DS.textTertiary)

            Text(value)
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(DS.textPrimary)

            if let sub {
                Text(sub)
                    .font(.system(size: 9.5))
                    .monospacedDigit()
                    .foregroundStyle(DS.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 12).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(DS.hairline))
    }

    // MARK: 当月记录明细

    private var recordsCard: some View {
        VStack(spacing: 6) {
            HStack {
                Text("当月记录")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.5)
                    .foregroundStyle(DS.textTertiary)
                Spacer()
                Text("\(records.count) 条")
                    .font(.system(size: 10.5))
                    .monospacedDigit()
                    .foregroundStyle(DS.textTertiary)
            }
            .padding(.bottom, 4)

            ForEach(records) { r in
                recordRow(r)
                if r.id != records.last?.id {
                    Rectangle().fill(DS.hairline).frame(height: 1)
                }
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(DS.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(DS.hairline))
    }

    private func recordRow(_ r: MonthDayRecord) -> some View {
        let date = month.date(r.day)
        let impact = model.recordImpact(month, day: r.day)

        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text("\(Fmt.day(date)) · \(Fmt.weekdayText(date))")
                        .font(.system(size: 12, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(DS.textPrimary)

                    if isCurrentMonth && r.day == todayDay {
                        Text("今天")
                            .font(.system(size: 9, weight: .semibold))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1.5)
                            .background(Capsule().fill(DS.accent.opacity(0.15)))
                            .foregroundStyle(DS.accent)
                    }
                }

                HStack(spacing: 6) {
                    if let lv = r.record.leave {
                        tag(lv.deduct ? "请假 · 扣薪" : "请假 · 带薪",
                            color: lv.deduct ? DS.negative : DS.accent)
                    }
                    if let ot = r.record.overtime {
                        tag("加班 \(String(format: "%.1f", ot.hours)) 小时\(ot.paid ? "" : " · 不加钱")",
                            color: DS.positive)
                    }
                    if let note = r.record.leave?.note, !note.isEmpty {
                        Text(note)
                            .font(.system(size: 10))
                            .foregroundStyle(DS.textTertiary)
                            .lineLimit(1)
                    }
                }
            }

            Spacer()

            impactText(impact, record: r.record)
        }
        .padding(.vertical, 8)
    }

    private func tag(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 9.5, weight: .medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(color.opacity(0.13)))
            .foregroundStyle(color)
    }

    private func impactText(_ impact: Double, record rec: DayRecord) -> some View {
        Group {
            if impact > 0.005 {
                Text("+¥\(Fmt.money(impact))")
                    .foregroundStyle(DS.positive)
            } else if impact < -0.005 {
                Text("-¥\(Fmt.money(-impact))")
                    .foregroundStyle(DS.negative)
            } else if rec.leave != nil {
                Text("不扣钱")
                    .foregroundStyle(DS.textTertiary)
            } else {
                Text("不加钱")
                    .foregroundStyle(DS.textTertiary)
            }
        }
        .font(.system(size: 12.5, weight: .bold, design: .rounded))
        .monospacedDigit()
    }
}
