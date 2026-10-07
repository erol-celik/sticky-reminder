package com.sticky.reminder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: StickyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Açık zemin: durum ve gezinme çubuğu simgeleri koyu olsun.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent { StickyApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        SyncScheduler.onStart(this) // uygulama açılırken senkron (giriş yapılmışsa)
    }
}
