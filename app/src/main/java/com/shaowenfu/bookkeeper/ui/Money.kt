package com.shaowenfu.bookkeeper.ui

import java.math.BigDecimal

object Money {
    fun yuan(cents: Long): String = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()

    /** 支出显示为 ¥26.00，退款和收入带 + 号 */
    fun signed(cents: Long, direction: String): String = when (direction) {
        "refund", "income" -> "+¥${yuan(cents)}"
        else -> "¥${yuan(cents)}"
    }

    fun directionLabel(d: String) = when (d) {
        "expense" -> "支出"
        "refund" -> "退款"
        "income" -> "收入"
        "transfer" -> "转账"
        "repayment" -> "还款"
        else -> d
    }
}
