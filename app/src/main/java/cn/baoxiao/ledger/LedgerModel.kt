package cn.baoxiao.ledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerModel(app: Application) : AndroidViewModel(app) {
    val db = LedgerDatabase.get(app)
    val files = LedgerFiles(app)
    val records = db.dao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    fun clearMessage() { message.value = null }
    fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } catch (e: Exception) { message.value = e.message ?: "操作失败，请重试" } finally { busy.value = false }
        }
    }
    fun save(expense: Expense, then: () -> Unit) = work {
        db.withTransaction {
            val old = db.dao().snapshot().find { it.expense.id == expense.id }
            val received = old?.received ?: 0L
            if (old != null && (old.expense.paid != expense.paid || old.expense.refunded != expense.refunded))
                require(expense.netPaid >= received) { "金额不能低于已到账金额，请先撤销错误的到账记录。" }
            val valid = if (expense.status == ClaimStatus.RECEIVED.name && received < expense.netPaid)
                expense.copy(status = ClaimStatus.SUBMITTED.name) else expense
            db.dao().save(valid)
        }
        then()
    }
    fun import(id: String, uris: List<Uri>, kind: MaterialKind) = work {
        var duplicates = 0
        for (uri in uris) {
            val attachment = withContext(Dispatchers.IO) { files.import(uri, id, kind) }
            val hashes = db.dao().snapshot().flatMap { it.attachments }.filter { it.expenseId == id }.map { it.hash }
            if (attachment.hash in hashes) { withContext(Dispatchers.IO) { files.file(attachment).delete() }; duplicates++ }
            else try { db.dao().add(attachment) } catch (e: Exception) { withContext(Dispatchers.IO) { files.file(attachment).delete() }; throw e }
        }
        message.value = if (duplicates > 0) "已导入，跳过 $duplicates 份重复材料" else "材料已导入"
    }
    fun receiveFully(id: String) = work {
        db.withTransaction {
            val record = db.dao().snapshot().find { it.expense.id == id } ?: error("账目不存在")
            val settled = record.receiveFully()
            settled.receipts.drop(record.receipts.size).forEach { db.dao().add(it) }
            db.dao().save(settled.expense)
        }
        message.value = "已全部到账"
    }
    fun updateProgress(id: String, status: ClaimStatus) = work {
        require(status in listOf(ClaimStatus.PREPARING, ClaimStatus.READY, ClaimStatus.SUBMITTED, ClaimStatus.RETURNED))
        db.withTransaction {
            val record = db.dao().snapshot().find { it.expense.id == id } ?: error("账目不存在")
            require(record.isCounted && record.expense.status != ClaimStatus.RECEIVED.name) { "请先恢复报账或撤销到账。" }
            db.dao().save(record.expense.copy(status = status.name))
        }
    }
    fun cancelClaim(id: String) = work {
        db.withTransaction {
            val record = db.dao().snapshot().find { it.expense.id == id } ?: error("账目不存在")
            db.dao().save(record.expense.copy(status = ClaimStatus.CANCELLED.name))
        }
        message.value = "报账已取消，不计入金额统计"
    }
    fun restoreClaim(id: String) = work {
        db.withTransaction {
            val record = db.dao().snapshot().find { it.expense.id == id } ?: error("账目不存在")
            if (record.expense.status == ClaimStatus.CANCELLED.name) {
                db.dao().save(record.restoreClaim().expense)
            }
        }
        message.value = "已恢复报账并计入统计"
    }
    fun removeReceipt(id: String) = work {
        db.withTransaction {
            val record = db.dao().snapshot().find { r -> r.receipts.any { it.id == id } }
            db.dao().deleteReceipt(id)
            if (record != null && record.expense.status == ClaimStatus.RECEIVED.name &&
                record.receipts.filter { it.id != id }.sumOf { it.amount } < record.expense.netPaid)
                db.dao().save(record.expense.copy(status = ClaimStatus.SUBMITTED.name))
        }
    }
    fun share(a: Attachment) = work {
        val target = withContext(Dispatchers.IO) { files.prepareShare(a) }
        files.shareFile(target, a.mime)
    }
    fun removeAttachment(a: Attachment) = work { db.dao().deleteAttachment(a.id); withContext(Dispatchers.IO) { files.file(a).delete() } }
    fun export(ids: Set<String>?, uri: Uri?, backup: Boolean) = work {
        val snapshot = db.dao().snapshot().filter { ids == null || it.expense.id in ids }
        val result = withContext(Dispatchers.IO) { files.export(snapshot, backup) }
        if (uri != null) { withContext(Dispatchers.IO) { files.write(uri, result) }; message.value = "完整备份已保存" }
        else files.shareFile(result, "application/zip")
    }
    fun restore(uri: Uri) = work {
        val count = withContext(Dispatchers.IO) { files.restore(uri, db) }; message.value = "已恢复 $count 笔，已有记录保持不变"
    }
}
