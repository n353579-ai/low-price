package com.nunppong.alert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nunppong.alert.ui.*
import com.nunppong.alert.ui.theme.NunppongTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.nunppong.alert.vehicle.VehicleAssist.sync(this)
        setContent {
            NunppongTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var refresh by remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

                val tabs = listOf(
                    Triple("🏠", "홈", 0),
                    Triple("🛡️", "권한", 1),
                    Triple("⚙️", "설정", 2),
                    Triple("🎬", "시뮬", 3)
                )

                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            tonalElevation = 6.dp,
                        ) {
                            tabs.forEach { (icon, label, idx) ->
                                NavigationBarItem(
                                    selected = tab == idx,
                                    onClick = { tab = idx },
                                    icon = {
                                        Text(
                                            icon,
                                            fontSize = if (tab == idx) 22.sp else 18.sp
                                        )
                                    },
                                    label = {
                                        Text(
                                            label,
                                            fontWeight = if (tab == idx) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> HomeScreen(refresh) { tab = 1 }
                            1 -> PermissionScreen(refresh) { refresh++ }
                            2 -> SettingsScreen()
                            else -> SimulationScreen(refresh) { tab = 1 }
                        }
                    }
                }
            }
        }
    }
}
