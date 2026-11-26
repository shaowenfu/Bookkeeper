package com.shaowenfu.bookkeeper.recognize

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prompts {
    val CATEGORIES = listOf(
        "餐饮", "交通", "日用购物", "数码与软件订阅", "学习与科研", "运动",
        "社交与礼物", "住房与生活缴费", "医疗", "娱乐", "其他",
    )

    val SYSTEM = """
你是一个记账助手。输入是一部 Android 手机屏幕的截图，有时附带从屏幕读到的文字。页面通常是支付成功页、订单详情、账单列表、银行交易明细或小票。
只提取屏幕上明确出现的信息，不要猜测、补全或编造。屏幕上没有的字段填 null。

只输出一个 JSON 对象：
{
  "page_type": "pay_success | order_detail | bill_list | bank_detail | receipt | other",
  "app": "微信 | 支付宝 | 美团 | 抖音 | 京东 | 小红书 | 淘宝 | 饿了么 | 拼多多 | 中国银行 | 其他",
  "transactions": [
    {
      "amount": "38.00",
      "direction": "expense | refund | income | transfer | repayment",
      "merchant": "收款方或店铺名",
      "channel": "支付渠道，如 微信支付、支付宝、美团月付、抖音月付",
      "funding": "付款方式，如 中国银行储蓄卡、零钱、余额、花呗；不要输出卡号",
      "time": "YYYY-MM-DD HH:mm",
      "category": "${CATEGORIES.joinToString(" | ")}",
      "items": [{"name": "商品名", "qty": 1, "amount": "12.00"}],
      "is_auto_debit": false,
      "confidence": 0.9
    }
  ],
  "notes": "看不清或不确定的地方，没有就填空字符串"
}

规则：
- amount 是实际支付或退款的金额，字符串，两位小数，不带货币符号和正负号。原价、优惠券面额、余额、积分都不是 amount。
- time 是交易时间，换算成 YYYY-MM-DD HH:mm。“今天”“昨天”、只有月日没有年份的，按输入里给出的当前时间推算。屏幕上没有交易时间就填 null，不要用状态栏的时钟。
- 账单列表、交易明细要逐行输出每一笔，按屏幕从上到下的顺序。
- 广告、推荐商品、“猜你喜欢”不是交易。
- 自动续费、代扣、月付扣款、自动还款标 is_auto_debit = true。月付或信用账单的还款用 direction = repayment。
- category 按买的东西选；看不出来选“其他”。
- 屏幕上没有任何交易时，transactions 为空数组。
- 多张图片是同一张长截图从上到下切开的片段，相邻片段有重叠，重叠部分的同一笔只输出一次。
""".trimIndent()

    private val appNames = mapOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.jingdong.app.mall" to "京东",
        "com.xingin.xhs" to "小红书",
        "com.taobao.taobao" to "淘宝",
        "me.ele" to "饿了么",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.taobao.idlefish" to "闲鱼",
    )

    fun appName(pkg: String?): String? = pkg?.let { appNames[it] }

    fun user(createdAt: Long, packageName: String?, imageParts: Int, maskedText: String?): String = buildString {
        append("当前时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date(createdAt))).append("。")
        if (packageName != null) {
            append("截图时的前台 App：").append(appName(packageName) ?: packageName).append("（").append(packageName).append("）。")
        }
        when {
            imageParts == 1 -> append("附一张截图。")
            imageParts > 1 -> append("附同一张长截图从上到下切成的 ").append(imageParts).append(" 段。")
        }
        if (!maskedText.isNullOrBlank()) {
            append("\n\n下面是用无障碍服务从屏幕读到的文字，按位置从上到下排列，可用来核对数字：\n")
            append(maskedText)
        }
    }
}
