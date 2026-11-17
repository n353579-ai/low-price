package com.nunppong.alert.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nunppong.alert.core.CameraRepository
import com.nunppong.alert.bt.CdmHelper
import com.nunppong.alert.bt.PairedDevice
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Reason
import com.nunppong.alert.service.Passenger
import com.nunppong.alert.vehicle.VehicleAssist
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.StatusRepo
import com.nunppong.alert.service.DrivingService
import com.nunppong.alert.service.stageText
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    val passengerActive = remember(refresh, tick, status) { prefs.isPassengerActive() }
    Page {
        Text("눈뽕 단속알림", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("현재 상태", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val text = when (status.mode) {
                    Mode.IDLE -> if (passengerActive) "승객 모드 (${hm(prefs.passengerUntil)}까지)"
                        else if (prefs.btAddress.isNotEmpty()) "대기 중 · 내 차 연결을 기다리는 중" else "대기 중"
                    Mode.SIM -> "시뮬레이션 · " + stageText(status)
                    Mode.REAL -> when (status.reason) {
                        Reason.BLUETOOTH -> "내 차 연결됨 · "
                        Reason.VEHICLE -> "차량 감지 · "
                        else -> ""
                    } + stageText(status)
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
            Button(
                onClick = { DrivingService.startReal(ctx) },
                enabled = ready && status.mode != Mode.REAL,
            ) { Text("수동 시작") }
            OutlinedButton(
                onClick = { DrivingService.stop(ctx) },
                enabled = status.mode != Mode.IDLE,
            ) { Text("종료") }
        }
        OutlinedButton(onClick = { Passenger.toggle(ctx); tick++ }) {
            Text(if (passengerActive) "승객 모드 해제 (${hm(prefs.passengerUntil)}까지 꺼짐)" else "지금은 승객 (1시간 끄기)")
        }
        val cams by StatusRepo.cameraCount.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            val g = withContext(Dispatchers.Default) { CameraRepository.load(ctx) }
            StatusRepo.cameraCount.value = g.count
        }
        Text(
            when (cams) {
                null -> "단속카메라 데이터 확인 중…"
                0 -> "⚠ 단속카메라 데이터가 비어 있어요. 저장소의 nunppong-alert/data/ 폴더에 공공데이터 CSV를 올리면 빌드 때 자동 반영돼요."
                else -> "단속카메라 ${cams}개 로드됨 (오프라인)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
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

    val prefs = remember { Prefs(ctx) }
    val btOk = remember(refresh) { Perms.bluetoothConnect(ctx) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    var saved by remember(refresh) { mutableStateOf(prefs.btName.ifEmpty { prefs.btAddress }) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<PairedDevice?>(null) }
    val cdmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val d = picked
        if (r.resultCode == Activity.RESULT_OK && d != null) {
            CdmHelper.finish(ctx, d); saved = d.name; msg = "'${d.name}' 등록 완료"
        } else msg = "등록이 취소됐어요"
        onChanged()
    }

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

        // 4. 내 차 블루투스
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("4. 내 차 블루투스 선택", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (saved.isNotEmpty()) "✅ 완료" else "⬜ 필요")
                }
                Text("내 차 오디오(블루투스)가 연결되면 자동으로 켜지고, 끊기면 꺼져요. 버스·택시에서는 울리지 않아요. " +
                    "차에 타서 블루투스가 연결된 상태에서 고르면 가장 잘 등록돼요.")
                if (saved.isNotEmpty()) Text("현재: $saved", style = MaterialTheme.typography.titleSmall)
                if (!btOk) {
                    Button(onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("블루투스 권한 허용") }
                } else {
                    val devices = remember(refresh, saved) { CdmHelper.bonded(ctx) }
                    if (devices.isEmpty()) Text("페어링된 기기가 없어요. 폰 설정에서 차량과 먼저 페어링해 주세요.")
                    devices.forEach { d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = d.address.equals(prefs.btAddress, true), onClick = {
                                picked = d
                                if (Build.VERSION.SDK_INT < 31) {
                                    prefs.btAddress = d.address; prefs.btName = d.name; saved = d.name; msg = "'${d.name}' 저장 완료"
                                } else {
                                    msg = "등록 중…"
                                    CdmHelper.associate(ctx, d.address,
                                        onChooser = { cdmLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                                        onError = { e ->
                                            msg = "등록 실패: $e (차에서 블루투스가 연결된 상태로 다시 시도해 보세요)"
                                        })
                                }
                            })
                            Text(d.name)
                        }
                    }
                    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
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

        val base = 10f * thickness
        Text("테두리 두께  ${base.roundToInt()}dp (과속 시 ${(30f * thickness).roundToInt()}dp) · 잘 안 보이면 올려 보세요")
        Slider(value = thickness, onValueChange = { thickness = it; prefs.thickness = it }, valueRange = 0.7f..2.0f)

        var overOnly by remember { mutableStateOf(prefs.overspeedOnly) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = overOnly, onCheckedChange = { overOnly = it; prefs.overspeedOnly = it })
            Spacer(Modifier.width(8.dp))
            Text("제한속도를 5km/h 넘을 때만 경고")
        }
        Text("끄면(기본) 단속카메라가 앞에 있을 때 항상 경고해요. 켜면 규정속도로 갈 때는 조용해요. 제한속도를 모르는 카메라는 항상 경고해요.",
            style = MaterialTheme.typography.bodySmall)

        var chr by remember { mutableStateOf(prefs.character) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = chr, onCheckedChange = { chr = it; prefs.character = it })
            Spacer(Modifier.width(8.dp))
            Text("캐릭터 안내 (손가락 1·5·3)")
        }
        Text("거리 단계가 바뀔 때만 작게 2초 나왔다 사라져요. 터치는 그대로 지도로 통과해요.",
            style = MaterialTheme.typography.bodySmall)

        var wake by remember { mutableStateOf(prefs.wakeScreen) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wake, onCheckedChange = { wake = it; prefs.wakeScreen = it })
            Spacer(Modifier.width(8.dp))
            Text("화면이 꺼져 있으면 단속 접근 때 화면 켜기")
        }
        Text("화면이 꺼진 상태에서는 테두리를 보여줄 수 없어서, 단속 1km 앞에서 화면을 5초 켜요. 잠금 화면 위에는 안 보일 수 있어요.",
            style = MaterialTheme.typography.bodySmall)

        var edge by remember { mutableStateOf(prefs.edgeMode) }
        Text("표시 위치")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sides" to "화면 좌우", "all" to "사방 테두리").forEach { (k, label) ->
                FilterChip(selected = edge == k, onClick = { edge = k; prefs.edgeMode = k }, label = { Text(label) })
            }
        }

        var yellow by remember { mutableIntStateOf(prefs.yellowColor) }
        var red by remember { mutableIntStateOf(prefs.redColor) }
        Text("경고 색 ① 멀리 (1km~500m)")
        ColorRow(listOf(0xFFFFC400, 0xFFC6FF00, 0xFFFF8A00, 0xFF00E5FF, 0xFFFFFFFF), yellow) { yellow = it; prefs.yellowColor = it }
        Text("경고 색 ② 임박 (300m 안)")
        ColorRow(listOf(0xFFFF2A1A, 0xFFFF2D95, 0xFFFF6D00, 0xFFB388FF, 0xFFFFFFFF), red) { red = it; prefs.redColor = it }
        OutlinedButton(onClick = {
            yellow = 0xFFFFC400.toInt(); red = 0xFFFF2A1A.toInt()
            prefs.yellowColor = yellow; prefs.redColor = red
        }) { Text("색 기본값으로 (노랑/빨강)") }
        Text("바꾼 색은 시뮬 탭에서 바로 확인할 수 있어요. 시뮬레이션이 켜져 있으면 곧바로 반영돼요.",
            style = MaterialTheme.typography.bodySmall)

        Text("밤 모드 (밤에는 색을 어둡게, 전체 밝기 65%)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "자동(일몰~일출)", "on" to "항상 켬", "off" to "끔").forEach { (k, label) ->
                FilterChip(selected = night == k, onClick = { night = k; prefs.nightMode = k }, label = { Text(label) })
            }
        }
        HorizontalDivider()
        Text("단속 피드백", style = MaterialTheme.typography.titleMedium)
        val fb = remember { com.nunppong.alert.core.CameraFeedback.get(ctx) }
        var fbCount by remember { mutableIntStateOf(fb.size) }
        Text("카메라를 지나면 \"방금 단속 맞았나요?\" 알림이 와요. 👎를 누른 카메라는 그 진행방향에서 꺼요. 지금 ${fbCount}곳 꺼 둠.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { fb.clear(); fbCount = 0 }) { Text("피드백 모두 지우기") }
        HorizontalDivider()
        Text("자동 시작 방식", style = MaterialTheme.typography.titleMedium)
        Text("기본: 내 차 블루투스 연결 → 자동 시작, 연결 끊김 → 종료" +
            (if (prefs.btName.isNotEmpty()) "\n내 차: ${prefs.btName}" else "\n(권한 탭에서 내 차 블루투스를 선택해 주세요)"))

        var assist by remember { mutableStateOf(prefs.vehicleAssist) }
        val arLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) { prefs.vehicleAssist = true; assist = true; VehicleAssist.sync(ctx) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = assist, onCheckedChange = { on ->
                if (!on) { prefs.vehicleAssist = false; assist = false; VehicleAssist.sync(ctx) }
                else if (VehicleAssist.hasPermission(ctx)) { prefs.vehicleAssist = true; assist = true; VehicleAssist.sync(ctx) }
                else if (Build.VERSION.SDK_INT >= 29) arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            })
            Spacer(Modifier.width(8.dp))
            Text("블루투스 없는 차용 (보조)")
        }
        Text("충전기가 연결된 상태에서 차량 이동이 감지되면 시작해요. 정확도가 낮고 배터리를 더 쓸 수 있어서 기본은 꺼져 있어요.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }) { Text("배터리 최적화 제외 (자동 시작이 잘 안 될 때)") }
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
        Text("300m 안에서 제한속도를 넘으면 테두리가 30dp로 두꺼워져요.", style = MaterialTheme.typography.bodySmall)

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

private fun hm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(ms))

@Composable
private fun ColorRow(colors: List<Long>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { c ->
            val argb = c.toInt()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(argb))
                    .border(if (argb == selected) 4.dp else 1.dp,
                        if (argb == selected) Color.Black else Color.Gray, CircleShape)
                    .clickable { onPick(argb) }
            )
        }
    }
}
