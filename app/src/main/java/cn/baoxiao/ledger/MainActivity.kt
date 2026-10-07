package cn.baoxiao.ledger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.time.LocalDate
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<List<Uri>>(emptyList())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); receive(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF256B59), secondary = Color(0xFF56766B), background = Color(0xFFF5F7F4), surface = Color.White)) {
                LedgerApp(incoming, consumeIncoming = { incoming = emptyList() })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        incoming = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
            else -> emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(incoming: List<Uri>, consumeIncoming: () -> Unit, model: LedgerModel = viewModel()) {
    val all by model.records.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) model.export(null, uri, true) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.restore(uri) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    LaunchedEffect(incoming) { showImport = incoming.isNotEmpty() }
    androidx.activity.compose.BackHandler(screen != "home") { screen = "home" }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(when (screen) { "detail" -> "支出详情"; "edit" -> "录入支出"; "stats" -> "金额统计"; "trash" -> "回收站"; else -> "报销账本" }, fontWeight = FontWeight.Bold) },
            navigationIcon = { if (screen != "home") TextButton(onClick = { screen = "home" }) { Text("返回") } },
            actions = {
                var menu by remember { mutableStateOf(false) }
                TextButton(onClick = { menu = true }) { Text("更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("完整备份") }, onClick = { menu = false; backup.launch("报销账本_${LocalDate.now()}.zip") })
                    DropdownMenuItem(text = { Text("恢复备份") }, onClick = { menu = false; confirmRestore = true })
                    DropdownMenuItem(text = { Text("导出全部材料与清单") }, onClick = { menu = false; model.export(all.filter { !it.expense.deleted }.map { it.expense.id }.toSet(), null, false) })
                    DropdownMenuItem(text = { Text("回收站") }, onClick = { menu = false; screen = "trash" })
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val current = all.find { it.expense.id == selected }
            when (screen) {
                "edit" -> ExpenseEditor(current?.expense, busy, { e -> model.save(e) { selected = e.id; screen = "detail" } }, { screen = if (current == null) "home" else "detail" })
                "detail" -> if (current != null) Detail(current, model, { screen = "edit" }, { screen = "home" }) else Text("正在读取…", Modifier.padding(24.dp))
                "stats" -> Statistics(all.filter { !it.expense.deleted })
                "trash" -> LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("删除的记录保留在这里，材料不会自动清除。") }
                    items(all.filter { it.expense.deleted }, key = { it.expense.id }) { r -> Card { Row(Modifier.padding(16.dp)) { Text(r.expense.title, Modifier.weight(1f)); TextButton(onClick = { model.save(r.expense.copy(deleted = false)) {} }) { Text("恢复") } } } }
                }
                else -> Home(all.filter { !it.expense.deleted }, { selected = null; screen = "edit" }, { selected = it; screen = "detail" }, { screen = "stats" })
            }
        }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, title = { Text("恢复完整备份") }, text = { Text("导入备份中尚不存在的记录和材料。已有记录不会被覆盖。仅支持本应用导出的完整备份 ZIP。") }, confirmButton = { TextButton(onClick = { confirmRestore = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("选择备份") } }, dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } })
    if (showImport) AlertDialog(onDismissRequest = { showImport = false; consumeIncoming() }, title = { Text("收到 ${incoming.size} 份材料") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("选择支出，材料将先归入补充材料，可在详情中修改类型。", Modifier.padding(bottom = 12.dp)) }
            items(all.filter { !it.expense.deleted }) { r -> TextButton(onClick = { model.import(r.expense.id, incoming, MaterialKind.OTHER); selected = r.expense.id; screen = "detail"; showImport = false; consumeIncoming() }) { Text("${r.expense.title} · ¥${money(r.expense.paid)}") } }
        }
    }, confirmButton = { TextButton(onClick = { selected = null; screen = "edit"; showImport = false }) { Text("先录入新支出") } }, dismissButton = { TextButton(onClick = { showImport = false; consumeIncoming() }) { Text("取消") } })
    LaunchedEffect(screen, selected, busy) {
        if (screen == "detail" && incoming.isNotEmpty() && !busy && selected != null) showImport = true
    }
}

