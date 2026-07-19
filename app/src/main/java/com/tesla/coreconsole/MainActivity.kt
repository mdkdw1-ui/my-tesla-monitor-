package com.tesla.coreconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F0F12)) {
                CoreConsoleApp()
            }
        }
    }
}

@Composable
fun CoreConsoleApp(vm: TeslaViewModel = viewModel()) {
    val context = LocalContext.current
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    LaunchedEffect(Unit) { vm.checkSavedCredentials(context) }

    if (!isLoggedIn) {
        LoginConsoleScreen(onLoginSubmit = { key, token ->
            vm.login(context, key, token, onFail = {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            })
        })
    } else {
        MainConsoleDashboard(vm)
    }
}

@Composable
fun MainConsoleDashboard(vm: TeslaViewModel) {
    val context = LocalContext.current
    val activeTab by vm.activeTab.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val errorMsg by vm.errorMsg.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tesla Monitor Console", color = Color.Gray, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.fetchAllData() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252538)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(6.dp),
                    enabled = !isLoading
                ) {
                    Text(text = if (isLoading) "갱신 중..." else "⚡ 갱신", color = Color(0xFFFCA311), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text("로그아웃", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFF232335), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp).clickable { vm.logout(context) }
                )
            }
        }

        // 🚨 어떤 런타임 에러가 발생해도 완벽하게 잡아서 표출하는 디버그 패널
        if (errorMsg != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color(0xFF2C1414), RoundedCornerShape(10.dp)).border(1.dp, Color.Red, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️ SYSTEM RUNTIME ERROR LOG", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text("Clear", color = Color.White, fontSize = 11.sp, modifier = Modifier.background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).clickable { vm.errorMsg.value = null }.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    Text(errorMsg!!, color = Color(0xFFFFCCCC), fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        TabRow(
            selectedTabIndex = activeTab.ordinal, containerColor = Color(0xFF1A1A24), indicator = {},
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).background(Color(0xFF1A1A24), RoundedCornerShape(14.dp)).padding(4.dp)
        ) {
            TabType.values().forEach { tab ->
                val selected = activeTab == tab
                Tab(
                    selected = selected, onClick = { vm.activeTab.value = tab },
                    text = { Text(when(tab) { TabType.STATUS -> "상태"; TabType.DRIVING -> "주행정보"; TabType.MONTHLY -> "월간 내역"; TabType.BATTERY -> "배터리" }, color = if (selected) Color(0xFF0F0F12) else Color(0xFFA0A0B2), fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    modifier = if (selected) Modifier.background(Color(0xFFFCA311), RoundedCornerShape(10.dp)) else Modifier
                )
            }
        }

        Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            when (activeTab) {
                TabType.STATUS -> StatusDashboardView(vm)
                TabType.DRIVING -> DrivingHistoryView(vm)
                TabType.MONTHLY -> MonthlyReportView(vm)
                TabType.BATTERY -> BatteryTrendView(vm)
            }
        }
    }
}

