package com.sticky.reminder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val REFRESH_MS = 60_000L
private const val EDIT_SAVE_DELAY_MS = 700L

/** Android'in kendi "widget'ı ekle" penceresini açar; kullanıcı tek dokunuşla onaylar. */
private fun requestPinWidget(context: Context) {
    AppWidgetManager.getInstance(context)
        .requestPinAppWidget(ComponentName(context, StickyWidgetReceiver::class.java), null, null)
}

/** Alt çubuktaki senkron durum satırı ve hata mı olduğu. */
private fun syncStatus(sync: SyncUi): Pair<String, Boolean> = when {
    sync.signingIn -> "Giriş yapılıyor…" to false
    !sync.signedIn -> (sync.error ?: "Giriş yapılmadı") to (sync.error != null)
    sync.syncing -> "Kaydediliyor…" to false
    sync.error != null -> "Hata: ${sync.error}" to true
    sync.lastOkAt != null ->
        "Son senkron ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sync.lastOkAt))} ✓" to false
    else -> "Henüz senkronlanmadı" to false
}

/** Deadline metni saat dilimsiz yazıldığı için "şimdi" de aynı çerçevede alınır. */
private fun nowNaive(): Long {
    val now = System.currentTimeMillis()
    return now + TimeZone.getDefault().getOffset(now)
}