@Composable
private fun Home(records: List<ExpenseRecord>, add: () -> Unit, open: (String) -> Unit, stats: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = records.filter { r ->
        val e = r.expense
        (query.isBlank() || listOf(e.title, e.category, e.project, e.merchant, e.note, e.batch).any { it.contains(query, true) }) && when (filter) {
            "缺材料" -> r.missing.isNotEmpty()
            "待提交" -> e.status in listOf("PREPARING", "READY", "RETURNED")
            "等到账" -> e.status == "SUBMITTED" && e.requested > r.received
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(onClick = stats, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("尚未收回的垫款", color = Color.White.copy(alpha = .8f))
                    Text("¥ ${money(records.sumOf { it.outstanding })}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    val pending = records.filter { it.expense.status in listOf("PREPARING", "READY", "RETURNED") }.sumOf { it.expense.requested }
                    val submitted = records.filter { it.expense.status == "SUBMITTED" }.sumOf { (it.expense.requested - it.received).coerceAtLeast(0) }
                    Text("待提交 ¥${money(pending)}   已提交未到账 ¥${money(submitted)}", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        item { Button(onClick = add, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("＋ 录入支出", fontSize = 17.sp) } }
        item { OutlinedTextField(query, { query = it }, placeholder = { Text("搜索名称、项目或报销单") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("全部", "缺材料", "待提交", "等到账").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, fontSize = 12.sp) }) } } }
        if (visible.isEmpty()) item { Text(if (records.isEmpty()) "还没有支出。录入第一笔，开始整理材料。" else "没有符合条件的支出。", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(visible, key = { it.expense.id }) { r ->
            Card(onClick = { open(r.expense.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text(r.expense.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text("¥${money(r.expense.paid)}", fontWeight = FontWeight.Bold) }
                    Row { Text("${r.expense.date} · ${r.expense.category}", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(ClaimStatus.valueOf(r.expense.status).label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    if (r.missing.isNotEmpty()) Text("未添加：${r.missing.joinToString("、")}", fontSize = 12.sp, color = Color(0xFF9B6621))
                }
            }
        }
        item { Text("数据与原文件仅保存在本机，建议定期完整备份。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExpenseEditor(initial: Expense?, busy: Boolean, save: (Expense) -> Unit, cancel: () -> Unit) {
    val draftId = rememberSaveable(initial?.id) { initial?.id ?: UUID.randomUUID().toString() }
    val base = remember(draftId) { initial ?: Expense(id = draftId) }
    var title by rememberSaveable(base.id) { mutableStateOf(base.title) }
    var paid by rememberSaveable(base.id) { mutableStateOf(if (initial == null) "" else money(base.paid)) }
    var requested by rememberSaveable(base.id) { mutableStateOf(if (initial == null) "" else money(base.requested)) }
    var refunded by rememberSaveable(base.id) { mutableStateOf(money(base.refunded)) }
    var date by rememberSaveable(base.id) { mutableStateOf(base.date) }
    var category by rememberSaveable(base.id) { mutableStateOf(base.category) }
    var project by rememberSaveable(base.id) { mutableStateOf(base.project) }
    var merchant by rememberSaveable(base.id) { mutableStateOf(base.merchant) }
    var batch by rememberSaveable(base.id) { mutableStateOf(base.batch) }
    var note by rememberSaveable(base.id) { mutableStateOf(base.note) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("先记金额，材料可以稍后补充。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Field("支出名称 *", title, { title = it }) }
        item { Field("实际支付金额（元）*", paid, { paid = it }, decimal = true) }
        item { Field("申请报销金额（留空同支付金额）", requested, { requested = it }, decimal = true) }
        item { Field("已退款金额（元）", refunded, { refunded = it }, decimal = true) }
        item { Field("支付日期（YYYY-MM-DD）", date, { date = it }) }
        item { Choice("类别", category, listOf("采购", "交通", "住宿", "餐饮", "其他")) { category = it } }
        item { Field("项目 / 课题", project, { project = it }) }
        item { Field("商家 / 收款方", merchant, { merchant = it }) }
        item { Field("报销单名称（同名支出归为一单）", batch, { batch = it }) }
        item { Field("备注", note, { note = it }, multiline = true) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            val p = parseMoney(paid); val q = if (requested.isBlank()) p else parseMoney(requested); val f = parseMoney(refunded)
            if (title.isBlank() || p == null || p <= 0 || q == null || f == null || f > p) error = "请填写名称和有效金额，最多两位小数，退款不能超过支付金额。"
            else if (runCatching { LocalDate.parse(date) }.isFailure) error = "日期格式应为 YYYY-MM-DD。"
            else save(base.copy(title = title.trim(), paid = p, requested = q, refunded = f, date = date, category = category, project = project, merchant = merchant, note = note, batch = batch.trim()))
        }) { Text("保存支出") } }
        item { TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) { Text("取消") } }
    }
}

@Composable
private fun Field(label: String, value: String, set: (String) -> Unit, decimal: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, set, label = { Text(label) }, singleLine = !multiline, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value ▾") }; DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); expanded = false }) } } }
}

@Composable
private fun Detail(r: ExpenseRecord, model: LedgerModel, edit: () -> Unit, back: () -> Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(MaterialKind.PAYMENT.name) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.import(r.expense.id, uris, MaterialKind.valueOf(kind)) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) model.import(r.expense.id, uris, MaterialKind.valueOf(kind))
    }
    var receiptDialog by remember { mutableStateOf(false) }
    var receiptAmount by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var attachmentDelete by remember { mutableStateOf<Attachment?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row { Column(Modifier.weight(1f)) { Text(r.expense.title, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("¥ ${money(r.expense.paid)}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text("${r.expense.date} · ${r.expense.category}") }; TextButton(onClick = edit) { Text("编辑") } } }
        item { Text("申请 ¥${money(r.expense.requested)}   到账 ¥${money(r.received)}   退款 ¥${money(r.expense.refunded)}") }
        item { Text("尚未收回 ¥${money(r.outstanding)}", fontWeight = FontWeight.Bold) }
        item { Choice("报销状态", ClaimStatus.valueOf(r.expense.status).label, ClaimStatus.entries.map { it.label }) { label ->
            val status = ClaimStatus.entries.first { it.label == label }
            if (status == ClaimStatus.RECEIVED && r.received < r.expense.requested) model.message.value = "请先记录到账金额，再标记已到账。"
            else model.save(r.expense.copy(status = status.name)) {}
        } }
        if (r.expense.project.isNotBlank()) item { Text("项目：${r.expense.project}") }
        if (r.expense.batch.isNotBlank()) item { Text("报销单：${r.expense.batch}"); OutlinedButton(onClick = {
            val ids = model.records.value.filter { !it.expense.deleted && it.expense.batch == r.expense.batch }.map { it.expense.id }.toSet(); model.export(ids, null, false)
        }) { Text("打包整张报销单") } }
        item { Row { Text("报销材料", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("${r.attachments.size} 份", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Choice("添加材料类型", MaterialKind.valueOf(kind).label, MaterialKind.entries.map { it.label }) { label -> kind = MaterialKind.entries.first { it.label == label }.name } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("选择文件") }
            OutlinedButton(enabled = !busy, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.weight(1f)) { Text("从图库选择") }
        } }
        MaterialKind.entries.forEach { materialKind ->
            item { Text(materialKind.label, fontWeight = FontWeight.SemiBold) }
            val attachments = r.attachments.filter { it.kind == materialKind.name }
            if (attachments.isEmpty()) item { Text("尚未添加（按本次报销要求补充）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(attachments, key = { it.id }) { a -> Card {
                Column(Modifier.padding(12.dp)) {
                    if (a.mime.startsWith("image/")) AsyncImage(model = model.files.file(a), contentDescription = a.name, modifier = Modifier.fillMaxWidth().height(130.dp).clickable { runCatching { model.files.open(a) }.onFailure { model.message.value = "没有可打开该文件的应用" } })
                    Text(a.name, fontWeight = FontWeight.Medium); Text("${a.size / 1024} KB · 原文件", fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { runCatching { model.files.open(a) }.onFailure { model.message.value = "没有可打开该文件的应用" } }) { Text("查看") }
                        TextButton(enabled = !busy, onClick = { model.share(a) }) { Text("转发") }
                        TextButton(onClick = { attachmentDelete = a }) { Text("移除") }
                    }
                    Choice("材料类型", MaterialKind.valueOf(a.kind).label, MaterialKind.entries.map { it.label }) { label -> model.work { model.db.dao().update(a.copy(kind = MaterialKind.entries.first { it.label == label }.name)) } }
                }
            } }
        }
        item { OutlinedButton(enabled = !busy, onClick = { model.export(setOf(r.expense.id), null, false) }, modifier = Modifier.fillMaxWidth()) { Text("打包这笔支出的材料与清单") } }
        item { Row { Text("到账记录", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold); TextButton(onClick = { receiptDialog = true }) { Text("＋记录到账") } } }
        items(r.receipts, key = { it.id }) { receipt -> Row { Text("${receipt.date}   ¥${money(receipt.amount)}", Modifier.weight(1f)); TextButton(onClick = { model.removeReceipt(receipt.id) }) { Text("撤销") } } }
        if (r.expense.note.isNotBlank()) item { Text("备注", fontWeight = FontWeight.Bold); Text(r.expense.note) }
        item { TextButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth()) { Text("移入回收站", color = MaterialTheme.colorScheme.error) } }
    }
    if (receiptDialog) AlertDialog(onDismissRequest = { receiptDialog = false }, title = { Text("记录一次报销到账") }, text = { Field("到账金额（元）", receiptAmount, { receiptAmount = it }, decimal = true) }, confirmButton = { TextButton(onClick = {
        val amount = parseMoney(receiptAmount)
        if (amount == null || amount <= 0) model.message.value = "请输入有效到账金额"
        else { model.receipt(r.expense.id, amount); receiptDialog = false; receiptAmount = "" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { receiptDialog = false }) { Text("取消") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("移入回收站？") }, text = { Text("账目暂时退出统计，原始材料仍保留，可随时恢复。") }, confirmButton = { TextButton(onClick = { model.save(r.expense.copy(deleted = true)) { back() }; delete = false }) { Text("移入") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
    attachmentDelete?.let { a -> AlertDialog(onDismissRequest = { attachmentDelete = null }, title = { Text("移除材料？") }, text = { Text("将删除应用内的这份副本，不影响来源文件。建议先备份。") }, confirmButton = { TextButton(onClick = { model.removeAttachment(a); attachmentDelete = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { attachmentDelete = null }) { Text("取消") } }) }
}

@Composable
private fun Statistics(records: List<ExpenseRecord>) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("累计实际支出 ¥${money(records.sumOf { it.expense.paid - it.expense.refunded })}", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        item { Text("累计报销到账 ¥${money(records.sumOf { it.received })}", fontSize = 20.sp) }
        item { Text("尚未收回的垫款 ¥${money(records.sumOf { it.outstanding })}", fontSize = 20.sp) }
        item { Text("按月份 · 实际支出", fontWeight = FontWeight.Bold) }
        records.groupBy { it.expense.date.take(7) }.toSortedMap(reverseOrder()).forEach { (month, rows) -> item { SummaryRow(month, rows.sumOf { it.expense.paid - it.expense.refunded }) } }
        item { Text("按类别 · 实际支出", fontWeight = FontWeight.Bold) }
        records.groupBy { it.expense.category }.forEach { (category, rows) -> item { SummaryRow(category, rows.sumOf { it.expense.paid - it.expense.refunded }) } }
        item { Text("按项目 · 实际支出", fontWeight = FontWeight.Bold) }
        records.groupBy { it.expense.project.ifBlank { "未指定项目" } }.forEach { (project, rows) -> item { SummaryRow(project, rows.sumOf { it.expense.paid - it.expense.refunded }) } }
    }
}

@Composable
private fun SummaryRow(label: String, cents: Long) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp)) { Text(label, Modifier.weight(1f)); Text("¥${money(cents)}", fontWeight = FontWeight.Bold) } } }
