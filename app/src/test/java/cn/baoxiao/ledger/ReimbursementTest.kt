package cn.baoxiao.ledger

import org.junit.Assert.*
import org.junit.Test

class ReimbursementTest {
    private fun record(paid: Long = 10000, refunded: Long = 0, requested: Long = paid,
                       received: Long = 0, status: ClaimStatus = ClaimStatus.PREPARING,
                       deleted: Boolean = false, date: String = "2026-10-25"): ExpenseRecord {
        val expense = Expense(paid = paid, refunded = refunded, requested = requested,
            status = status.name, deleted = deleted, date = date)
        return ExpenseRecord(expense, emptyList(), if (received > 0)
            listOf(Receipt(expenseId = expense.id, amount = received, date = "2026-10-07")) else emptyList())
    }

    @Test fun fullArrivalAutomaticallyRecordsTheEntireNetExpense() {
        val settled = record(paid = 12345, refunded = 345).receiveFully()
        assertEquals(12000L, settled.received)
        assertEquals(12000L, settled.expense.requested)
        assertEquals(0L, settled.outstanding)
        assertEquals(ClaimStatus.RECEIVED.name, settled.expense.status)
    }

    @Test fun oldPartialArrivalIsPreservedAndOnlyTheRemainderIsAdded() {
        val old = record(paid = 10000, refunded = 1000, requested = 7000, received = 2000)
        val settled = old.receiveFully()
        assertEquals(old.receipts.first(), settled.receipts.first())
        assertEquals(7000L, settled.receipts.last().amount)
        assertEquals(9000L, settled.received)
        assertEquals(0L, settled.outstanding)
    }

    @Test fun repeatedFullArrivalDoesNotDuplicateMoneyOrHistory() {
        val settled = record().receiveFully()
        assertEquals(settled, settled.receiveFully())
        assertEquals(1, settled.receiveFully().receipts.size)
    }

    @Test fun oldOverpaymentDoesNotCreateNegativeArrivalOrAdvance() {
        val settled = record(received = 11000).receiveFully()
        assertEquals(11000L, settled.received)
        assertEquals(1, settled.receipts.size)
        assertEquals(0L, settled.outstanding)
    }

    @Test fun completelyRefundedExpenseCanBeSettledWithoutAZeroReceipt() {
        val settled = record(refunded = 10000).receiveFully()
        assertTrue(settled.receipts.isEmpty())
        assertEquals(ClaimStatus.RECEIVED.name, settled.expense.status)
        assertEquals(0L, settled.outstanding)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cancelledClaimCannotReceiveMoney() {
        record(status = ClaimStatus.CANCELLED).receiveFully()
    }

    @Test(expected = IllegalArgumentException::class)
    fun deletedClaimCannotReceiveMoney() {
        record(deleted = true).receiveFully()
    }

    @Test fun cancelledAndDeletedClaimsAreExcludedFromEveryTotalAndMonth() {
        val pending = record(paid = 10000, received = 2000)
        val submitted = record(paid = 5000, received = 1000, status = ClaimStatus.SUBMITTED)
        val received = record(paid = 3000, received = 3000, status = ClaimStatus.RECEIVED)
        val cancelled = record(paid = 90000, received = 20000, status = ClaimStatus.CANCELLED, date = "2026-09-01")
        val deleted = record(paid = 80000, received = 40000, deleted = true, date = "2026-08-01")
        val totals = ledgerTotals(listOf(pending, submitted, received, cancelled, deleted))
        assertEquals(18000L, totals.spent)
        assertEquals(6000L, totals.received)
        assertEquals(12000L, totals.outstanding)
        assertEquals(8000L, totals.pending)
        assertEquals(4000L, totals.submitted)
        assertEquals(mapOf("2026-10" to 18000L), totals.monthly)
        assertEquals(20000L, cancelled.received)
        assertEquals(0L, cancelled.outstanding)
    }

    @Test fun restoringCancelledPartialClaimBringsItsAmountsBack() {
        val cancelled = record(received = 2000, status = ClaimStatus.CANCELLED)
        val restored = cancelled.restoreClaim()
        assertEquals(cancelled.receipts, restored.receipts)
        assertEquals(ClaimStatus.PREPARING.name, restored.expense.status)
        assertEquals(8000L, ledgerTotals(listOf(restored)).outstanding)
        assertEquals(10000L, ledgerTotals(listOf(restored)).spent)
    }

    @Test fun restoringCancelledPaidClaimKeepsItFullyReceived() {
        val restored = record(received = 10000, status = ClaimStatus.CANCELLED).restoreClaim()
        assertEquals(ClaimStatus.RECEIVED.name, restored.expense.status)
        assertEquals(0L, restored.outstanding)
        assertEquals(10000L, ledgerTotals(listOf(restored)).received)
    }

    @Test fun restoringTrashDoesNotReincludeACancelledClaim() {
        val cancelled = record(status = ClaimStatus.CANCELLED, deleted = true)
        val restoredFromTrash = cancelled.copy(expense = cancelled.expense.copy(deleted = false))
        assertFalse(restoredFromTrash.isCounted)
        assertEquals(0L, ledgerTotals(listOf(restoredFromTrash)).spent)
    }
}
