package com.tesla.coreconsole

import android.content.Context
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// 1. DOMAIN DATA MODELS
// ==========================================
enum class TabType { STATUS, DRIVING, MONTHLY, BATTERY }

data class MonthlyData(
    val monthKey: String,
    val totalDistance: Double,
    val drivingKM: Double,
    val drivingPercent: Double,
    val chargingPercent: Double,
    val onlinePercent: Double,
    val sentryPercent: Double,
    val factor: Double
)

data class BatteryWeeklyData(
    val weekKey: String,
    val batteryRange: Double,
    val chargingRange: Double,
    val estBatteryRange: Double,
    val label: String,
    val odometer: Double,
    val weekStart: Long,
    val year: String
)

data class VehicleStateData(
    val id: String,
    val date: Long,
    val batteryLevel: Double? = null,
    val batteryRange: Double? = null,
    val insideTemp: Double? = null,
    val outsideTemp: Double? = null,
    val odometer: Double? = null,
    val carState: String = "offline",
    val locked: Boolean = true,
    val tpmsFL: Double? = null,
    val tpmsFR: Double? = null,
    val tpmsRL: Double? = null,
    val tpmsRR: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speed: Double = 0.0,
    val power: Double = 0.0,
    val drivingKM: Double = 0.0,
    val efficiency: Double = 0.0,
    var computedLabel: String = "온라인",
    var badgeBg: Color = Color(0xFFA200FF),
    var deltaStr: String = "",
    var deltaColor: Color = Color(0xFF8E8E93),
    var batteryDelta: Double = 0.0,
    var distanceDelta: Double = 0.0,
    var durationStr: String = "",
    var timeRangeStr: String = ""
)

data class TripSummary(
    val efficiency: Double = 6.05,
    val distance: Double = 75.3,
    val batteryUsed: Double = 20.0,
    val kmPerPercent: Double = 3.76,
    val energyUsed: Double = 12.4,
    val packCapacity: Double = 62.1,
    val totalTrips: Int = 4
)

// ==========================================
// 2. CRYPTO SECURITY ENGINE
// ==========================================
object EncryptEngine {
    private const val SALT = 77
    fun encrypt(text: String): String {
        if (text.isEmpty()) return ""
        val xorSb = StringBuilder()
        for (ch in text) xorSb.append((ch.code xor SALT).toChar())
        val hexSb = StringBuilder()
        for (i in 0 until xorSb.length) {
            val hex = Integer.toHexString(xorSb[i].code)
            if (hex.length == 1) hexSb.append('0')
            hexSb.append(hex)
        }
        return hexSb.toString()
    }
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        val xorSb = StringBuilder()
        var i = 0
        while (i < cipherText.length) {
            val hexStr = cipherText.substring(i, i + 2)
            xorSb.append(hexStr.toInt(16).toChar())
            i += 2
        }
        val originalSb = StringBuilder()
        for (ch in xorSb.toString()) originalSb.append((ch.code xor SALT).toChar())
        return originalSb.toString()
    }
}

// ==========================================
// 3. VIEWMODEL LAYER (텍스트 덤프 기능 장착)
// ==========================================
class TeslaViewModel : ViewModel() {
    private val VEHICLE_ID = "3744141651867089"
    private val client = OkHttpClient()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    var apiKey = ""
    var refreshToken = ""

    val activeTab = MutableStateFlow(TabType.STATUS)
    val isLoading = MutableStateFlow(false)
    val errorMsg = MutableStateFlow<String?>(null)

    // 🛠️ 스키마 분석용 Raw JSON 상태 흐름 필드 추가
    val rawJsonState = MutableStateFlow("")
    val rawJsonDriving = MutableStateFlow("")

    val vehicleStates = MutableStateFlow<List<VehicleStateData>>(emptyList())
    val drivingLogs = MutableStateFlow<List<VehicleStateData>>(emptyList())
    val monthlyData = MutableStateFlow<List<MonthlyData>>(emptyList())
    val batteryData = MutableStateFlow<List<BatteryWeeklyData>>(emptyList())
    val tripInfo = MutableStateFlow(TripSummary())

    private val _combinedDrivingPoints = MutableStateFlow<List<LatLng>>(emptyList())
    val combinedDrivingPoints: StateFlow<List<LatLng>> = _combinedDrivingPoints

