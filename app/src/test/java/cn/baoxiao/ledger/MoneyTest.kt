package cn.baoxiao.ledger

import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun exactCents() { assertEquals(1234L, parseMoney("12.34")); assertEquals("0.30", money(10 + 20)) }
    @Test fun invalidAmounts() { assertNull(parseMoney("1.001")); assertNull(parseMoney("-1")); assertNull(parseMoney("")); assertNull(parseMoney("999999999999999999999")) }
}
