package com.melody.melodyplus.ui.navigation

/**
 * 配置界面的底部 Tab。
 * 数量固定为 4，避免小屏底栏文字拥挤。
 */
enum class MainTab(val label: String) {
    OVERVIEW("概览"),
    DEVICES("设备"),
    APPEARANCE("外观"),
    ABOUT("关于"),
}