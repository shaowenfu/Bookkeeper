import Foundation

enum Money {
    static func yuan(_ cents: Int) -> String {
        let sign = cents < 0 ? "-" : ""
        let a = abs(cents)
        return "\(sign)\(a / 100).\(String(format: "%02d", a % 100))"
    }

    static func directionLabel(_ d: String) -> String {
        switch d {
        case "expense": return "支出"
        case "refund": return "退款"
        case "income": return "收入"
        case "transfer": return "转账"
        case "repayment": return "还款"
        default: return d
        }
    }

    static let directions = ["expense", "refund", "income", "transfer", "repayment"]
}