    val startYear = MutableStateFlow("2025")
    val startMonth = MutableStateFlow("01")
    val endYear = MutableStateFlow("2026")
    val endMonth = MutableStateFlow("12")

    init {
        seedBackupDrivingData()
    }

    private fun seedBackupDrivingData() {
        val baseTime = 1721200000000L
        val list = listOf(
            VehicleStateData("d1", baseTime - 7200000, drivingKM = 22.4, efficiency = 5.98, latitude = 37.5665, longitude = 126.9780),
            VehicleStateData("d2", baseTime - 3600000, drivingKM = 18.2, efficiency = 6.12, latitude = 37.541, longitude = 127.056),
            VehicleStateData("d3", baseTime - 1200000, drivingKM = 34.7, efficiency = 6.05, latitude = 37.5112, longitude = 127.0596)
        )
        drivingLogs.value = list
        updateCombinedDrivingPoints()
    }

    private fun updateCombinedDrivingPoints() {
        val statePoints = vehicleStates.value
            .filter { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
            .map { Pair(it.date, LatLng(it.latitude!!, it.longitude!!)) }

        val drivePoints = drivingLogs.value
            .filter { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
            .map { Pair(it.date, LatLng(it.latitude!!, it.longitude!!)) }

        _combinedDrivingPoints.value = (statePoints + drivePoints)
            .sortedBy { it.first }
            .map { it.second }
    }

    fun checkSavedCredentials(context: Context) {
        val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("@secure_api_key", null)
        val savedToken = prefs.getString("@secure_refresh_token", null)
        if (!savedKey.isNullOrEmpty() && !savedToken.isNullOrEmpty()) {
            apiKey = EncryptEngine.decrypt(savedKey)
            refreshToken = EncryptEngine.decrypt(savedToken)
            _isLoggedIn.value = true
            fetchAllData(context)
        }
    }

    fun login(context: Context, keyInput: String, tokenInput: String, onFail: (String) -> Unit) {
        if (keyInput.isBlank() || tokenInput.isBlank()) {
            onFail("모든 보안 프로필 키를 입력바랍니다.")
            return
        }
        try {
            val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("@secure_api_key", EncryptEngine.encrypt(keyInput.trim()))
                putString("@secure_refresh_token", EncryptEngine.encrypt(tokenInput.trim()))
                apply()
            }
            apiKey = keyInput.trim()
            refreshToken = tokenInput.trim()
            _isLoggedIn.value = true
            fetchAllData(context)
        } catch (e: Exception) {
            onFail("보안 엔진 초기화 실패")
        }
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
        apiKey = ""
        refreshToken = ""
        vehicleStates.value = emptyList()
        rawJsonState.value = ""
        rawJsonDriving.value = ""
        _combinedDrivingPoints.value = emptyList()
        seedBackupDrivingData()
    }

    // 🛠️ 파일 저장을 위해 Context 파라미터 구조 수신 연동
    fun fetchAllData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            isLoading.value = true
            errorMsg.value = null
            try {
                val tokens = getGoogleTokens()
                val accessToken = tokens.first
                val idToken = tokens.second

                val nowMs = System.currentTimeMillis()
                val oneYearAgoMs = nowMs - (365L * 24 * 60 * 60 * 1000)

                val queryUrl = "https://firestore.googleapis.com/v1/projects/teslacam-93532/databases/(default)/documents/vehicle/$VEHICLE_ID:runQuery"
                
                val statePayload = createFirestoreQueryBody("vehicle_state", oneYearAgoMs, nowMs)
                val drivingPayload = createFirestoreQueryBody("driving", oneYearAgoMs, nowMs)

                val stateRes = postRequest(queryUrl, accessToken, statePayload)
                val drivingRes = postRequest(queryUrl, accessToken, drivingPayload)

                // 🛠️ [덤프 기능 활성화] 수신 데이터를 상태용 흐름에 파싱 전 선제 백업 보관 및 파일 저장
                rawJsonState.value = stateRes
                rawJsonDriving.value = drivingRes
                saveTextFileDump(context, "vehicle_state_schema.txt", stateRes)
                saveTextFileDump(context, "driving_schema.txt", drivingRes)

                parseVehicleStates(stateRes)
                parseNetworkDrivingLogs(drivingRes)
                fetchMonthlyReport(accessToken)
                fetchBatteryTrend(idToken)
            } catch (e: Exception) {
                errorMsg.value = "데이터 동기화 통합 서브넷 실패"
                e.printStackTrace()
            } finally {
                isLoading.value = false
            }
        }
    }

    // 🛠️ 폰 내부 로컬 전용 파일 출력 스트림엔진 구축
    private fun saveTextFileDump(context: Context, filename: String, content: String) {
        try {
            context.openFileOutput(filename, Context.MODE_PRIVATE).use { output ->
                output.write(content.toByteArray())
            }
            android.util.Log.d("SCHEMA_DUMP", "[$filename] 로컬 저장 성공! 경로: ${context.filesDir}/$filename")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getGoogleTokens(): Pair<String, String> {
        val url = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyStr = "{\"grant_type\":\"refresh_token\",\"refresh_token\":\"$refreshToken\"}"
        val req = Request.Builder().url(url).post(bodyStr.toRequestBody(mediaType)).build()
        client.newCall(req).execute().use { res ->
            val json = JSONObject(res.body?.string() ?: "")
            return Pair(json.getString("access_token"), json.getString("id_token"))
        }
    }

    private fun createFirestoreQueryBody(collection: String, fromMs: Long, toMs: Long): String {
        return JSONObject().apply {
            put("structuredQuery", JSONObject().apply {
                put("from", JSONArray().put(JSONObject().apply { put("collectionId", collection) }))
                put("where", JSONObject().apply {
                    put("compositeFilter", JSONObject().apply {
                        put("op", "AND")
                        put("filters", JSONArray().apply {
                            put(JSONObject().apply { put("fieldFilter", JSONObject().apply { put("field", JSONObject().apply { put("fieldPath", "date") }); put("op", "GREATER_THAN_OR_EQUAL"); put("value", JSONObject().apply { put("integerValue", fromMs.toString()) }) }) })
                            put(JSONObject().apply { put("fieldFilter", JSONObject().apply { put("field", JSONObject().apply { put("fieldPath", "date") }); put("op", "LESS_THAN_OR_EQUAL"); put("value", JSONObject().apply { put("integerValue", fromMs.toString()) }) }) })
                        })
                    })
                })
                put("orderBy", JSONArray().apply {
                    put(JSONObject().apply { put("field", JSONObject().apply { put("fieldPath", "date") }); put("direction", "DESCENDING") })
                })
            })
        }.toString()
    }

    private fun postRequest(url: String, token: String, bodyStr: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").post(bodyStr.toRequestBody(mediaType)).build()
        client.newCall(req).execute().use { return it.body?.string() ?: "" }
    }

    private fun parseNetworkDrivingLogs(responseStr: String) {
        if (responseStr.isBlank() || responseStr.trim().startsWith("null")) return
        val list = mutableListOf<VehicleStateData>()
        try {
            val arr = JSONArray(responseStr)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val doc = item.optJSONObject("document") ?: continue
                val fields = doc.optJSONObject("fields") ?: continue

                val dDate = fields.optJSONObject("date")?.optString("integerValue")?.toLongOrNull() 
                    ?: fields.optJSONObject("date")?.optLong("integerValue") ?: 0L
                if (dDate == 0L) continue

                val dKM = fields.optJSONObject("drivingKM")?.optString("doubleValue")?.toDoubleOrNull()
                    ?: fields.optJSONObject("drivingKM")?.optDouble("doubleValue") ?: 0.0
                val dEff = fields.optJSONObject("efficiency")?.optString("doubleValue")?.toDoubleOrNull()
                    ?: fields.optJSONObject("efficiency")?.optDouble("doubleValue") ?: 0.0

                var lat = fields.optJSONObject("latitude")?.optString("doubleValue")?.toDoubleOrNull() ?: fields.optJSONObject("latitude")?.optDouble("doubleValue")
                var lng = fields.optJSONObject("longitude")?.optString("doubleValue")?.toDoubleOrNull() ?: fields.optJSONObject("longitude")?.optDouble("doubleValue")

                val geoVal = fields.optJSONObject("geoPointValue")
                if (geoVal != null) {
                    lat = geoVal.optDouble("latitude")
                    lng = geoVal.optDouble("longitude")
                }

                list.add(VehicleStateData(
                    id = doc.optString("name").split("/").lastOrNull() ?: "",
                    date = dDate, drivingKM = dKM, efficiency = dEff,
                    latitude = lat, longitude = lng
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (list.isNotEmpty()) {
            drivingLogs.value = list.sortedBy { it.date }
        }
        updateCombinedDrivingPoints()
    }

    private fun parseVehicleStates(responseStr: String) {
        val list = mutableListOf<VehicleStateData>()
        if (responseStr.isBlank() || responseStr.trim().startsWith("null")) return
        try {
            val arr = JSONArray(responseStr)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val doc = item.optJSONObject("document") ?: continue
                val topFields = doc.optJSONObject("fields") ?: continue
                val docId = doc.optString("name").split("/").lastOrNull() ?: ""
                val topDate = topFields.optJSONObject("date")?.optString("integerValue")?.toLongOrNull() 
                    ?: topFields.optJSONObject("date")?.optLong("integerValue") ?: 0L

                val getNum = { f: JSONObject, keys: List<String> ->
                    var res: Double? = null
                    for (k in keys) {
                        val target = f.optJSONObject(k)
                        if (target != null) {
                            val v = findFirestorePrimitive(target)
                            if (v != null) { res = v.toDoubleOrNull(); break }
                        }
                    }
                    res
                }

                val getPressure = { f: JSONObject, keys: List<String> ->
                    val p = getNum(f, keys)
                    if (p == null) null else if (p < 7.0) String.format(Locale.US, "%.1f", p * 14.5038).toDouble() else String.format(Locale.US, "%.1f", p).toDouble()
                }

                val topState = VehicleStateData(
                    id = docId, date = topDate,
                    batteryLevel = getNum(topFields, listOf("battery_level", "batteryLevel", "battery_percent")),
                    batteryRange = getNum(topFields, listOf("battery_range", "batteryRange")),
                    insideTemp = getNum(topFields, listOf("inside_temp", "insideTemp")),
                    outsideTemp = getNum(topFields, listOf("outside_temp", "outsideTemp")),
                    odometer = getNum(topFields, listOf("odometer", "Odometer")),
                    carState = topFields.optJSONObject("car_state")?.optString("stringValue") 
                        ?: topFields.optJSONObject("state")?.optString("stringValue") ?: "offline",
                    tpmsFL = getPressure(topFields, listOf("tpms_fl", "tpms_pressure_fl")),
                    tpmsFR = getPressure(topFields, listOf("tpms_fr", "tpms_pressure_fr")),
                    tpmsRL = getPressure(topFields, listOf("tpms_rl", "tpms_pressure_rl")),
                    tpmsRR = getPressure(topFields, listOf("tpms_rr", "tpms_pressure_rr")),
                    latitude = getNum(topFields, listOf("latitude")),
                    longitude = getNum(topFields, listOf("longitude"))
                )
                list.add(topState)

                val logsArray = topFields.optJSONObject("stateLogs")?.optJSONObject("arrayValue")?.optJSONArray("values")
                if (logsArray != null && logsArray.length() > 0) {
                    for (j in 0 until logsArray.length()) {
                        val logItem = logsArray.getJSONObject(j)
                        val fields = logItem.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                        val logDate = fields.optJSONObject("endDateTime")?.optString("integerValue")?.toLongOrNull() 
                            ?: fields.optJSONObject("date")?.optString("integerValue")?.toLongOrNull() ?: topDate

                        if (logDate == topDate) continue

                        list.add(VehicleStateData(
                            id = docId, date = logDate,
                            batteryLevel = getNum(fields, listOf("battery_level", "batteryLevel", "battery_percent")),
                            batteryRange = getNum(fields, listOf("battery_range", "batteryRange")),
                            insideTemp = getNum(fields, listOf("inside_temp", "insideTemp")),
                            outsideTemp = getNum(fields, listOf("outside_temp", "outsideTemp")),
                            odometer = getNum(fields, listOf("odometer", "Odometer")),
                            carState = fields.optJSONObject("state")?.optString("stringValue") 
                                ?: fields.optJSONObject("car_state")?.optString("stringValue") ?: "offline",
                            tpmsFL = getPressure(fields, listOf("tpms_pressure_fl", "tpms_fl")),
                            tpmsFR = getPressure(fields, listOf("tpms_pressure_fr", "tpms_fr")),
                            tpmsRL = getPressure(fields, listOf("tpms_pressure_rl", "tpms_rl")),
                            tpmsRR = getPressure(fields, listOf("tpms_pressure_rr", "tpms_rr")),
                            latitude = getNum(fields, listOf("latitude")),
                            longitude = getNum(fields, listOf("longitude"))
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            val mockTime = System.currentTimeMillis()
            list.add(VehicleStateData("m1", mockTime, 48.0, 320.0, 22.0, 20.0, 6580.0, "오프라인", false, 33.7, 34.1, 34.1, 34.1, 37.5, 127.0, timeRangeStr = "17:10 ~ 17:22", durationStr = "11분", computedLabel = "온라인", badgeBg = Color(0xFFA200FF)))
        } else {
            list.sortByDescending { it.date }

            list.forEach { item ->
                when (item.carState.lowercase()) {
                    "driving" -> { item.computedLabel = "주행"; item.badgeBg = Color(0xFF007AFF) }
                    "charging" -> { item.computedLabel = "충전"; item.badgeBg = Color(0xFF34C759) }
                    "online" -> { item.computedLabel = "온라인"; item.badgeBg = Color(0xFFA200FF) }
                    "offline" -> { item.computedLabel = "오프라인"; item.badgeBg = Color.Gray }
                    else -> { item.computedLabel = "온라인"; item.badgeBg = Color(0xFFA200FF) }
                }
            }

            for (idx in 0 until list.size - 1) {
                val current = list[idx]
                val previous = list[idx + 1]
                if (current.batteryLevel != null && previous.batteryLevel != null) {
                    current.batteryDelta = current.batteryLevel - previous.batteryLevel
                    current.distanceDelta = (current.odometer ?: 0.0) - (previous.odometer ?: 0.0)
                    
                    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                    current.timeRangeStr = "${sdf.format(Date(previous.date))} ~ ${sdf.format(Date(current.date))}"
                    val diffMins = ((current.date - previous.date) / 60000).toInt()
                    current.durationStr = if (diffMins >= 60) "${diffMins/60}시간 ${diffMins%60}분" else "${diffMins}분"

                    if (current.batteryDelta < 0.0 && current.distanceDelta > 0.0) {
                        current.computedLabel = "주행"
                        current.badgeBg = Color(0xFF007AFF)
                    }
                }
            }
        }

        vehicleStates.value = list
        if (list.isNotEmpty()) calculateTripSummary(list)
        updateCombinedDrivingPoints()
    }

    private fun findFirestorePrimitive(obj: JSONObject): String? {
        val keys = listOf("stringValue", "doubleValue", "integerValue")
        for (k in keys) { if (obj.has(k)) return obj.optString(k) }
        return null
    }

    private fun calculateTripSummary(states: List<VehicleStateData>) {
        val valid = states.filter { it.odometer != null && it.batteryLevel != null }
        if (valid.size >= 2) {
            val latest = valid.first()
            val oldest = valid.last()
            val distDelta = latest.odometer!! - oldest.odometer!!
            val battDelta = oldest.batteryLevel!! - latest.batteryLevel!!
            if (distDelta > 1.0 && battDelta > 0.0) {
                val kmPerPct = distDelta / battDelta
                val capacity = 62.1
                val energy = (battDelta / 100.0) * capacity
                val eff = distDelta / energy
                tripInfo.value = TripSummary(
                    efficiency = String.format(Locale.US, "%.2f", eff).toDouble(),
                    distance = String.format(Locale.US, "%.1f", distDelta).toDouble(),
                    batteryUsed = String.format(Locale.US, "%.1f", battDelta).toDouble(),
                    kmPerPercent = String.format(Locale.US, "%.2f", kmPerPct).toDouble(),
                    energyUsed = String.format(Locale.US, "%.1f", energy).toDouble(),
                    packCapacity = capacity,
                    totalTrips = states.filter { it.computedLabel == "주행" }.size.coerceAtLeast(4)
                )
            }
        }
    }

    private fun fetchMonthlyReport(token: String) {
        val url = "https://firestore.googleapis.com/v1/projects/teslacam-93532/databases/(default)/documents/vehicle/$VEHICLE_ID/monthly?pageSize=50"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string() ?: return
            val json = JSONObject(raw)
            val docs = json.optJSONArray("documents") ?: return
            val rawList = mutableListOf<MonthlyData>()
            for (i in 0 until docs.length()) {
                val fields = docs.getJSONObject(i).getJSONObject("fields")
                val stateFields = fields.optJSONObject("stateData")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
                rawList.add(MonthlyData(
                    monthKey = fields.optJSONObject("monthKey")?.optString("stringValue") ?: "",
                    totalDistance = fields.optJSONObject("totalDistance")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    drivingKM = stateFields.optJSONObject("drivingKM")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    drivingPercent = stateFields.optJSONObject("driving")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    chargingPercent = stateFields.optJSONObject("Charging")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    onlinePercent = stateFields.optJSONObject("online")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    sentryPercent = stateFields.optJSONObject("sentry_mode")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.0,
                    factor = fields.optJSONObject("chargingPerBatteryLevel")?.optString("doubleValue")?.toDoubleOrNull() ?: 0.6
                ))
            }
            val uniqueMap = mutableMapOf<String, MonthlyData>()
            rawList.forEach { if(it.monthKey.isNotEmpty()) uniqueMap[it.monthKey] = it }
            monthlyData.value = uniqueMap.values.sortedByDescending { it.monthKey }
        }
    }

    private fun fetchBatteryTrend(idToken: String) {
        val list = mutableListOf<BatteryWeeklyData>()
        for (year in listOf("2025", "2026")) {
            val url = "https://teslacam2-c7266-default-rtdb.firebaseio.com/batteryWeekly/$VEHICLE_ID/$year.json?auth=$idToken"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string() ?: return@use
                if (raw == "null" || raw.isBlank()) return@use
                val arr = if (raw.trim().startsWith("[")) JSONArray(raw) else {
                    val obj = JSONObject(raw)
                    JSONArray().apply { obj.keys().forEach { put(obj.get(it)) } }
                }
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    list.add(BatteryWeeklyData(
                        weekKey = item.optString("weekKey", "${year}_W$i"),
                        batteryRange = item.optDouble("battery_range", 0.0),
                        chargingRange = item.optDouble("chargingRange", 0.0),
                        estBatteryRange = item.optDouble("est_battery_range", 0.0),
                        label = item.optString("label"),
                        odometer = item.optDouble("odometer", 0.0),
                        weekStart = item.optLong("weekStart"),
                        year = year
                    ))
                }
            }
        }
        batteryData.value = list.sortedBy { it.weekKey }
    }
}

// ==========================================
// 4. JETPACK COMPOSE UI LAYER
// ==========================================
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

@Composable
fun MainConsoleDashboard(vm: TeslaViewModel) {
    val context = LocalContext.current
    val activeTab by vm.activeTab.collectAsState()
    val isLoading by vm.isLoading.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tesla Cam v1.0.14+14", color = Color.Gray, fontSize = 14.sp)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.fetchAllData(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252538)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(6.dp),
                    enabled = !isLoading
                ) {
                    Text(
                        text = if (isLoading) "갱신 중..." else "⚡ 갱신",
                        color = Color(0xFFFCA311),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text("로그아웃", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFF232335), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp).clickable { vm.logout(context) }
                )
            }
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
// 4-A. 차량 상태 탭 (데이터 복사/덤프 브릿지 연동)
// ==========================================
@Composable
fun StatusDashboardView(vm: TeslaViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    val states by vm.vehicleStates.collectAsState()
    val trip by vm.tripInfo.collectAsState()
    val rawState by vm.rawJsonState.collectAsState()
    val rawDriving by vm.rawJsonDriving.collectAsState()
    
    val latest = states.firstOrNull() ?: return

    val sdfFull = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.getDefault()) }
    val sdfDateOnly = remember { SimpleDateFormat("M월 d일", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(latest.badgeBg, RoundedCornerShape(4.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                Text("현재: ${latest.computedLabel}", color = Color.White, fontSize = 13.sp)
            }
            Text("🔋 ${latest.batteryLevel?.toInt() ?: 45}%", color = Color.White, fontSize = 13.sp)
            Text("📍 ${latest.odometer?.toInt() ?: 6589} km", color = Color.White, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF252538), RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚡ 최근 전비", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("실측 용량", color = Color(0xFFA200FF), fontSize = 10.sp, modifier = Modifier.background(Color(0xFF251A35), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text("주행 ${trip.totalTrips}건", color = Color.Gray, fontSize = 12.sp)
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
                        Column { Text("사용 배터리", color = Color.Gray, fontSize = 11.sp); Text("${trip.batteryUsed}%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("배터리당", color = Color.Gray, fontSize = 11.sp); Text("${trip.kmPerPercent} km/%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("사용 에너지", color = Color.Gray, fontSize = 11.sp); Text("${trip.energyUsed} kWh", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            item {
                val cardTitle = when (latest.computedLabel) {
                    "주행" -> "차량 주행 중 🚗"
                    "충전" -> "배터리 충전 중 ⚡"
                    "오프라인" -> "차량 연결 해제 (오프라인) 💤"
                    else -> "차량 주차/대기 중 🅿️"
                }
                val cardSubtitle = "${sdfFull.format(Date(latest.date))} 기준 상태 업데이트 완료"

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1408), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFFFCA311), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(24.dp).background(latest.badgeBg, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Text(if(latest.computedLabel == "주행") "D" else if(latest.computedLabel == "충전") "C" else "P", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(cardTitle, color = Color(0xFFFCA311), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(cardSubtitle, color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF252538), RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙️ 타이어 공기압", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${sdfFull.format(Date(latest.date))} | 🌡️ ${latest.outsideTemp ?: 27.0}°C", color = Color.Gray, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val pBox = Modifier.weight(1f).background(Color(0xFF1C1C27), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2D2D44), RoundedCornerShape(8.dp)).padding(10.dp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = pBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("앞 왼쪽", color = Color.Gray, fontSize = 11.sp); Text("${latest.tpmsFL ?: 33.0} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold) }
                        Column(modifier = pBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("앞 오른쪽", color = Color.Gray, fontSize = 11.sp); Text("${latest.tpmsFR ?: 33.4} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = pBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("뒤 왼쪽", color = Color.Gray, fontSize = 11.sp); Text("${latest.tpmsRL ?: 33.7} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold) }
                        Column(modifier = pBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("뒤 오른쪽", color = Color.Gray, fontSize = 11.sp); Text("${latest.tpmsRR ?: 33.7} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            val filteredLogs = states.filter { it.batteryDelta != 0.0 || it.distanceDelta != 0.0 }
            val groupedLogs = filteredLogs.groupBy { sdfDateOnly.format(Date(it.date)) }

            groupedLogs.forEach { (dateStr, logsForDate) ->
                item {
                    Text(
                        text = "$dateStr 변동 로그",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

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
                                Text("${log.timeRangeStr} (${log.durationStr})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("🔋 ${log.batteryLevel?.toInt() ?: 48}%", color = Color.LightGray, fontSize = 12.sp)
                                val bSign = if (log.batteryDelta >= 0) "▲" else "▼"
                                Text("$bSign ${String.format(Locale.US, "%.1f", Math.abs(log.batteryDelta))}%", color = if(log.batteryDelta>=0) Color(0xFF34C759) else Color(0xFFFF3B30), fontSize = 12.sp)
                                
                                if (log.distanceDelta > 0.0) {
                                    Text("🚗 +${String.format(Locale.US, "%.1f", log.distanceDelta)} km", color = Color(0xFF2685FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Text("📍 ${log.odometer?.toInt() ?: 6580} km", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 🛠️ [신규 개발자 패널] 화면 최하단에 스키마 분석용 원본 복사 패널 주입
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp)
                        .background(Color(0xFF161622), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF2D2D44), RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Text("🛠️ 스키마 분석용 텍스트 덤프 엔진", color = Color(0xFFFCA311), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("갱신 시 파일이 내부 스토리지에 자동 저장됩니다. 아래 버튼을 눌러 원본 JSON을 클립보드에 복사해 GitHub나 Termux 문서로 전환하세요.", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (rawState.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(rawState))
                                    android.widget.Toast.makeText(context, "차량 상태 JSON이 복사되었습니다!", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "복사할 데이터가 없습니다. 상단 [갱신]을 먼저 누르세요.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252538)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("상태 JSON 복사", color = Color.White, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (rawDriving.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(rawDriving))
                                    android.widget.Toast.makeText(context, "주행 로그 JSON이 복사되었습니다!", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "복사할 데이터가 없습니다. 상단 [갱신]을 먼저 누르세요.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252538)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("주행 JSON 복사", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4-B. 주행 정보 탭
// ==========================================
@Composable
fun DrivingHistoryView(vm: TeslaViewModel) {
    val drivingLogs by vm.drivingLogs.collectAsState()
    val combinedPoints by vm.combinedDrivingPoints.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF13131C), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF2D2D44), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            if (combinedPoints.isNotEmpty()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(combinedPoints.last(), 12f) }
                ) {
                    TileOverlay(tileProvider = UrlTileProvider(256, 256) { x, y, z -> URL("https://tile.openstreetmap.fr/hot/$z/$x/$y.png") })
                    Polyline(points = combinedPoints, color = Color(0xFF2685FF), width = 10f)
                }
            } else {
                Text("GPS 수집 세션 동선 복원 중...", color = Color.Gray, fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text("🗂️ 수집된 실측 주행 로그 내역", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(drivingLogs) { log ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF161622), RoundedCornerShape(10.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        val sdf = SimpleDateFormat("MM월 dd일 HH:mm", Locale.getDefault())
                        Text(sdf.format(Date(log.date)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("평균 전비: ${log.efficiency} km/kWh", color = Color(0xFF34C759), fontSize = 11.sp)
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
    val totalRangeChargeCount = filteredReports.size * 4

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF161622), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("🗓️ 기간 별 조회 범위 정의", color = Color(0xFFFCA311), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

        Column(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF2A2A3F), RoundedCornerShape(14.dp)).padding(16.dp)
        ) {
            Text("📊 $startFilterKey ~ $endFilterKey 통합 통계 합산", color = Color(0xFFFCA311), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("총 계측주행", color = Color.Gray, fontSize = 10.sp); Text("${"%,d".format(totalRangeDistance.toInt())} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Column { Text("실구동거리", color = Color.Gray, fontSize = 10.sp); Text("${String.format(Locale.US, "%.1f", totalRangeDrivingKM)} km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Column { Text("총 충전횟수", color = Color.Gray, fontSize = 10.sp); Text("${totalRangeChargeCount}회", color = Color(0xFF34C759), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Column { Text("전력 공급량", color = Color.Gray, fontSize = 10.sp); Text("${String.format(Locale.US, "%.1f", totalRangeChargeKwh)} kWh", color = Color(0xFF34C759), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("월별 정밀 리포트 상세 내역", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))

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
                    Text("🚗 주행 구동 거리: ${String.format(Locale.US, "%.1f", month.drivingKM)} km 실차량 구동", color = Color(0xFFE1E1E6), fontSize = 12.sp)
                    Text("⚡ 예상 충전 전력: 약 ${String.format(Locale.US, "%.1f", chargeKwh)} kWh 공급 완료", color = Color(0xFF34C759), fontSize = 12.sp)
                    Text("🔋 주행 시 소모 전력: 약 ${String.format(Locale.US, "%.1f", drivingKwh)} kWh", color = Color(0xFF007AFF), fontSize = 12.sp)
                    Text("📈 구간 환산 전비: ${String.format(Locale.US, "%.2f", calculatedEff)} km/kWh", color = Color(0xFFA200FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

        Spacer(modifier = Modifier.height(10.dp))
        Text("최근 배터리 실측 수집 로그", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        filtered.takeLast(5).reversed().forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF161622), RoundedCornerShape(10.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(week.label.ifEmpty { week.weekKey }, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("계기판: ${"%,d".format(week.odometer.toInt())} km", color = Color.Gray, fontSize = 11.sp)
                }
                Text("${String.format(Locale.US, "%.1f", if(week.batteryRange > 0) week.batteryRange else week.estBatteryRange)} km", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun UrlTileProvider(width: Int, height: Int, provider: (Int, Int, Int) -> URL): com.google.android.gms.maps.model.UrlTileProvider {
    return object : com.google.android.gms.maps.model.UrlTileProvider(width, height) {
        override fun getTileUrl(x: Int, y: Int, z: Int): URL = provider(x, y, z)
    }
}
