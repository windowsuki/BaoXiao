package cn.baoxiao.ledger

import org.junit.Assert.*
import org.junit.Test

class ExpenseRecordTest {
    @Test fun refundsAndPartialReimbursements() {
        val expense = Expense(paid = 10000, refunded = 1000, requested = 9000)
        val record = ExpenseRecord(expense, emptyList(), listOf(
            Receipt(expenseId = expense.id, amount = 2000),
            Receipt(expenseId = expense.id, amount = 3000)
        ))
        assertEquals(5000L, record.received)
        assertEquals(4000L, record.outstanding)
    }
    @Test fun materialKindsDetermineMissingItems() {
        val expense = Expense()
        val invoice = Attachment(expenseId = expense.id, name = "发票.pdf", path = "test",
            mime = "application/pdf", kind = MaterialKind.INVOICE.name, hash = "test", size = 1)
        val record = ExpenseRecord(expense, listOf(invoice), emptyList())
        assertEquals(listOf(MaterialKind.PAYMENT.label, MaterialKind.BILL.label), record.missing)
    }
    @Test fun fullyReimbursedHasNoOutstandingAdvance() {
        val expense = Expense(paid = 1234, requested = 1234)
        val record = ExpenseRecord(expense, emptyList(), listOf(Receipt(expenseId = expense.id, amount = 1234)))
        assertEquals(0L, record.outstanding)
    }
}
