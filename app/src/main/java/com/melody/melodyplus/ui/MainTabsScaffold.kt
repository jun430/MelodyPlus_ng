package com.melody.melodyplus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.melody.melodyplus.ui.components.GlassNavigationBar
import com.melody.melodyplus.ui.components.WallpaperBackground
import com.melody.melodyplus.ui.config.UiSettings
import com.melody.melodyplus.ui.navigation.MainTab
import com.melody.melodyplus.ui.pages.AboutPage
import com.melody.melodyplus.ui.pages.AppearancePage
import com.melody.melodyplus.ui.pages.DevicesPage
import com.melody.melodyplus.ui.pages.OverviewPage
import kotlinx.coroutines.launch

/**
 * 配置页主体：全屏壁纸背景（兼作液体玻璃背板录制层）+ 透明 Scaffold（顶部栏 / 内容区 / 玻璃底栏）。
 *
 * [液体玻璃重构] 底栏的真玻璃效果需要能“看到”身后的内容：
 *   把**全屏壁纸**通过 `Modifier.layerBackdrop(backdrop)` 录进 GraphicsLayer，
 *   再把 backdrop 交给 [GlassNavigationBar]，由 `drawBackdrop()` 采样做折射/模糊。
 *   此时 Scaffold 必须透明（`containerColor = Color.Transparent`），否则不透明的容器色
 *   会盖住底下的壁纸、也让背板采样到一片纯色。
 *   注意：底栏位于 Scaffold 的 bottomBar 槽位（内容区 padding 之外），因此录制层必须
 *   是**全屏**壁纸，才能覆盖到底栏正下方的像素。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScaffold(
    settings: UiSettings,
    onSettingsChange: (UiSettings) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { MainTab.entries.size })
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }
    // 液体玻璃底栏的背板：全屏壁纸内容录入此层
    val backdrop = rememberLayerBackdrop()

    fun selectTab(tab: MainTab) {
        if (selectedTab != tab) selectedTab = tab
        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedTab = MainTab.entries[page.coerceIn(0, MainTab.entries.lastIndex)]
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1) 全屏壁纸：既作视觉背景，也作为背板录制层（完整覆盖底栏所在区域）。
        WallpaperBackground(
            settings = settings,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) { }

        // 2) 透明 Scaffold：顶部栏 / 内容 / 玻璃底栏都浮在壁纸之上。
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(text = if (selectedTab == MainTab.OVERVIEW) "MelodyPlus" else selectedTab.label) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                )
            },
            bottomBar = {
                GlassNavigationBar(
                    tabs = MainTab.entries,
                    selected = selectedTab,
                    settings = settings,
                    onSelect = ::selectTab,
                    backdrop = backdrop,
                )
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { page ->
                val tab = MainTab.entries[page.coerceIn(0, MainTab.entries.lastIndex)]
                key(tab) {
                    when (tab) {
                        MainTab.OVERVIEW -> OverviewPage(settings = settings)
                        MainTab.DEVICES -> DevicesPage(settings = settings)
                        MainTab.APPEARANCE -> AppearancePage(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                        )
                        MainTab.ABOUT -> AboutPage(settings = settings)
                    }
                }
            }
        }
    }
}
