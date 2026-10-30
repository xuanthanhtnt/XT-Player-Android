package com.xuanthanhtnt.xtplayer

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalConfiguration
import com.xuanthanhtnt.xtplayer.ui.XtApp
import com.xuanthanhtnt.xtplayer.ui.theme.XtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        setContent {
            val wide = LocalConfiguration.current.screenWidthDp >= 720
            XtTheme(isTv = isTv, isWide = wide) {
                XtApp(isTv = isTv, wide = wide)
            }
        }
    }
}
