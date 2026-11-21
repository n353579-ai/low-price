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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nunppong.alert.bt.CdmHelper
import com.nunppong.alert.bt.PairedDevice
import com.nunppong.alert.core.CameraRepository
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason
import com.nunppong.alert.core.Stage
import com.nunppong.alert.core.StatusRepo
import com.nunppong.alert.service.DrivingService
import com.nunppong.alert.service.Passenger
import com.nunppong.alert.service.stageText
import com.nunppong.alert.ui.theme.DangerRed
import com.nunppong.alert.ui.theme.SafeGreen
import com.nunppong.alert.ui.theme.WarningYellow
import com.nunppong.alert.vehicle.VehicleAssist
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

// ───────────── 홈 화면 ─────────────
@Composable
fun HomeScreen(refresh: Int, goPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val status by StatusRepo.status.collectAsStateWithLifecycle()
    val ready = remember(refresh) { Perms.readyToRun(ctx) }
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    val passengerActive = remember(refresh, tick, status) { prefs.isPassengerActive() }
    val isRunning = status.mode != Mode.IDLE

    Page {
        // 앱 상단 헤더
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "눈뽕 단속알림",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "화면 테두리 깜빡임으로 알려주는 안전 주행",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 운전자용 대형 상태 대시보드 카드
        val cardBorderColor = when {
            status.overspeed -> DangerRed
            status.stage == Stage.S3 -> DangerRed
            status.stage == Stage.S1 || status.stage == Stage.S2 -> WarningYellow
            isRunning -> SafeGreen
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, cardBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "현재 동작 상태",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 상태 인디케이터 배지
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            passengerActive -> Color(0xFF8B5CF6).copy(alpha = 0.15f)
                            isRunning -> SafeGreen.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            text = when {
                                passengerActive -> "승객 모드"
                                status.mode == Mode.REAL -> "주행 중"
                                status.mode == Mode.SIM -> "시뮬레이션"
                                else -> "대기 중"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                passengerActive -> Color(0xFF8B5CF6)
                                isRunning -> SafeGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                val mainText = when (status.mode) {
                    Mode.IDLE -> if (passengerActive) "승객 모드 (${hm(prefs.passengerUntil)}까지 조용)"
                        else if (prefs.btAddress.isNotEmpty()) "내 차(${prefs.btName.ifEmpty { "연결 대기" }})를 기다리는 중"
                        else "대기 중 · 준비 완료"
                    Mode.SIM -> stageText(status)
                    Mode.REAL -> when (status.reason) {
                        Reason.BLUETOOTH -> "내 차 연결됨 · "
                        Reason.VEHICLE -> "차량 이동 감지 · "
                        else -> ""
                    } + stageText(status)
                }

                Text(
                    text = mainText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (status.overspeed) DangerRed else MaterialTheme.colorScheme.onSurface,
                )

                // 주행 중일 때 추가 정보 표시 (속도 및 남은 거리)
                if (isRunning && (status.speedKmh > 0 || status.distanceM != null)) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "현재 속도 ${status.speedKmh} km/h",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        status.distanceM?.let { d ->
                            Text(
                                "단속까지 ${d}m",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (status.overspeed) DangerRed else WarningYellow,
                            )
                        }
                    }
                }
            }
        }

        // 권한 미비 경고 카드
        if (!ready) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, DangerRed, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "⚠️ 필수 권한 설정이 필요합니다",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "화면 위에 테두리를 그리고 위치를 측정하려면 '다른 앱 위 표시'와 '위치(항상 허용)' 권한이 반드시 필요해요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = goPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("권한 설정하러 가기", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        // 운전 중 누르기 편한 큰 조작 버튼
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { DrivingService.startReal(ctx) },
                enabled = ready && status.mode != Mode.REAL,
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("▶ 주행 시작", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { DrivingService.stop(ctx) },
                enabled = isRunning,
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(14.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = isRunning).copy(width = 2.dp),
            ) {
                Text("■ 알림 종료", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 승객 모드 토글 대형 버튼
        OutlinedButton(
            onClick = { Passenger.toggle(ctx); tick++ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = if (passengerActive) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                    contentColor = Color(0xFF7C3AED),
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Text(
                if (passengerActive) "🙋 승객 모드 해제 (${hm(prefs.passengerUntil)}까지 꺼짐)"
                else "🚕 지금은 승객 (1시간 동안 끄기)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 단속카메라 데이터 로드 상태 안내
        val cams by StatusRepo.cameraCount.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            val g = withContext(Dispatchers.Default) { CameraRepository.load(ctx) }
            StatusRepo.cameraCount.value = g.count
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("📡", fontSize = 20.sp)
                Text(
                    text = when (cams) {
                        null -> "단속카메라 데이터를 확인하는 중입니다…"
                        0 -> "단속카메라 데이터가 비어 있습니다. 공공데이터를 확인해 주세요."
                        else -> "전국 단속카메라 ${cams}개 탑재 완료 (오프라인 동작) · 도로 정보 © OpenStreetMap contributors"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ───────────── 권한 화면 ─────────────
@Composable
private fun PermCard(
    step: Int,
    title: String,
    desc: String,
    done: Boolean,
    button: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (done) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$step. $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (done) SafeGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                ) {
                    Text(
                        if (done) "완료" else "설정 필요",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (done) SafeGreen else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!done) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(button, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
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
            CdmHelper.finish(ctx, d)
            saved = d.name
            msg = "'${d.name}' 등록이 완료되었어요"
        } else {
            msg = "등록이 취소되었습니다"
        }
        onChanged()
    }

    Page {
        Text("권한 안내", style = MaterialTheme.typography.headlineLarge)
        Text(
            "내비게이션 위에 안전하게 테두리를 띄우기 위해 아래 권한들을 순서대로 허용해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermCard(
            step = 1,
            title = "다른 앱 위에 표시",
            desc = "내비게이션 화면 가장자리에 노랑·빨강 안전 테두리를 띄우기 위해 필요해요. 화면 터치는 가로채지 않고 그대로 통과합니다.",
            done = overlay,
            button = "권한 설정 열기",
        ) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }

        PermCard(
            step = 2,
            title = "위치 정보 (항상 허용)",
            desc = "화면이 꺼져 있거나 다른 앱을 쓰는 중에도 단속카메라까지의 거리를 정확히 계산하려면 '항상 허용'이 필요합니다.",
            done = fine && bg,
            button = if (!fine) "위치 권한 허용" else "설정에서 '항상 허용'으로 변경",
        ) {
            if (!fine) {
                fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else if (Build.VERSION.SDK_INT >= 29) {
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        PermCard(
            step = 3,
            title = "알림 허용",
            desc = "백그라운드 주행 알림과 '지금은 승객' 토글 버튼을 상태창에 보여드리기 위해 필요합니다.",
            done = noti,
            button = "알림 권한 허용",
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 4. 내 차 블루투스 설정 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "4. 내 차 블루투스 연동",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (saved.isNotEmpty()) SafeGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            if (saved.isNotEmpty()) "등록됨" else "선택 필요",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (saved.isNotEmpty()) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "내 차 블루투스가 연결되면 자동으로 켜지고, 시동을 끄면 저절로 꺼집니다. 버스나 택시에서는 울리지 않아요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (saved.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "현재 등록된 차량: $saved",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!btOk) {
                    Button(
                        onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("블루투스 권한 허용", fontWeight = FontWeight.Bold)
                    }
                } else {
                    val devices = remember(refresh, saved) { CdmHelper.bonded(ctx) }
                    if (devices.isEmpty()) {
                        Text(
                            "페어링된 기기가 없습니다. 스마트폰 블루투스 설정에서 차량 오디오와 먼저 연결해 주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    devices.forEach { d ->
                        val selected = d.address.equals(prefs.btAddress, true)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = d
                                    if (Build.VERSION.SDK_INT < 31) {
                                        prefs.btAddress = d.address
                                        prefs.btName = d.name
                                        saved = d.name
                                        msg = "'${d.name}' 차량이 저장되었어요"
                                    } else {
                                        msg = "차량 등록 진행 중…"
                                        CdmHelper.associate(ctx, d.address,
                                            onChooser = { cdmLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                                            onError = { e ->
                                                msg = "등록 실패: $e (차량 블루투스가 연결된 상태에서 시도해 주세요)"
                                            })
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    msg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ───────────── 설정 화면 ─────────────
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var thickness by remember { mutableFloatStateOf(prefs.thickness) }
    var night by remember { mutableStateOf(prefs.nightMode) }

    Page {
        Text("설정", style = MaterialTheme.typography.headlineLarge)

        // 1. 디스플레이 & 시각 알림 그룹
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "화면 테두리 표시 설정",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("테두리 밝기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${(brightness * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it; prefs.brightness = it },
                        valueRange = 0.3f..1f,
                    )
                }

                val base = 10f * thickness
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("테두리 두께", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${base.roundToInt()}dp (과속 ${(30f * thickness).roundToInt()}dp)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = thickness,
                        onValueChange = { thickness = it; prefs.thickness = it },
                        valueRange = 0.7f..2.0f,
                    )
                }

                var spreadV by remember { mutableFloatStateOf(prefs.spread) }
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("안쪽 번짐 세기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (spreadV < 0.05f) "끔" else "${(spreadV * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = spreadV,
                        onValueChange = { spreadV = it; prefs.spread = it },
                        valueRange = 0f..1.5f,
                    )
                    Text(
                        "단속 지점에 가까워질수록 색이 화면 안쪽으로 더 넓고 진하게 번져요. 깜빡임 횟수는 그대로예요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Column {
                    Text("표시 위치", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    var edge by remember { mutableStateOf(prefs.edgeMode) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("sides" to "화면 좌우 양쪽", "all" to "사방 전체 테두리").forEach { (k, label) ->
                            FilterChip(
                                selected = edge == k,
                                onClick = { edge = k; prefs.edgeMode = k },
                                label = { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
                                modifier = Modifier.height(44.dp),
                            )
                        }
                    }
                }

                Column {
                    Text("야간 눈부심 방지 (밤 모드)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "밤에는 테두리를 차분하게 어둡게 줄여(밝기 65%) 운전자의 눈부심을 막아줍니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "자동(일몰~일출)", "on" to "항상 켬", "off" to "항상 끔").forEach { (k, label) ->
                            FilterChip(
                                selected = night == k,
                                onClick = { night = k; prefs.nightMode = k },
                                label = { Text(label, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.height(42.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // 커스텀 색상 선택
                var yellow by remember { mutableIntStateOf(prefs.yellowColor) }
                var red by remember { mutableIntStateOf(prefs.redColor) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("원거리 알림 색상 (1km ~ 500m)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ColorRow(
                        listOf(0xFFFFC400, 0xFFC6FF00, 0xFFFF8A00, 0xFF00E5FF, 0xFFFFFFFF),
                        yellow,
                    ) { yellow = it; prefs.yellowColor = it }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("단속 임박 색상 (300m 이내)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ColorRow(
                        listOf(0xFFFF2A1A, 0xFFFF2D95, 0xFFFF6D00, 0xFFB388FF, 0xFFFFFFFF),
                        red,
                    ) { red = it; prefs.redColor = it }
                }

                OutlinedButton(
                    onClick = {
                        yellow = 0xFFFFC400.toInt()
                        red = 0xFFFF2A1A.toInt()
                        prefs.yellowColor = yellow
                        prefs.redColor = red
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("색상 기본값으로 복원 (노랑 / 빨강)")
                }
            }
        }

        // 2. 스마트 주행 옵션 그룹
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "스마트 주행 안내 옵션",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                var overOnly by remember { mutableStateOf(prefs.overspeedOnly) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("과속할 때만 경고하기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "제한속도를 5km/h 넘을 때만 테두리가 울려요. 규정속도 준수 중에는 화면이 조용합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = overOnly, onCheckedChange = { overOnly = it; prefs.overspeedOnly = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                var calm by remember { mutableStateOf(prefs.calmMode) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("지키는 중이면 큰 숫자로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "제한속도를 알고 지키는 중이면 깜빡이지 않고 화면 위쪽에 제한속도를 큰 숫자로 보여줘요. 제한속도를 넘으면 그때부터 깜빡여요. 제한속도를 모르는 카메라는 항상 깜빡여요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = calm, onCheckedChange = { calm = it; prefs.calmMode = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                var wake by remember { mutableStateOf(prefs.wakeScreen) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("단속 접근 시 꺼진 화면 켜기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "화면이 꺼져 있거나 잠겨 있어도 단속 1km 전방에서 화면을 켜고 잠금 화면 위에 표시합니다. 잠금을 풀면 자동으로 사라집니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wake, onCheckedChange = { wake = it; prefs.wakeScreen = it })
                }
            }
        }

        // 3. 자동 실행 및 배터리 그룹
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "차량 자동 연결 & 전원",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    "기본: 내 차 블루투스 연결 시 자동 시작, 연결 끊김 시 자동 종료\n" +
                    (if (prefs.btName.isNotEmpty()) "현재 연결 차량: ${prefs.btName}" else "권한 탭에서 내 차 블루투스를 등록해 주세요."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var assist by remember { mutableStateOf(prefs.vehicleAssist) }
                val arLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) { prefs.vehicleAssist = true; assist = true; VehicleAssist.sync(ctx) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("블루투스 없는 차량 보조 감지", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "충전기 연결 상태에서 차량 주행이 감지되면 자동 시작합니다. (배터리 사용량 증가 가능)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = assist, onCheckedChange = { on ->
                        if (!on) { prefs.vehicleAssist = false; assist = false; VehicleAssist.sync(ctx) }
                        else if (VehicleAssist.hasPermission(ctx)) { prefs.vehicleAssist = true; assist = true; VehicleAssist.sync(ctx) }
                        else if (Build.VERSION.SDK_INT >= 29) arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    })
                }

                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("배터리 최적화 예외 등록 (백그라운드 유지)")
                }
            }
        }

        // 4. 단속 피드백 관리
        val fb = remember { com.nunppong.alert.core.CameraFeedback.get(ctx) }
        var fbCount by remember { mutableIntStateOf(fb.size) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "사용자 단속 피드백 관리",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "카메라를 통과한 후 '방금 단속 맞았나요?' 알림에서 👎를 누른 카메라는 해당 주행 방향에서 다시는 알리지 않아요. 현재 ${fbCount}곳이 꺼져 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { fb.clear(); fbCount = 0 },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("꺼둔 카메라 피드백 모두 초기화")
                }
            }
        }

        // 5. 최근 알림 기록 (틀린 알림 원인 확인용)
        val alertLog = remember { com.nunppong.alert.core.AlertLog.get(ctx) }
        var entries by remember { mutableStateOf(alertLog.all()) }
        val limitFix = remember { com.nunppong.alert.core.LimitOverride.get(ctx) }
        var limitEdit by remember { mutableStateOf<com.nunppong.alert.core.AlertEntry?>(null) }
        var limitText by remember { mutableStateOf("") }
        limitEdit?.let { e ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { limitEdit = null },
                title = { Text("이 카메라의 실제 제한속도") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it.filter { c -> c.isDigit() }.take(3) },
                        singleLine = true,
                        label = { Text("km/h (예: 70)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        limitText.toIntOrNull()?.let { if (it in 10..150) limitFix.set(e.camLat, e.camLon, it) }
                        limitEdit = null
                    }) { Text("저장") }
                },
                dismissButton = {
                    TextButton(onClick = { limitFix.set(e.camLat, e.camLon, 0); limitEdit = null }) { Text("원래대로") }
                },
            )
        }
        val clipboard = LocalClipboardManager.current
        val fmt = remember { java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.KOREA) }
        fun line(e: com.nunppong.alert.core.AlertEntry) =
            "${fmt.format(java.util.Date(e.timeMs))} · 제한 ${e.limit} · ${e.rule} · ${e.distM}m · ${e.speedKmh}km/h${if (e.sideM != com.nunppong.alert.core.AlertEntry.SIDE_UNKNOWN) " · 카메라 " + (if (e.sideM >= 0) "오른쪽" else "왼쪽") + " ${Math.abs(e.sideM)}m" else ""} · https://maps.google.com/?q=${e.camLat},${e.camLon}"
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "최근 알림 기록",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "주행이 끝난 뒤 여기서 확인해 주세요. 틀린 알림에 👎를 누르면 그 카메라를 그 방향에서 끕니다. '지도로 보기'로 그 자리를 볼 수 있어요. 기록은 폰 안에만 저장돼요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entries.isEmpty()) {
                    Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyMedium)
                }
                entries.take(20).forEach { e ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(java.util.Date(e.timeMs)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "제한 ${e.limit} · ${e.rule} · ${e.distM}m · ${e.speedKmh}km/h",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (e.sideM != com.nunppong.alert.core.AlertEntry.SIDE_UNKNOWN) {
                                Text(
                                    "지나갈 때 카메라 위치: 내 ${if (e.sideM >= 0) "오른쪽" else "왼쪽"} ${Math.abs(e.sideM)}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (e.speeds.isNotEmpty()) {
                                Text(
                                    "카메라 300/150/50m 전 속도: ${e.speeds}km/h",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 주차한 뒤에 누르는 큰 버튼: 아니에요(👎)는 그 카메라를 그 방향에서 끈다
                        TextButton(onClick = {
                            alertLog.setReview(e.timeMs, 1)
                            entries = alertLog.all()
                        }) { Text(if (e.review == 1) "👍✓" else "👍", fontSize = 22.sp) }
                        TextButton(onClick = {
                            alertLog.setReview(e.timeMs, 2)
                            if (e.heading >= 0) com.nunppong.alert.core.CameraFeedback.get(ctx).block(e.camLat, e.camLon, e.heading.toDouble())
                            entries = alertLog.all()
                        }) { Text(if (e.review == 2) "👎✓" else "👎", fontSize = 22.sp) }
                        TextButton(onClick = {
                            val uri = Uri.parse("https://maps.google.com/?q=${e.camLat},${e.camLon}")
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                            }
                        }) { Text("지도로 보기") }
                    }
                    TextButton(onClick = { limitText = ""; limitEdit = e }) {
                        val fixed = limitFix.get(e.camLat, e.camLon)
                        Text(if (fixed > 0) "제한속도 고침: $fixed (눌러서 다시 고치기)" else "제한속도가 달라요? 고치기 (지금 ${e.limit})")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(entries.joinToString("\n") { line(it) })) },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty(),
                    ) { Text("기록 복사") }
                    OutlinedButton(
                        onClick = { alertLog.clear(); entries = emptyList() },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty(),
                    ) { Text("기록 지우기") }
                }
            }
        }
    }
}

// ───────────── 시뮬레이션 화면 ─────────────
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
        Text("주행 시뮬레이션", style = MaterialTheme.typography.headlineLarge)
        Text(
            "1.5km 전방에서 단속카메라에 접근하는 상황을 실제 테두리 오버레이와 큰 제한속도 표지판으로 미리 체험해 보세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 대형 주행 계기판 카드
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    2.dp,
                    if (status.overspeed) DangerRed
                    else if (running) WarningYellow
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(20.dp),
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (running) "가상 주행 진행 중" else "시뮬레이션 대기 중",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.overspeed) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DangerRed.copy(alpha = 0.15f),
                        ) {
                            Text(
                                "🚨 과속 감지!",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = DangerRed,
                            )
                        }
                    }
                }

                Text(
                    text = if (running) stageText(status) else "시뮬레이션을 시작해 보세요",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (status.overspeed) DangerRed else MaterialTheme.colorScheme.onSurface,
                )

                if (running) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "현재 속도: ${status.speedKmh} km/h",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "제한 속도: ${limit} km/h",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 속도 조절 슬라이더
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("내 주행 속도", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${speed} km/h",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = speed.toFloat(),
                    onValueChange = { StatusRepo.simSpeedKmh.value = it.roundToInt() },
                    valueRange = 20f..140f,
                )
            }
        }

        // 카메라 제한속도 선택
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("카메라 규정 제한속도", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(60, 80, 100).forEach { v ->
                        FilterChip(
                            selected = limit == v,
                            onClick = { StatusRepo.simLimitKmh.value = v },
                            label = { Text("${v} km/h", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }
        }

        // 빠른 테스트 배속 옵션
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("3배속 빠른 재생", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("단속 구간 접근을 빠르게 확인합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = fast, onCheckedChange = { StatusRepo.simFast.value = it })
            }
        }

        // 제어 버튼
        if (!ready) {
            Button(
                onClick = goPermissions,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("권한 허용 후 시뮬레이션 가능", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else if (running) {
            Button(
                onClick = { DrivingService.stop(ctx) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("■ 시뮬레이션 종료", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { DrivingService.startSim(ctx) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("▶ 시뮬레이션 시작 (홈 버튼을 눌러 확인해 보세요)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun hm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(ms))

@Composable
private fun ColorRow(colors: List<Long>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        colors.forEach { c ->
            val argb = c.toInt()
            val isSelected = argb == selected
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        if (isSelected) 3.5.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}
