package cn.baoxiao.ledger

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "", val paid: Long = 0, val requested: Long = 0,
    val refunded: Long = 0, val date: String = LocalDate.now().toString(),
    val category: String = "采购", val project: String = "", val merchant: String = "",
    val note: String = "", val status: String = ClaimStatus.PREPARING.name,
    val batch: String = "", val deleted: Boolean = false
)

@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = Expense::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("expenseId")])
data class Attachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val expenseId: String,
    val name: String, val path: String, val mime: String, val kind: String,
    val hash: String, val size: Long
)

@Entity(tableName = "receipts", foreignKeys = [ForeignKey(entity = Expense::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("expenseId")])
data class Receipt(@PrimaryKey val id: String = UUID.randomUUID().toString(), val expenseId: String, val amount: Long, val date: String = LocalDate.now().toString())

data class ExpenseRecord(
    @Embedded val expense: Expense,
    @Relation(parentColumn = "id", entityColumn = "expenseId") val attachments: List<Attachment>,
    @Relation(parentColumn = "id", entityColumn = "expenseId") val receipts: List<Receipt>
) {
    val received: Long get() = receipts.sumOf { it.amount }
    val isCounted: Boolean get() = !expense.deleted && expense.status != ClaimStatus.CANCELLED.name
    val remainingReimbursement: Long get() = (expense.netPaid - received).coerceAtLeast(0)
    val outstanding: Long get() = if (isCounted) remainingReimbursement else 0
    val missing: List<String> get() = listOf(MaterialKind.PAYMENT, MaterialKind.BILL, MaterialKind.INVOICE)
        .filter { kind -> attachments.none { it.kind == kind.name } }.map { it.label }

    fun receiveFully(): ExpenseRecord {
        require(isCounted) { "请先恢复这笔报账。" }
        val extra = if (remainingReimbursement > 0)
            listOf(Receipt(expenseId = expense.id, amount = remainingReimbursement)) else emptyList()
        return copy(expense = expense.copy(requested = expense.netPaid, status = ClaimStatus.RECEIVED.name), receipts = receipts + extra)
    }

    fun restoreClaim(): ExpenseRecord {
        require(expense.status == ClaimStatus.CANCELLED.name) { "这笔报账未取消。" }
        val status = if (remainingReimbursement == 0L) ClaimStatus.RECEIVED else ClaimStatus.PREPARING
        return copy(expense = expense.copy(status = status.name))
    }
}

val Expense.netPaid: Long get() = paid - refunded

data class LedgerTotals(
    val spent: Long, val received: Long, val outstanding: Long,
    val pending: Long, val submitted: Long, val monthly: Map<String, Long>
)

fun ledgerTotals(records: List<ExpenseRecord>): LedgerTotals {
    val counted = records.filter { it.isCounted }
    return LedgerTotals(
        spent = counted.sumOf { it.expense.netPaid },
        received = counted.sumOf { it.received },
        outstanding = counted.sumOf { it.outstanding },
        pending = counted.filter { it.expense.status in listOf(ClaimStatus.PREPARING.name, ClaimStatus.READY.name, ClaimStatus.RETURNED.name) }.sumOf { it.outstanding },
        submitted = counted.filter { it.expense.status == ClaimStatus.SUBMITTED.name }.sumOf { it.outstanding },
        monthly = counted.groupBy { it.expense.date.take(7) }.mapValues { (_, rows) -> rows.sumOf { it.expense.netPaid } }.toSortedMap(reverseOrder())
    )
}

@Dao
interface LedgerDao {
    @Transaction @Query("SELECT * FROM expenses ORDER BY date DESC, rowid DESC") fun observe(): Flow<List<ExpenseRecord>>
    @Transaction @Query("SELECT * FROM expenses ORDER BY date DESC") suspend fun snapshot(): List<ExpenseRecord>
    @Upsert suspend fun save(expense: Expense)
    @Insert suspend fun add(attachment: Attachment)
    @Update suspend fun update(attachment: Attachment)
    @Insert suspend fun add(receipt: Receipt)
    @Query("DELETE FROM receipts WHERE id = :id") suspend fun deleteReceipt(id: String)
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteAttachment(id: String)
    @Query("DELETE FROM expenses") suspend fun clear()
}

@Database(entities = [Expense::class, Attachment::class, Receipt::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db").build().also { instance = it }
        }
    }
}
