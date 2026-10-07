package cn.baoxiao.ledger

import java.math.BigDecimal
import java.math.RoundingMode

fun parseMoney(value: String): Long? = try {
    val number = BigDecimal(value.trim())
    if (number.signum() < 0) null else number.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
} catch (_: Exception) { null }

fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

enum class ClaimStatus(val label: String) {
    PREPARING("准备材料"), READY("待提交"), SUBMITTED("已提交"), RETURNED("退回补材料"), RECEIVED("已到账"), CANCELLED("取消报销")
}

enum class MaterialKind(val label: String) {
    PAYMENT("支付记录"), BILL("支付账单"), INVOICE("发票"), OTHER("补充材料")
}
