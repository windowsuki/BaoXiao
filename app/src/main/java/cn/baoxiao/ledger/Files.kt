package cn.baoxiao.ledger

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LedgerFiles(private val context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(8192); var n = input.read(b); while (n >= 0) { hash.update(b, 0, n); n = input.read(b) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun import(uri: Uri, expenseId: String, kind: MaterialKind): Attachment {
        var name = "材料"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val file = File(dir, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取原文件")
            require(file.length() > 0) { "文件为空" }
            return Attachment(expenseId = expenseId, name = name, path = file.name, mime = context.contentResolver.getType(uri) ?: "application/octet-stream", kind = kind.name, hash = digest(file), size = file.length())
        } catch (e: Exception) { file.delete(); throw e }
    }
    fun file(a: Attachment): File = File(dir, a.path).also { require(it.parentFile?.canonicalFile == dir.canonicalFile) }
    fun open(a: Attachment) {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file(a)), a.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    fun prepareShare(a: Attachment): File {
        val export = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(export, safeName(a.name))
        file(a).copyTo(target)
        return target
    }
    fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送原文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun export(records: List<ExpenseRecord>, backup: Boolean): File {
        val target = File(context.cacheDir, "exports/${UUID.randomUUID()}.zip").apply { parentFile?.mkdirs() }
        ZipOutputStream(target.outputStream()).use { zip ->
            val csv = "名称,支付日期,实际支付,退款,申请报销,实际到账,未收回,类别,项目,报销单,状态\r\n" + records.joinToString("\r\n") { r ->
                listOf(r.expense.title, r.expense.date, money(r.expense.paid), money(r.expense.refunded), money(r.expense.requested), money(r.received), money(r.outstanding), r.expense.category, r.expense.project, r.expense.batch, ClaimStatus.valueOf(r.expense.status).label).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
            }
            entry(zip, "支出清单.csv", ("\uFEFF" + csv).toByteArray(Charsets.UTF_8))
            if (backup) entry(zip, "manifest.json", manifest(records).toString(2).toByteArray())
            records.forEach { r -> r.attachments.forEach { a ->
                val source = file(a); require(source.isFile) { "材料丢失：${a.name}" }
                val path = if (backup) "attachments/${a.path}" else "${safeName(r.expense.title)}_${r.expense.id.take(8)}/${MaterialKind.valueOf(a.kind).label}/${a.id.take(8)}_${safeName(a.name)}"
                zip.putNextEntry(ZipEntry(path)); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            } }
        }
        return target
    }
    fun write(uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法保存文件")
    }
    private fun entry(zip: ZipOutputStream, name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100).ifBlank { "材料" }
    private fun manifest(records: List<ExpenseRecord>): JSONObject = JSONObject().put("version", 1).put("records", JSONArray().apply {
        records.forEach { r -> put(JSONObject().apply {
            val e = r.expense
            put("expense", JSONObject().apply { put("id", e.id); put("title", e.title); put("paid", e.paid); put("requested", e.requested); put("refunded", e.refunded); put("date", e.date); put("category", e.category); put("project", e.project); put("merchant", e.merchant); put("note", e.note); put("status", e.status); put("batch", e.batch); put("deleted", e.deleted) })
            put("attachments", JSONArray().apply { r.attachments.forEach { a -> put(JSONObject().apply { put("id", a.id); put("name", a.name); put("path", a.path); put("mime", a.mime); put("kind", a.kind); put("hash", a.hash); put("size", a.size) }) } })
            put("receipts", JSONArray().apply { r.receipts.forEach { receipt -> put(JSONObject().put("id", receipt.id).put("amount", receipt.amount).put("date", receipt.date)) } })
        }) }
    })
    suspend fun restore(uri: Uri, db: LedgerDatabase): Int {
        val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val copied = mutableListOf<File>()
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { stream -> ZipInputStream(stream).use { zip ->
                var count = 0
                var e = zip.nextEntry
                while (e != null) {
                    require(++count <= 10000) { "备份文件数量过多" }
                    val f = File(stage, e.name)
                    require(f.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "备份路径不合法" }
                    if (!e.isDirectory) {
                        f.parentFile?.mkdirs(); f.outputStream().use { out -> val b = ByteArray(8192); var n = zip.read(b); while (n >= 0) { total += n; require(total <= 1024L * 1024 * 1024) { "备份超过 1GB" }; out.write(b, 0, n); n = zip.read(b) } }
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            } } ?: error("无法读取备份")
            val manifestFile = File(stage, "manifest.json")
            require(manifestFile.length() <= 10 * 1024 * 1024) { "备份索引过大" }
            val json = JSONObject(manifestFile.readText()); require(json.getInt("version") == 1) { "备份版本不支持" }
            val existing = db.dao().snapshot().map { it.expense.id }.toSet()
            val seen = mutableSetOf<String>()
            val parsed = mutableListOf<ExpenseRecord>(); val records = json.getJSONArray("records")
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i); val e = r.getJSONObject("expense"); val id = e.getString("id")
                require(seen.add(id)) { "备份包含重复账目" }
                if (id in existing) continue
                val expense = Expense(id, e.getString("title"), e.getLong("paid"), e.getLong("requested"), e.getLong("refunded"), e.getString("date"), e.getString("category"), e.getString("project"), e.getString("merchant"), e.getString("note"), e.getString("status"), e.getString("batch"), e.getBoolean("deleted"))
                ClaimStatus.valueOf(expense.status); java.time.LocalDate.parse(expense.date)
                require(expense.paid >= 0 && expense.requested >= 0 && expense.refunded in 0..expense.paid)
                val attachments = mutableListOf<Attachment>(); val aa = r.getJSONArray("attachments")
                for (j in 0 until aa.length()) {
                    val a = aa.getJSONObject(j); val relative = a.getString("path")
                    require(relative.matches(Regex("[A-Za-z0-9-]+"))) { "材料路径不合法" }
                    val src = File(stage, "attachments/$relative"); require(src.isFile && digest(src) == a.getString("hash")) { "材料校验失败" }
                    val dest = File(dir, UUID.randomUUID().toString()); src.copyTo(dest); copied.add(dest)
                    MaterialKind.valueOf(a.getString("kind"))
                    attachments.add(Attachment(UUID.randomUUID().toString(), id, a.getString("name"), dest.name, a.getString("mime"), a.getString("kind"), a.getString("hash"), dest.length()))
                }
                val receipts = mutableListOf<Receipt>(); val rr = r.getJSONArray("receipts")
                for (j in 0 until rr.length()) { val receipt = rr.getJSONObject(j); val amount = receipt.getLong("amount"); require(amount > 0); val date = receipt.getString("date"); java.time.LocalDate.parse(date); receipts.add(Receipt(expenseId = id, amount = amount, date = date)) }
                parsed.add(ExpenseRecord(expense, attachments, receipts))
            }
            db.withTransaction { parsed.forEach { r -> db.dao().save(r.expense); r.attachments.forEach { db.dao().add(it) }; r.receipts.forEach { db.dao().add(it) } } }
            return parsed.size
        } catch (e: Exception) { copied.forEach { it.delete() }; throw e }
        finally { stage.deleteRecursively() }
    }
}
