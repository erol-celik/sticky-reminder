package com.sticky.reminder

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Widget'ın okuduğu görev listesi. */
private object WidgetData {
    val tasks = MutableStateFlow<List<Task>>(emptyList())

    suspend fun reload(context: Context) {
        tasks.value = withContext(Dispatchers.IO) { TaskStore.get(context).list() }
    }
}

/** Veritabanı değişince çağrılır: listeyi tazeler ve ekrandaki tüm widget'ları yeniden çizer. */
suspend fun updateStickyWidgets(context: Context) {
    WidgetData.reload(context)
    StickyWidget().updateAll(context)
}

private val TaskIdKey = ActionParameters.Key<String>("taskId")
private val DoneKey = ActionParameters.Key<Boolean>("done")

private fun color(c: Color) = ColorProvider(c)

/** Deadline metni saat dilimsiz yazıldığı için "şimdi" de aynı çerçevede alınır. */
private fun nowNaive(): Long {
    val now = System.currentTimeMillis()
    return now + TimeZone.getDefault().getOffset(now)
}

/**
 * Ana ekran widget'ı: uygulamayla aynı yerel veritabanını okur. Üstte tekrarlayan görevler, altında
 * açık görevler; yapılan görevler görünmez. Yapıldı / sil / ekle widget'tan yapılır.
 */
class StickyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetData.reload(context)
        provideContent {
            // Glance oturumu açıkken provideGlance yeniden çağrılmaz; veri akıştan okunur ki
            // açık oturum da yeni listeyi göstersin.
            val tasks by WidgetData.tasks.collectAsState()
            Content(
                recurring = tasks.filter { it.recurring },
                open = tasks.filter { !it.recurring && !it.done },
            )
        }
    }

    @Composable
    private fun Content(recurring: List<Task>, open: List<Task>) {
        val context = LocalContext.current
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        val openQuickAdd = actionStartActivity(Intent(context, QuickAddActivity::class.java))
        Column(GlanceModifier.fillMaxSize().background(StickyColors.Bg).cornerRadius(16.dp)) {
            Row(
                GlanceModifier.fillMaxWidth().background(StickyColors.Bar).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sticky",
                    modifier = GlanceModifier.defaultWeight().clickable(openApp),
                    style = TextStyle(color = color(StickyColors.Accent), fontSize = 17.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    "+",
                    modifier = GlanceModifier.padding(horizontal = 14.dp).clickable(openQuickAdd),
                    style = TextStyle(color = color(StickyColors.Accent), fontSize = 26.sp, fontWeight = FontWeight.Medium),
                )
            }

            if (recurring.isEmpty() && open.isEmpty()) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Görev yok. + ile ekle",
                        style = TextStyle(color = color(StickyColors.Muted), fontSize = 14.sp),
                    )
                }
            } else {
                LazyColumn(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    if (recurring.isNotEmpty()) {
                        item { SectionLabel("TEKRARLAYAN") }
                        items(recurring, itemId = { it.id.hashCode().toLong() }) { TaskRow(it, openApp) }
                    }
                    if (open.isNotEmpty()) {
                        if (recurring.isNotEmpty()) item { SectionLabel("GÖREVLER") }
                        items(open, itemId = { it.id.hashCode().toLong() }) { TaskRow(it, openApp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            modifier = GlanceModifier.padding(top = 10.dp, bottom = 4.dp),
            style = TextStyle(color = color(StickyColors.Muted), fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
    }

    @Composable
    private fun TaskRow(task: Task, openApp: Action) {
        val key = task.deadlineKey
        val overdue = !task.done && key != null && key < nowNaive()
        // Sol renk şeridi: dış kutu şerit rengi, iç kutu 5dp içeriden başlar.
        Box(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Box(GlanceModifier.fillMaxWidth().background(taskColor(task.color)).padding(start = 5.dp)) {
                Row(
                    GlanceModifier.fillMaxWidth().background(Color.White.copy(alpha = 0.92f)).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(
                        checked = task.done,
                        onCheckedChange = actionRunCallback<ToggleDoneAction>(
                            actionParametersOf(TaskIdKey to task.id, DoneKey to !task.done),
                        ),
                        colors = CheckboxDefaults.colors(
                            checkedColor = color(StickyColors.Accent),
                            uncheckedColor = color(StickyColors.Accent),
                        ),
                    )
                    Column(GlanceModifier.defaultWeight().padding(vertical = 6.dp).clickable(openApp)) {
                        Text(
                            task.title,
                            style = TextStyle(
                                color = color(if (task.done) StickyColors.Muted else StickyColors.Ink),
                                fontSize = 15.sp,
                                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                            ),
                        )
                        val details = listOfNotNull(
                            task.deadline.takeIf { it.isNotEmpty() },
                            task.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                        ).joinToString("  ")
                        if (details.isNotEmpty()) {
                            Text(
                                details,
                                style = TextStyle(
                                    color = color(if (overdue) StickyColors.Danger else StickyColors.Muted),
                                    fontSize = 12.sp,
                                    fontWeight = if (overdue) FontWeight.Medium else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                    Text(
                        "×",
                        modifier = GlanceModifier.padding(horizontal = 12.dp, vertical = 6.dp).clickable(
                            actionRunCallback<DeleteAction>(actionParametersOf(TaskIdKey to task.id)),
                        ),
                        style = TextStyle(color = color(StickyColors.Muted), fontSize = 20.sp),
                    )
                }
            }
        }
    }
}

class StickyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StickyWidget()
}

/** Widget'tan "yapıldı" işareti: yerele yazar, widget'ı yeniler, senkron planlar. */
class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        val done = parameters[DoneKey] ?: return
        withContext(Dispatchers.IO) { runCatching { TaskStore.get(context).update(id, TaskPatch(done = done)) } }
        updateStickyWidgets(context)
        SyncScheduler.afterChange(context)
    }
}

/** Widget'tan silme (tombstone olarak). */
class DeleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        withContext(Dispatchers.IO) { runCatching { TaskStore.get(context).delete(id) } }
        updateStickyWidgets(context)
        SyncScheduler.afterChange(context)
    }
}
