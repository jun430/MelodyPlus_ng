package com.melody.melodyplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.melody.melodyplus.ui.ModuleUiTheme
import com.melody.melodyplus.ui.MainTabsScaffold
import com.melody.melodyplus.ui.config.ModuleUiConfigStore
import com.melody.melodyplus.ui.config.UiSettings

/**
 * 模块配置主入口：多 Tab（概览/设备/外观/关于）。
 * 外观设置（壁纸/底栏/主题）持久化并在重启后恢复。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            var settings by remember { mutableStateOf(ModuleUiConfigStore.load(context)) }

            ModuleUiTheme(settings = settings) {
                MainTabsScaffold(
                    settings = settings,
                    onSettingsChange = { next ->
                        settings = next
                        ModuleUiConfigStore.save(context, next)
                    },
                )
            }
        }
    }
}