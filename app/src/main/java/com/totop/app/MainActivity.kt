package com.totop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.totop.app.navigation.ToTopApp
import com.totop.app.ui.theme.ToTopTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity，UI 全部为 Jetpack Compose，不使用任何 XML View。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToTopRoot()
        }
    }
}

@Composable
private fun ToTopRoot() {
    ToTopTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 导航图入口：8 条路由见 navigation/NavGraph.kt
            ToTopApp()
        }
    }
}