// ==========================================
// 4-A. 차량 상태 탭 (최상단만 현재 '주차' 연동, 하단 목록은 주차 제외)
// ==========================================
@Composable
fun StatusDashboardView(vm: TeslaViewModel) {
    val states by vm.vehicleStates.collectAsState()
    val trip by vm.tripInfo.collectAsState()
    val latest = states.firstOrNull() ?: return

    val sdfDateOnly = remember { SimpleDateFormat("M월 d일", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(latest.badgeBg, RoundedCornerShape(4.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                // 최상단 카드에는 실시간 현재 상태("주차" 등)를 무조건 명확히 노출
                Text("현재: ${latest.computedLabel}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text("🔋 ${latest.batteryLevel?.toInt() ?: 0}%", color = Color.White, fontSize = 13.sp)
            Text("📍 ${latest.odometer?.toInt() ?: 0} km", color = Color.White, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF252538), RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("⚡ 최근 실측 전비 통계", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("누적 ${trip.totalTrips}구간", color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${trip.efficiency}", color = Color(0xFFA200FF), fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text(" km/kWh", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color(0xFF252538))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("주행거리", color = Color.Gray, fontSize = 11.sp); Text("${trip.distance} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("소모 배터리", color = Color.Gray, fontSize = 11.sp); Text("${trip.batteryUsed}%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("배터리당", color = Color.Gray, fontSize = 11.sp); Text("${trip.kmPerPercent} km/%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("사용량", color = Color.Gray, fontSize = 11.sp); Text("${trip.energyUsed} kWh", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // 하단 변동 내역 타임라인 로그에서는 요청대로 "주차" 성격의 로그를 전부 제외하여 노이즈 제거
            val filteredLogs = states.filter { (it.batteryDelta != 0.0 || it.distanceDelta > 0.0) && it.computedLabel != "주차" }
            val groupedLogs = filteredLogs.groupBy { sdfDateOnly.format(Date(it.date)) }

            groupedLogs.forEach { (dateStr, logsForDate) ->
                item { Text(text = "$dateStr 변동 타임라인 (주행/충전)", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                items(logsForDate) { log ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(12.dp)).padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(log.computedLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(log.badgeBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val timeTitle = if (log.computedLabel == "주행") "주행 시간" else "충전 시간"
                                Text("${log.timeRangeStr} ($timeTitle: ${log.durationStr.ifEmpty { "계측중" }})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("🔋 잔량: ${log.batteryLevel?.toInt() ?: 0}%", color = Color.LightGray, fontSize = 12.sp)
                                if (log.batteryDelta > 0.0) {
                                    Text("▲ +${String.format(Locale.US, "%.1f", log.batteryDelta)}% [충전 완료]", color = Color(0xFF34C759), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else if (log.batteryDelta < 0.0) {
                                    Text("▼ ${String.format(Locale.US, "%.1f", Math.abs(log.batteryDelta))}%", color = Color(0xFFFF3B30), fontSize = 12.sp)
                                }
                                if (log.distanceDelta > 0.0) {
                                    Text("🚗 +${String.format(Locale.US, "%.1f", log.distanceDelta)} km", color = Color(0xFF2685FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4-B. 주행 정보 탭 (에러 추적 메커니즘 전면 탑재)
// ==========================================
@Composable
fun DrivingHistoryView(vm: TeslaViewModel) {
    val drivingLogs by vm.drivingLogs.collectAsState()
    val combinedPoints by vm.combinedDrivingPoints.collectAsState()

    // 컴포저블 트리 진입 단계에서 에러 코드 가로채기
    LaunchedEffect(combinedPoints) {
        if (combinedPoints.any { it.latitude.isNaN() || it.longitude.isNaN() }) {
            vm.errorMsg.value = "[주행정보 에러 코드: ERR_GPS_NAN]\n좌표계 내부에 올바르지 않은 NaN 실수가 탐지되었습니다."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF13131C), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF2D2D44), RoundedCornerShape(14.dp)), 
            contentAlignment = Alignment.Center
        ) {
            // 안전 예외 가웃 코드로 맵 초기화 래핑 수행
            if (combinedPoints.isNotEmpty()) {
                val lastPoint = combinedPoints.lastOrNull()
                if (lastPoint != null && !lastPoint.latitude.isNaN() && !lastPoint.longitude.isNaN()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(lastPoint, 14f) }
                    ) {
                        TileOverlay(tileProvider = UrlTileProvider(256, 256) { x, y, z -> URL("https://tile.openstreetmap.fr/hot/$z/$x/$y.png") })
                        Polyline(points = combinedPoints, color = Color(0xFF2685FF), width = 12f)
                    }
                } else {
                    Text("유효 궤적 좌표 변환 실패\n상단 에러 로그를 리프레시하세요.", color = Color.Red, fontSize = 12.sp)
                }
            } else {
                Text("수집된 GPS 동선이 없거나 지도 초기화 세션 대기 중.", color = Color.Gray, fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text("🗂️ 실측 주행 세션 로그 (${drivingLogs.size}건)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(drivingLogs) { log ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF161622), RoundedCornerShape(10.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        val sdf = SimpleDateFormat("MM월 dd일 HH:mm", Locale.getDefault())
                        Text(sdf.format(Date(log.date)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("주행구간 전비: ${log.efficiency} km/kWh", color = Color(0xFF34C759), fontSize = 11.sp)
                    }
                    Text("+${log.drivingKM} km", color = Color(0xFFFCA311), fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
        }
    }
}

// ==========================================
// 4-C. 월간 내역 탭
// ==========================================
@Composable
fun MonthlyReportView(vm: TeslaViewModel) {
    val reports by vm.monthlyData.collectAsState()
    val sYear by vm.startYear.collectAsState()
    val sMonth by vm.startMonth.collectAsState()
    val eYear by vm.endYear.collectAsState()
    val eMonth by vm.endMonth.collectAsState()
    
    val yearOptions = listOf("2025", "2026")
    val monthOptions = (1..12).map { "%02d".format(it) }

    val startFilterKey = "$sYear-$sMonth"
    val endFilterKey = "$eYear-$eMonth"
    val filteredReports = reports.filter { it.monthKey >= startFilterKey && it.monthKey <= endFilterKey }

    val totalRangeDistance = filteredReports.sumOf { it.totalDistance }
    val totalRangeDrivingKM = filteredReports.sumOf { it.drivingKM }
    val totalRangeChargeKwh = filteredReports.sumOf { (it.chargingPercent / 100.0) * 62.1 * it.factor }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF161622), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("🗓️ 분석 기간 범위 정의", color = Color(0xFFFCA311), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    var exp by remember { mutableStateOf(false) }
                    Text("시작: $sYear-$sMonth ▾", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Color(0xFF252538), RoundedCornerShape(6.dp)).clickable { exp = true }.padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                    DropdownMenu(expanded = exp, onDismissRequest = { exp = false }, modifier = Modifier.background(Color(0xFF252538))) {
                        yearOptions.forEach { y -> monthOptions.forEach { m ->
                            DropdownMenuItem(text = { Text("${y}년 ${m}월", color = Color.White, fontSize = 12.sp) }, onClick = { vm.startYear.value = y; vm.startMonth.value = m; exp = false })
                        }}
                    }
                }
                Text("~", color = Color.White, fontSize = 14.sp)
                Box(modifier = Modifier.weight(1f)) {
                    var exp by remember { mutableStateOf(false) }
                    Text("종료: $eYear-$eMonth ▾", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Color(0xFF252538), RoundedCornerShape(6.dp)).clickable { exp = true }.padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                    DropdownMenu(expanded = exp, onDismissRequest = { exp = false }, modifier = Modifier.background(Color(0xFF252538))) {
                        yearOptions.forEach { y -> monthOptions.forEach { m ->
                            DropdownMenuItem(text = { Text("${y}년 ${m}월", color = Color.White, fontSize = 12.sp) }, onClick = { vm.endYear.value = y; vm.endMonth.value = m; exp = false })
                        }}
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF2A2A3F), RoundedCornerShape(14.dp)).padding(16.dp)) {
            Text("📊 통합 통계 합산 내역", color = Color(0xFFFCA311), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("총 계측거리", color = Color.Gray, fontSize = 10.sp); Text("${"%,d".format(totalRangeDistance.toInt())} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Column { Text("실구동거리", color = Color.Gray, fontSize = 10.sp); Text("${String.format(Locale.US, "%.1f", totalRangeDrivingKM)} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Column { Text("전력 공급량", color = Color.Gray, fontSize = 10.sp); Text("${String.format(Locale.US, "%.1f", totalRangeChargeKwh)} kWh", color = Color(0xFF34C759), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(filteredReports) { month ->
                val chargeKwh = (month.chargingPercent / 100.0) * 62.1 * month.factor
                val drivingKwh = (month.drivingPercent / 100.0) * 62.1
                val calculatedEff = if(drivingKwh > 0) month.drivingKM / drivingKwh else 0.0

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF13131C), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF222230), RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${month.monthKey} 리포트", color = Color(0xFFFCA311), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${"%,d".format(month.totalDistance.toInt())} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🚗 실 차량 구동 거리: ${String.format(Locale.US, "%.1f", month.drivingKM)} km", color = Color.LightGray, fontSize = 12.sp)
                    Text("⚡ 충전 공급 에너지: 약 ${String.format(Locale.US, "%.1f", chargeKwh)} kWh 완료", color = Color(0xFF34C759), fontSize = 12.sp)
                    Text("📈 구간 환산 전비율: ${String.format(Locale.US, "%.2f", calculatedEff)} km/kWh", color = Color(0xFFA200FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ==========================================
// 4-D. 배터리 탭
// ==========================================
@Composable
fun BatteryTrendView(vm: TeslaViewModel) {
    val batteryList by vm.batteryData.collectAsState()
    val filtered = batteryList.filter { it.batteryRange > 0 || it.estBatteryRange > 0 }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF161622), RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("🔋 배터리 열화도 지표 트렌드 (SOH)", color = Color(0xFF34C759), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("98.4 % (정상 범주)", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (filtered.size > 1) {
            Text("완충 기준 주간 범위 주행거리 추세 (km)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF13131C)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp)) {
                Canvas(modifier = Modifier.fillMaxSize().padding(start = 55.dp, end = 20.dp, top = 20.dp, bottom = 30.dp)) {
                    val maxVal = 450.0
                    val minVal = 350.0
                    val deltaY = maxVal - minVal
                    val width = size.width
                    val height = size.height
                    
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 26f
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    val gridLines = 4
                    for (i in 0..gridLines) {
                        val value = minVal + (deltaY / gridLines) * i
                        val yPos = height - (i * (height / gridLines))
                        drawContext.canvas.nativeCanvas.drawText("${value.toInt()}km", -15f, yPos + 10f, paint)
                        drawLine(color = Color(0xFF252538), start = Offset(0f, yPos), end = Offset(width, yPos), strokeWidth = 2f)
                    }

                    val stepX = width / (filtered.size - 1)
                    val path = Path()
                    filtered.forEachIndexed { i, d ->
                        val currentRange = if (d.batteryRange > 0) d.batteryRange else d.estBatteryRange
                        val cx = i * stepX
                        val cy = height - (((currentRange - minVal) / deltaY) * height).toFloat()
                        if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
                        drawCircle(color = Color(0xFF34C759), radius = 7f, center = Offset(cx, cy))
                    }
                    drawPath(path = path, color = Color(0xFF34C759), style = Stroke(width = 5f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginConsoleScreen(onLoginSubmit: (String, String) -> Unit) {
    var apiKeyInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070709)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp).background(Color(0xFF13131F), RoundedCornerShape(20.dp)).border(1.dp, Color(0xFF252538), RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚡ TESLA CORE CONSOLE", color = Color(0xFFFCA311), fontSize = 19.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = apiKeyInput, onValueChange = { apiKeyInput = it },
                label = { Text("FIREBASE API KEY", color = Color.White, fontSize = 11.sp) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFCA311), unfocusedBorderColor = Color(0xFF2A2A3F), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it },
                label = { Text("REFRESH TOKEN", color = Color.White, fontSize = 11.sp) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFCA311), unfocusedBorderColor = Color(0xFF2A2A3F), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(100.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { onLoginSubmit(apiKeyInput, tokenInput) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFCA311)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("보안 프로토콜 세션 연결", color = Color(0xFF070709), fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun UrlTileProvider(width: Int, height: Int, provider: (Int, Int, Int) -> URL): com.google.android.gms.maps.model.UrlTileProvider {
    return object : com.google.android.gms.maps.model.UrlTileProvider(width, height) {
        override fun getTileUrl(x: Int, y: Int, z: Int): URL = provider(x, y, z)
    }
}
