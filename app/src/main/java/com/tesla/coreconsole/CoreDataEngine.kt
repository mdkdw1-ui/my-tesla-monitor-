package com.tesla.coreconsole

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineExceptionHandler
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
    var computedLabel: String = "주차",
    var badgeBg: Color = Color(0xFFA200FF),
    var deltaStr: String = "",
    var deltaColor: Color = Color(0xFF8E8E93),
    var batteryDelta: Double = 0.0,
    var distanceDelta: Double = 0.0,
    var durationStr: String = "",
    var timeRangeStr: String = "",
    val pathPoints: List<LatLng> = emptyList()
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
// 3. VIEWMODEL LAYER (DATA & NETWORK ENGINE)
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

    fun updateCombinedDrivingPoints() {
        try {
            val statePoints = vehicleStates.value
                .filter { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 && !it.latitude.isNaN() && !it.longitude.isNaN() }
                .map { Pair(it.date, LatLng(it.latitude!!, it.longitude!!)) }

            val drivePoints = mutableListOf<Pair<Long, LatLng>>()
            drivingLogs.value.forEach { log ->
                if (log.pathPoints.isNotEmpty()) {
                    log.pathPoints.forEachIndexed { idx, latLng ->
                        if (!latLng.latitude.isNaN() && !latLng.longitude.isNaN()) {
                            drivePoints.add(Pair(log.date + idx, latLng))
                        }
                    }
                } else if (log.latitude != null && log.longitude != null && log.latitude != 0.0 && log.longitude != 0.0 && !log.latitude.isNaN() && !log.longitude.isNaN()) {
                    drivePoints.add(Pair(log.date, LatLng(log.latitude, log.longitude)))
                }
            }

            _combinedDrivingPoints.value = (statePoints + drivePoints)
                .sortedBy { it.first }
                .map { it.second }
        } catch (e: Exception) {
            errorMsg.value = "[동선 연산 오류] 코드 분기점 예외:\n${e.localizedMessage}\n${e.stackTraceToString().take(300)}"
        }
    }

    fun checkSavedCredentials(context: Context) {
        val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("@secure_api_key", null)
        val savedToken = prefs.getString("@secure_refresh_token", null)
        if (!savedKey.isNullOrEmpty() && !savedToken.isNullOrEmpty()) {
            apiKey = EncryptEngine.decrypt(savedKey)
            refreshToken = EncryptEngine.decrypt(savedToken)
            _isLoggedIn.value = true
            fetchAllData()
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
            fetchAllData()
        } catch (e: Exception) {
            onFail("보안 세션 토큰 저장 실패")
        }
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
        apiKey = ""
        refreshToken = ""
        vehicleStates.value = emptyList()
        _combinedDrivingPoints.value = emptyList()
        errorMsg.value = null
        seedBackupDrivingData()
    }

    fun fetchAllData() {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            isLoading.value = false
            errorMsg.value = "[네트워크 크래시 캡처]\nCode: ERR_CONN_FAILED\n${throwable.localizedMessage}\n${throwable.stackTraceToString().take(300)}"
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            isLoading.value = true
            errorMsg.value = null
            
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

            parseVehicleStates(stateRes)
            parseNetworkDrivingLogs(drivingRes)
            fetchMonthlyReport(accessToken)
            fetchBatteryTrend(idToken)
            
            isLoading.value = false
        }
    }

    private fun getGoogleTokens(): Pair<String, String> {
        val url = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyStr = "{\"grant_type\":\"refresh_token\",\"refresh_token\":\"$refreshToken\"}"
        val req = Request.Builder().url(url).post(bodyStr.toRequestBody(mediaType)).build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string() ?: ""
            val json = JSONObject(raw)
            if (json.has("error")) {
                throw Exception("G_AUTH_FAIL: ${json.optJSONObject("error")?.optString("message")}")
            }
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
                            put(JSONObject().apply { put("fieldFilter", JSONObject().apply { put("field", JSONObject().apply { put("fieldPath", "date") }); put("op", "LESS_THAN_OR_EQUAL"); put("value", JSONObject().apply { put("integerValue", toMs.toString()) }) }) })
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
        client.newCall(req).execute().use { 
            val rawBody = it.body?.string() ?: ""
            if (!it.isSuccessful) throw Exception("HTTP_ERR_${it.code}: $rawBody")
            return rawBody
        }
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

                val moveKMObj = fields.optJSONObject("moveKM")
                val dKM = if (moveKMObj != null) findFirestorePrimitive(moveKMObj)?.toDoubleOrNull() ?: 0.0 else 0.0

                val useBatteryObj = fields.optJSONObject("useBattery")
                val useBattery = if (useBatteryObj != null) findFirestorePrimitive(useBatteryObj)?.toDoubleOrNull() ?: 0.0 else 0.0
                
                val efficiency = if (useBattery > 0.0) {
                    val kwhUsed = (useBattery / 100.0) * 62.1
                    if (kwhUsed > 0.0) String.format(Locale.US, "%.2f", dKM / kwhUsed).toDoubleOrNull() ?: 0.0 else 0.0
                } else 0.0

                val durationStr = fields.optJSONObject("drivingTime")?.optString("stringValue") ?: ""

                val pathPoints = mutableListOf<LatLng>()
                val locListObj = fields.optJSONObject("location_list")
                val locArray = locListObj?.optJSONObject("arrayValue")?.optJSONArray("values")
                if (locArray != null) {
                    for (j in 0 until locArray.length()) {
                        val locItem = locArray.optJSONObject(j) ?: continue
                        val locFields = locItem.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                        val latObj = locFields.optJSONObject("latitude")
                        val lngObj = locFields.optJSONObject("longitude")
                        val lat = if (latObj != null) findFirestorePrimitive(latObj)?.toDoubleOrNull() ?: 0.0 else 0.0
                        val lng = if (lngObj != null) findFirestorePrimitive(lngObj)?.toDoubleOrNull() ?: 0.0 else 0.0
                        if (lat != 0.0 && lng != 0.0 && !lat.isNaN() && !lng.isNaN()) {
                            pathPoints.add(LatLng(lat, lng))
                        }
                    }
                }

                list.add(VehicleStateData(
                    id = doc.optString("name").split("/").lastOrNull() ?: "",
                    date = dDate, drivingKM = dKM, efficiency = efficiency,
                    latitude = pathPoints.lastOrNull()?.latitude, longitude = pathPoints.lastOrNull()?.longitude,
                    durationStr = durationStr, pathPoints = pathPoints
                ))
            }
        } catch (e: Exception) {
            errorMsg.value = "[주행 내역 파싱 오류 코드: ERR_DRIVE_PARSE]\n${e.localizedMessage}"
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

                // 충전 상태 다중 필드 검증 연동 (충전이 안 뜨는 현상 완전 차단)
                val rawStateStr = topFields.optJSONObject("car_state")?.optString("stringValue")
                    ?: topFields.optJSONObject("state")?.optString("stringValue")
                    ?: topFields.optJSONObject("charging_state")?.optString("stringValue")
                    ?: topFields.optJSONObject("charge_state")?.optString("stringValue") ?: "offline"

                val topState = VehicleStateData(
                    id = docId, date = topDate,
                    batteryLevel = getNum(topFields, listOf("battery_level", "batteryLevel", "battery_percent")),
                    batteryRange = getNum(topFields, listOf("battery_range", "batteryRange")),
                    insideTemp = getNum(topFields, listOf("inside_temp", "insideTemp")),
                    outsideTemp = getNum(topFields, listOf("outside_temp", "outsideTemp")),
                    odometer = getNum(topFields, listOf("odometer", "Odometer")),
                    carState = rawStateStr,
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

                        val subStateStr = fields.optJSONObject("state")?.optString("stringValue")
                            ?: fields.optJSONObject("car_state")?.optString("stringValue")
                            ?: fields.optJSONObject("charging_state")?.optString("stringValue") ?: "offline"

                        list.add(VehicleStateData(
                            id = docId, date = logDate,
                            batteryLevel = getNum(fields, listOf("battery_level", "batteryLevel")),
                            batteryRange = getNum(fields, listOf("battery_range", "batteryRange")),
                            insideTemp = getNum(fields, listOf("inside_temp")),
                            outsideTemp = getNum(fields, listOf("outside_temp")),
                            odometer = getNum(fields, listOf("odometer")),
                            carState = subStateStr,
                            latitude = getNum(fields, listOf("latitude")),
                            longitude = getNum(fields, listOf("longitude"))
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            errorMsg.value = "[상태 분석 파싱 오류 코드: ERR_STATE_PARSE]\n${e.localizedMessage}"
        }

        if (list.isNotEmpty()) {
            list.sortByDescending { it.date }

            // 상태 연동 파싱 로직 튜닝
            list.forEach { item ->
                val stateLower = item.carState.lowercase()
                if (stateLower.contains("charging") || stateLower.contains("charge")) {
                    item.computedLabel = "충전"
                    item.badgeBg = Color(0xFF34C759)
                } else if (stateLower.contains("drive") || stateLower.contains("driving")) {
                    item.computedLabel = "주행"
                    item.badgeBg = Color(0xFF007AFF)
                } else if (stateLower.contains("offline")) {
                    item.computedLabel = "오프라인"
                    item.badgeBg = Color.Gray
                } else {
                    item.computedLabel = "주차"
                    item.badgeBg = Color(0xFFA200FF)
                }
            }

            // 차분 세션 매핑
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

                    // 배터리 잔량의 실시간 양수(+) 인입 변화가 잡히면 강제로 충전 표출 귀속
                    if (current.batteryDelta > 0.0 || current.carState.lowercase().contains("charge")) {
                        current.computedLabel = "충전"
                        current.badgeBg = Color(0xFF34C759)
                    }
                }
            }
            vehicleStates.value = list
            calculateTripSummary(list)
        }
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
                val energy = (battDelta / 100.0) * 62.1
                tripInfo.value = TripSummary(
                    efficiency = String.format(Locale.US, "%.2f", distDelta / energy).toDouble(),
                    distance = String.format(Locale.US, "%.1f", distDelta).toDouble(),
                    batteryUsed = String.format(Locale.US, "%.1f", battDelta).toDouble(),
                    kmPerPercent = String.format(Locale.US, "%.2f", kmPerPct).toDouble(),
                    energyUsed = String.format(Locale.US, "%.1f", energy).toDouble(),
                    packCapacity = 62.1,
                    totalTrips = states.filter { it.computedLabel == "주행" }.size.coerceAtLeast(1)
                )
            }
        }
    }

    private fun fetchMonthlyReport(token: String) {
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchBatteryTrend(idToken: String) {
        try {
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
            if (list.isNotEmpty()) {
                batteryData.value = list.sortedBy { it.weekKey }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
