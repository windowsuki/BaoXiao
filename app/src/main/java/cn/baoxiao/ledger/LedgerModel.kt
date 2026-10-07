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
            val received = db.dao().snapshot().find { it.expense.id == expense.id }?.received ?: 0L
            val valid = if (expense.status == ClaimStatus.RECEIVED.name && received < expense.requested)
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
    fun receipt(id: String, cents: Long) = work { require(cents > 0); db.dao().add(Receipt(expenseId = id, amount = cents)) }
    fun removeReceipt(id: String) = work {
        db.withTransaction {
            val record = db.dao().snapshot().find { r -> r.receipts.any { it.id == id } }
            db.dao().deleteReceipt(id)
            if (record != null && record.expense.status == ClaimStatus.RECEIVED.name &&
                record.receipts.filter { it.id != id }.sumOf { it.amount } < record.expense.requested)
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
