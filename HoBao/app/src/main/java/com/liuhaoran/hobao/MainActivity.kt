package com.liuhaoran.hobao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.liuhaoran.hobao.ui.HoBaoApp
import com.liuhaoran.hobao.ui.theme.HoBaoTheme

/**
 * Activity(活动) 是安卓的"一个屏幕"。它是系统的入口，
 * 系统启动 app 时只认这个类，其他所有界面都活在它里面。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边显示：内容画到状态栏/导航栏底下，Material 3 的规范要求
        enableEdgeToEdge()
        setContent {
            HoBaoTheme {
                HoBaoApp()
            }
        }
    }
}
