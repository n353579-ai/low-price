package com.nunppong.alert.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.StatusRepo
import com.nunppong.alert.service.DrivingService
import com.nunppong.alert.service.stageText
import kotlin.math.roundToInt

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

// ───────────── 홈 ─────────────
@Composable
fun HomeScreen(refresh: Int, goPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val status by StatusRepo.status.collectAsStateWithLifecycle()
    val ready = remember(refresh) { Perms.readyToRun(ctx) }
    Page {
        Text("눈뽕 단속알림", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("현재 상태", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val text = when (status.mode) {
                    Mode.IDLE -> "대기 중"
                    Mode.SIM -> "시뮬레이션 · " + stageText(status)
                    Mode.REAL -> stageText(status)
                }
                Text(text, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (!ready) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("권한 설정이 필요해요. 다른 앱 위 표시와 위치 권한이 있어야 동작합니다.")
                    Button(onClick = goPermissions) { Text("권한 설정하러 가기") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {}, enabled = false) { Text("수동 시작") }
            OutlinedButton(onClick = {}, enabled = false) { Text("종료") }
        }
        Text("실제 주행 시작/종료는 다음 단계에서 활성화됩니다. 지금은 '시뮬레이션' 탭에서 테두리를 확인하세요.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────── 권한 ─────────────
@Composable
private fun PermCard(step: Int, title: String, desc: String, done: Boolean, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$step. $title", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (done) "✅ 완료" else "⬜ 필요")
            }
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            if (!done) Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
fun PermissionScreen(refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val overlay = remember(refresh) { Perms.overlay(ctx) }
    val fine = remember(refresh) { Perms.fineLocation(ctx) }
    val bg = remember(refresh) { Perms.backgroundLocation(ctx) }
    val noti = remember(refresh) { Perms.notifications(ctx) }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val notiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    Page {
        Text("권한 안내", style = MaterialTheme.typography.headlineSmall)
        Text("아래 순서대로 허용해 주세요.", style = MaterialTheme.typography.bodyMedium)

        PermCard(1, "다른 앱 위에 표시", "다른 화면 위에 노랑·빨강 테두리를 그리기 위해 필요해요. 화면 터치는 가로채지 않아요.",
            overlay, "설정 열기") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        PermCard(2, "위치 (항상 허용)", "앱을 안 켜고 있어도 단속카메라까지의 거리를 계산하려면 '항상 허용'이 필요해요. " +
            "먼저 위치를 허용한 뒤, 한 번 더 '항상 허용'을 골라 주세요.",
            fine && bg, if (!fine) "위치 허용" else "항상 허용으로 변경") {
            if (!fine) fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        PermCard(3, "알림", "동작 중임을 알리는 알림과 '지금은 승객' 버튼을 보여주기 위해 필요해요.",
            noti, "알림 허용") {
            if (Build.VERSION.SDK_INT >= 33) notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ───────────── 설정 ─────────────
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var thickness by remember { mutableFloatStateOf(prefs.thickness) }
    var night by remember { mutableStateOf(prefs.nightMode) }

    Page {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        Text("밝기  ${(brightness * 100).roundToInt()}%")
        Slider(value = brightness, onValueChange = { brightness = it; prefs.brightness = it }, valueRange = 0.3f..1f)

        val base = 12f * thickness
        Text("테두리 두께  ${base.roundToInt()}dp (과속 시 ${(20f * thickness).roundToInt()}dp)")
        Slider(value = thickness, onValueChange = { thickness = it; prefs.thickness = it }, valueRange = 0.7f..1.5f)

        Text("밤 모드 (밤에는 색을 어둡게, 전체 밝기 65%)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "자동(일몰~일출)", "on" to "항상 켬", "off" to "끔").forEach { (k, label) ->
                FilterChip(selected = night == k, onClick = { night = k; prefs.nightMode = k }, label = { Text(label) })
            }
        }
        Text("자동 시작 방식은 4단계에서 추가됩니다.", style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────── 시뮬레이션 ─────────────
@Composable
fun SimulationScreen(refresh: Int, goPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val status by StatusRepo.status.collectAsStateWithLifecycle()
    val speed by StatusRepo.simSpeedKmh.collectAsStateWithLifecycle()
    val limit by StatusRepo.simLimitKmh.collectAsStateWithLifecycle()
    val fast by StatusRepo.simFast.collectAsStateWithLifecycle()
    val ready = remember(refresh) { Perms.readyToRun(ctx) }
    val running = status.mode == Mode.SIM

    Page {
        Text("시뮬레이션", style = MaterialTheme.typography.headlineSmall)
        Text("1.5km 앞에서 출발해 단속카메라를 지나가는 장면을 실제 오버레이로 보여줘요. " +
            "시작한 뒤 홈 버튼을 눌러 다른 앱 위에서도 확인해 보세요. 속도는 달리는 중에도 바꿀 수 있어요.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (running) stageText(status) else "정지됨", style = MaterialTheme.typography.titleMedium)
                if (running) Text("속도 ${status.speedKmh}km/h · 제한 ${limit}km/h")
            }
        }

        Text("내 속도  ${speed}km/h")
        Slider(value = speed.toFloat(), onValueChange = { StatusRepo.simSpeedKmh.value = it.roundToInt() },
            valueRange = 20f..140f)

        Text("카메라 제한속도")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 80, 100).forEach { v ->
                FilterChip(selected = limit == v, onClick = { StatusRepo.simLimitKmh.value = v }, label = { Text("${v}km/h") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fast, onCheckedChange = { StatusRepo.simFast.value = it })
            Spacer(Modifier.width(8.dp))
            Text("3배속 (빨리 보기)")
        }
        Text("300m 안에서 제한속도를 넘으면 테두리가 20dp로 두꺼워져요.", style = MaterialTheme.typography.bodySmall)

        if (!ready) {
            Text("먼저 '권한' 탭에서 다른 앱 위 표시와 위치 권한을 허용해 주세요.")
            Button(onClick = goPermissions) { Text("권한 설정하러 가기") }
        } else if (running) {
            Button(onClick = { DrivingService.stop(ctx) }) { Text("시뮬레이션 종료") }
        } else {
            Button(onClick = { DrivingService.startSim(ctx) }) { Text("시뮬레이션 시작") }
        }
    }
}
