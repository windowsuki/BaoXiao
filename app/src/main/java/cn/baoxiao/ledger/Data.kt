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
    val outstanding: Long get() = expense.paid - expense.refunded - received
    val missing: List<String> get() = listOf(MaterialKind.PAYMENT, MaterialKind.BILL, MaterialKind.INVOICE)
        .filter { kind -> attachments.none { it.kind == kind.name } }.map { it.label }
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