@Composable
fun StickyApp(vm: StickyViewModel) {
    StickyTheme {
        val state by vm.state.collectAsStateWithLifecycle()
        val sync by vm.sync.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val signInLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> vm.onSignInResult(context, result.data) }
        val onSyncClick = {
            if (sync.signedIn) {
                vm.syncNow()
            } else {
                vm.signIn(context) { pending ->
                    signInLauncher.launch(IntentSenderRequest.Builder(pending).build())
                }
            }
        }
        var filterTag by rememberSaveable { mutableStateOf<String?>(null) }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var doneOpen by rememberSaveable { mutableStateOf(false) }
        var confirmSignOut by rememberSaveable { mutableStateOf(false) }

        // Uygulama önplandayken dakikada bir yenile: geçmiş deadline vurgusu ve tekrarlayan sıfırlama.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(REFRESH_MS)
                    vm.refresh()
                }
            }
        }

        val collator = remember { Collator.getInstance(Locale.forLanguageTag("tr")) }
        val allTags = remember(state.tasks) {
            state.tasks.flatMap { it.tags }.distinct().sortedWith(collator)
        }
        val activeTag = filterTag?.takeIf { it in allTags }
        val visible = if (activeTag == null) state.tasks else state.tasks.filter { activeTag in it.tags }
        val recurring = visible.filter { it.recurring }
        val open = visible.filter { !it.recurring && !it.done }
        val done = visible.filter { !it.recurring && it.done }

        val taskItem: @Composable (Task) -> Unit = { t ->
            TaskItem(
                task = t,
                editing = editingId == t.id,
                onToggleEdit = { editingId = if (editingId == t.id) null else t.id },
                onDone = { vm.update(t.id, TaskPatch(done = it)) },
                onDelete = {
                    if (editingId == t.id) editingId = null
                    vm.delete(t.id)
                },
                onPatch = { vm.update(t.id, it) },
            )
        }

        if (confirmSignOut) {
            AlertDialog(
                onDismissRequest = { confirmSignOut = false },
                containerColor = StickyColors.Bg,
                title = { Text("Google hesabından çıkılsın mı?", color = StickyColors.Accent) },
                text = { Text("Bu cihazdaki görevler silinmez; yalnızca senkron durur.", color = StickyColors.Ink) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSignOut = false
                        vm.signOut()
                    }) { Text("Çık", color = StickyColors.Danger) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSignOut = false }) { Text("Vazgeç", color = StickyColors.Muted) }
                },
            )
        }

        Column(Modifier.fillMaxSize().background(StickyColors.Bg)) {
            // Widget henüz yoksa ve başlatıcı destekliyorsa "Ana ekrana ekle" önerilir.
            val canPinWidget = remember {
                val manager = AppWidgetManager.getInstance(context)
                manager.isRequestPinAppWidgetSupported &&
                    manager.getAppWidgetIds(ComponentName(context, StickyWidgetReceiver::class.java)).isEmpty()
            }
            Row(
                Modifier.fillMaxWidth().background(StickyColors.Bar).statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sticky", color = StickyColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (canPinWidget) {
                    Text(
                        "Ana ekrana ekle",
                        color = StickyColors.Accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { requestPinWidget(context) }.padding(4.dp),
                    )
                }
            }

            var newTitle by rememberSaveable { mutableStateOf("") }
            val addTask = {
                val title = newTitle.trim()
                if (title.isNotEmpty()) {
                    newTitle = ""
                    vm.add(title)
                }
            }
            StickyField(
                value = newTitle,
                onValueChange = { newTitle = it },
                placeholder = "Yeni görev, tamam ile ekle",
                modifier = Modifier.padding(horizontal = 8.dp).padding(top = 8.dp, bottom = 4.dp),
                imeAction = ImeAction.Done,
                onDone = addTask,
            )

            if (allTags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip("Tümü", activeTag == null) { filterTag = null }
                    allTags.forEach { tag ->
                        Chip("#$tag", activeTag == tag) { filterTag = if (activeTag == tag) null else tag }
                    }
                }
            }

            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (recurring.isNotEmpty()) {
                    item(key = "h-rec") { SectionTitle("TEKRARLAYAN") }
                    items(recurring, key = { it.id }) { taskItem(it) }
                }
                if (open.isNotEmpty()) {
                    if (recurring.isNotEmpty()) item(key = "h-open") { SectionTitle("GÖREVLER") }
                    items(open, key = { it.id }) { taskItem(it) }
                }
                if (done.isNotEmpty()) {
                    item(key = "h-done") {
                        SectionTitle("${if (doneOpen) "▾" else "▸"} YAPILANLAR (${done.size})") { doneOpen = !doneOpen }
                    }
                    if (doneOpen) items(done, key = { it.id }) { taskItem(it) }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (state.tasks.isEmpty()) "Henüz görev yok." else "Bu etikette görev yok.",
                            color = StickyColors.Muted,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(StickyColors.Bar)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        if (state.pending > 0) "Bekleyen: ${state.pending}" else "Bekleyen yok",
                        color = StickyColors.Accent,
                        fontSize = 13.sp,
                    )
                    val (statusText, isError) = syncStatus(sync)
                    Text(
                        statusText,
                        color = if (isError) StickyColors.Danger else StickyColors.Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sync.signedIn) {
                    Text(
                        "Çıkış",
                        color = StickyColors.Muted,
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { confirmSignOut = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Button(
                    onClick = onSyncClick,
                    enabled = !sync.signingIn && !sync.syncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.85f),
                        contentColor = StickyColors.Accent,
                        disabledContainerColor = Color.White.copy(alpha = 0.6f),
                        disabledContentColor = StickyColors.Accent.copy(alpha = 0.5f),
                    ),
                ) {
                    Text(
                        when {
                            sync.signingIn -> "Giriş yapılıyor…"
                            !sync.signedIn -> "Google ile giriş"
                            sync.syncing -> "Kaydediliyor…"
                            else -> "Kaydet"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = StickyColors.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        fontSize = 13.sp,
        color = if (selected) Color.White else StickyColors.Ink,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) StickyColors.Accent else Color.White.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, if (selected) StickyColors.Accent else StickyColors.Edge), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun TaskItem(
    task: Task,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onDone: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onPatch: (TaskPatch) -> Unit,
) {
    val key = task.deadlineKey
    val overdue = !task.done && key != null && key < nowNaive()
    val unreadable = task.deadline.isNotEmpty() && key == null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(taskColor(task.color)))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleEdit),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = task.done, onCheckedChange = onDone)
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(
                        task.title,
                        color = if (task.done) StickyColors.Muted else StickyColors.Ink,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        fontSize = 15.sp,
                    )
                    if (task.deadline.isNotEmpty() || task.tags.isNotEmpty()) {
                        Text(
                            buildAnnotatedString {
                                if (task.deadline.isNotEmpty()) {
                                    val style = when {
                                        overdue -> SpanStyle(color = StickyColors.Danger, fontWeight = FontWeight.SemiBold)
                                        unreadable -> SpanStyle(color = StickyColors.Hint, fontStyle = FontStyle.Italic)
                                        else -> SpanStyle(color = StickyColors.Muted)
                                    }
                                    withStyle(style) { append(task.deadline) }
                                }
                                if (task.tags.isNotEmpty()) {
                                    if (task.deadline.isNotEmpty()) append("  ")
                                    withStyle(SpanStyle(color = StickyColors.Tag)) {
                                        append(task.tags.joinToString(" ") { "#$it" })
                                    }
                                }
                            },
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Box(
                    Modifier.size(44.dp).clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) { Text("×", color = StickyColors.Muted, fontSize = 22.sp) }
            }
            if (editing) Editor(task, onPatch)
        }
    }
}

