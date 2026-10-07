package com.sticky.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget'taki "+" düğmesinin açtığı küçük pencere. Widget'larda yazı kutusu olamayacağı için tek
 * satırlık hızlı ekleme burada yapılır; uygulamayı tam açmaz.
 */
class QuickAddActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StickyTheme {
                QuickAddDialog(onAdd = ::add, onCancel = ::finish)
            }
        }
    }

    private fun add(title: String) {
        val appContext = applicationContext
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { TaskStore.get(appContext).add(NewTask(title)) } }
            updateStickyWidgets(appContext)
            SyncScheduler.afterChange(appContext)
            finish()
        }
    }
}

@Composable
private fun QuickAddDialog(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = { if (title.isNotBlank()) onAdd(title.trim()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onCancel)
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(StickyColors.Bg)
                // Karta dokunmak pencereyi kapatmasın.
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Yeni görev", color = StickyColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            StickyField(
                value = title,
                onValueChange = { title = it },
                placeholder = "Görev yaz, tamam ile ekle",
                imeAction = ImeAction.Done,
                onDone = submit,
                focusRequester = focus,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Vazgeç",
                    color = StickyColors.Muted,
                    modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Text(
                    "Ekle",
                    color = StickyColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = submit).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
