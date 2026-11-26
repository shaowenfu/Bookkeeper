package com.shaowenfu.bookkeeper.capture

/** 发给模型前的最小脱敏：手机号、长数字串（卡号、证件号、订单号以外的长号码）。 */
object Masking {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val longDigits = Regex("(?<!\\d)\\d{15,19}(?!\\d)")

    fun mask(s: String): String =
        s.replace(longDigits) { m -> "*".repeat(m.value.length - 4) + m.value.takeLast(4) }
            .replace(phone) { m -> m.value.take(3) + "****" + m.value.takeLast(4) }
}