@Composable
private fun Editor(task: Task, onPatch: (TaskPatch) -> Unit) {
    val latest by rememberUpdatedState(task)
    var title by remember(task.id) { mutableStateOf(task.title) }
    var notes by remember(task.id) { mutableStateOf(task.notes) }
    var deadline by remember(task.id) { mutableStateOf(task.deadline) }
    var tags by remember(task.id) { mutableStateOf(task.tags.joinToString(", ")) }

    // Yazılan metin alandan çıkınca ve düzenleyici kapanınca kaydedilir.
    val commit = { restoreBlankTitle: Boolean ->
        val t = latest
        if (restoreBlankTitle && title.isBlank()) title = t.title
        val tagList = tags.split(',')
        val patch = TaskPatch(
            title = title.takeIf { it.trim() != t.title },
            notes = notes.takeIf { it != t.notes },
            deadline = deadline.takeIf { !t.recurring && it.trim() != t.deadline },
            tags = tagList.takeIf { TaskLogic.normalizeTags(it) != t.tags },
        )
        if (patch != TaskPatch()) onPatch(patch)
    }
    val currentCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { currentCommit(true) } }
    // Yazmayı bırakınca kısa süre sonra kaydet; klavye Geri ile kapatılıp uygulamadan çıkılsa da kaybolmasın.
    LaunchedEffect(title, notes, deadline, tags) {
        delay(EDIT_SAVE_DELAY_MS)
        currentCommit(false)
    }
    val onFocusLost = { currentCommit(true) }

    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StickyField(title, { title = it }, "Başlık", onFocusLost = onFocusLost)
        StickyField(notes, { notes = it }, "Not", singleLine = false, minLines = 3, onFocusLost = onFocusLost)
        if (!task.recurring) {
            StickyField(deadline, { deadline = it }, "GG.AA.YYYY SS:DD", onFocusLost = onFocusLost)
        }
        StickyField(tags, { tags = it }, "etiketler, virgülle ayır", onFocusLost = onFocusLost)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = task.recurrence ?: ""
            Chip("Tekrar yok", current == "") { onPatch(TaskPatch(recurring = false)) }
            Chip("Her gün", current == "daily") { onPatch(TaskPatch(recurring = true, recurrence = "daily")) }
            Chip("Her hafta", current == "weekly") { onPatch(TaskPatch(recurring = true, recurrence = "weekly")) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
            TASK_COLORS.forEach { name ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(taskColor(name))
                        .border(2.dp, if (task.color == name) StickyColors.Ink else Color.Transparent, CircleShape)
                        .clickable { onPatch(TaskPatch(color = name)) },
                )
            }
        }
    }
}

@Composable
internal fun StickyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    onFocusLost: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (focused && !it.isFocused) onFocusLost?.invoke()
                focused = it.isFocused
            },
        textStyle = TextStyle(color = StickyColors.Ink, fontSize = 15.sp),
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        cursorBrush = SolidColor(StickyColors.Accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.85f))
                    .border(1.dp, if (focused) StickyColors.Focus else StickyColors.Edge, shape)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = StickyColors.Hint, fontSize = 15.sp)
                inner()
            }
        },
    )
}
