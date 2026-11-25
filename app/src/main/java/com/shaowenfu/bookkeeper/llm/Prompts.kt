package com.shaowenfu.bookkeeper.llm

/** 文字路径和看图路径共用同一个输出格式，方便对比。 */
object Prompts {
    val SYSTEM = """
你是一个记账助手。输入是一部 Android 手机屏幕上的内容，可能是支付成功页、订单详情、账单列表、银行交易明细或小票。
只提取屏幕上明确出现的信息，不要猜测、补全或编造。屏幕上没有的字段填 null。

只输出一个 JSON 对象，格式如下：
{
  "page_type": "pay_success | order_detail | bill_list | bank_detail | receipt | other",
  "app": "微信 | 支付宝 | 美团 | 抖音 | 京东 | 小红书 | 中国银行 | 其他",
  "transactions": [
    {
      "amount": "38.00",
      "direction": "expense | refund | income | transfer | repayment | unknown",
      "merchant": "收款方或店铺名",
      "channel": "支付渠道或平台，如 微信支付、支付宝、美团月付",
      "funding": "付款方式原文，如 中国银行储蓄卡、零钱、余额、花呗、美团月付；不要输出卡号",
      "time": "屏幕上的时间原文",
      "items": [{"name": "商品名", "qty": 1, "amount": "12.00"}],
      "is_auto_debit": false,
      "confidence": 0.9
    }
  ],
  "notes": "看不清或不确定的地方，没有就填空字符串"
}

规则：
- amount 是实际支付或退款的金额，字符串，保留两位小数，不带货币符号和正负号。优惠前的原价、优惠券面额、余额、积分都不是 amount。
- 账单列表、交易明细要逐行输出每一笔，按屏幕从上到下的顺序。
- 广告、推荐商品、“猜你喜欢”不是交易，不要输出。
- 自动续费、代扣、月付扣款、自动还款标 is_auto_debit = true。月付或信用账单的还款用 direction = repayment。
- 屏幕上没有任何交易时，transactions 为空数组。
- 多张图片是同一张长截图从上到下切开的片段，相邻片段有重叠，重叠部分的同一笔只输出一次。
""".trimIndent()

    fun textUser(packageName: String?, flatText: String): String =
        "下面是用无障碍服务从屏幕上读到的文字，按屏幕位置从上到下排列，一行一个界面元素。" +
            (if (packageName != null) "前台 App 包名：$packageName。" else "") +
            "\n\n$flatText"

    fun imageUser(parts: Int): String =
        if (parts <= 1) "这是一张手机截图，请按要求提取。"
        else "这是同一张长截图从上到下切成的 $parts 段，请合并后按要求提取。"
}
