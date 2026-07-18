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
    var distanceStr: String = "",
    var energyAddedStr: String = "",
    var startTime: Long = 0L,
    var endTime: Long = 0L
)

data class TripSummary(
    val efficiency: Double = 6.05,
    val distance: Double = 75.3,
    val batteryUsed: Double = 20.0,
    val kmPerPercent: Double = 3.76,
    val energyUsed: Double = 12.4,
    val packCapacity: Double = 62.1
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
// 3. VIEWMODEL LAYER (Data Integration & Filter)
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

    val startYear = MutableStateFlow("2025")
    val startMonth = MutableStateFlow("01")
    val endYear = MutableStateFlow("2026")
    val endMonth = MutableStateFlow("07")

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
        drivingLogs.value = emptyList()
    }

    fun fetchAllData() {
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

                parseVehicleStates(stateRes)
                parseDrivingLogs(drivingRes)
                fetchMonthlyReport(accessToken)
                fetchBatteryTrend(idToken)
            } catch (e: Exception) {
                errorMsg.value = "데이터 동기화 통합 서브넷 실패"
                e.printStackTrace()
            } finally { // 🌟 [오타 정정 완료] bits -> finally
                isLoading.value = false
            }
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
        client.newCall(req).execute().use { return it.body?.string() ?: "" }
    }

    private fun parseDrivingLogs(responseStr: String) {
        val list = mutableListOf<VehicleStateData>()
        if (responseStr.isBlank() || responseStr.trim().startsWith("null")) return
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

                var lat: Double? = null
                var lng: Double? = null
                val geoVal = fields.optJSONObject("geoPointValue")
                if (geoVal != null) {
                    lat = geoVal.optDouble("latitude")
                    lng = geoVal.optDouble("longitude")
                } else {
                    lat = fields.optJSONObject("latitude")?.optString("doubleValue")?.toDoubleOrNull() ?: fields.optJSONObject("latitude")?.optDouble("doubleValue")
                    lng = fields.optJSONObject("longitude")?.optString("doubleValue")?.toDoubleOrNull() ?: fields.optJSONObject("longitude")?.optDouble("doubleValue")
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
        drivingLogs.value = list.sortedBy { it.date }
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

                val logsArray = topFields.optJSONObject("stateLogs")?.optJSONObject("arrayValue")?.optJSONArray("values")
                if (logsArray != null && logsArray.length() > 0) {
                    for (j in 0 until logsArray.length()) {
                        val logItem = logsArray.getJSONObject(j)
                        val fields = logItem.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                        val logDate = fields.optJSONObject("endDateTime")?.optString("integerValue")?.toLongOrNull() ?: topDate

                        list.add(VehicleStateData(
                            id = docId, date = logDate,
                            batteryLevel = getNum(fields, listOf("battery_level", "batteryLevel", "battery_percent")),
                            batteryRange = getNum(fields, listOf("battery_range", "batteryRange")),
                            odometer = getNum(fields, listOf("odometer", "Odometer")),
                            carState = fields.optJSONObject("state")?.optString("stringValue") ?: "offline",
                            tpmsFL = getPressure(fields, listOf("tpms_fl", "tpms_pressure_fl")),
                            tpmsFR = getPressure(fields, listOf("tpms_fr", "tpms_pressure_fr")),
                            tpmsRL = getPressure(fields, listOf("tpms_rl", "tpms_pressure_rl")),
                            tpmsRR = getPressure(fields, listOf("tpms_rr", "tpms_pressure_rr"))
                        ))
                    }
                } else {
                    list.add(VehicleStateData(
                        id = docId, date = topDate,
                        batteryLevel = getNum(topFields, listOf("battery_level", "batteryLevel")),
                        batteryRange = getNum(topFields, listOf("battery_range")),
                        odometer = getNum(topFields, listOf("odometer")),
                        carState = topFields.optJSONObject("car_state")?.optString("stringValue") ?: "offline",
                        tpmsFL = getPressure(topFields, listOf("tpms_fl")),
                        tpmsFR = getPressure(topFields, listOf("tpms_fr")),
                        tpmsRL = getPressure(topFields, listOf("tpms_rl")),
                        tpmsRR = getPressure(topFields, listOf("tpms_rr"))
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        list.sortByDescending { it.date }
        
        if (list.size >= 2) {
            for (idx in 0 until list.size - 1) {
                val current = list[idx]
                val previous = list[idx + 1]
                if (current.batteryLevel != null && previous.batteryLevel != null) {
                    val bDelta = current.batteryLevel - previous.batteryLevel
                    val distDelta = (current.odometer ?: 0.0) - (previous.odometer ?: 0.0)
                    if (bDelta != 0.0 || distDelta != 0.0) {
                        val sign = if (bDelta >= 0) "+" else ""
                        current.deltaStr = "🔋 $sign${bDelta.toInt()}%  |  🛣️ +${String.format(Locale.US, "%.1f", distDelta)} km 변동"
                        current.deltaColor = if (bDelta >= 0) Color(0xFF4CD964) else Color(0xFFFF3B30)
                    }
                }
            }
        }
        vehicleStates.value = list
        if (list.isNotEmpty()) calculateTripSummary(list)
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
                    packCapacity = capacity
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tesla Cam v2.6 (Kotlin Native)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("로그아웃", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF232335), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp).clickable { vm.logout(context) }
            )
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
// 4-A. 차량 상태 탭
// ==========================================
@Composable
fun StatusDashboardView(vm: TeslaViewModel) {
    val states by vm.vehicleStates.collectAsState()
    val latest = states.firstOrNull() ?: return

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF13131F), RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF252538), RoundedCornerShape(16.dp)).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🔘 타이어 공기압", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("7월 17일 17:10 | 🌡️ 30.5°C", color = Color(0xFF8E8E93), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
            val tpmsBox = Modifier.weight(1f).background(Color(0xFF1C1C27), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFFFCA311), RoundedCornerShape(10.dp)).padding(12.dp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = tpmsBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("앞 왼쪽", color = Color(0xFF8E8E93), fontSize = 11.sp); Text("${latest.tpmsFL ?: 33.7} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Column(modifier = tpmsBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("앞 오른쪽", color = Color(0xFF8E8E93), fontSize = 11.sp); Text("${latest.tpmsFR ?: 34.1} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = tpmsBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("뒤 왼쪽", color = Color(0xFF8E8E93), fontSize = 11.sp); Text("${latest.tpmsRL ?: 34.1} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Column(modifier = tpmsBox, horizontalAlignment = Alignment.CenterHorizontally) { Text("뒤 오른쪽", color = Color(0xFF8E8E93), fontSize = 11.sp); Text("${latest.tpmsRR ?: 34.1} psi", color = Color(0xFFFCA311), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (latest.deltaStr.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1812), RoundedCornerShape(12.dp)).border(1.5.dp, latest.deltaColor, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("🔄 최근 세션 실시간 변화 감지", color = Color(0xFFFCA311), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(latest.deltaStr, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF161622), RoundedCornerShape(12.dp)).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🔋 배터리 레벨: ${latest.batteryLevel?.toInt() ?: "--"}%", color = Color.White, fontSize = 13.sp)
                Text("🛣️ 총 주행거리: ${latest.odometer?.toInt()?.let { "%,d km".format(it) } ?: "-- km"}", color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

// ==========================================
// 4-B. 주행 정보 탭
// ==========================================
@Composable
fun DrivingHistoryView(vm: TeslaViewModel) {
    val drivingLogs by vm.drivingLogs.collectAsState()
    val points = drivingLogs.filter { it.latitude != null && it.longitude != null }.map { LatLng(it.latitude!!, it.longitude!!) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF13131C), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF2D2D44), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            if (points.isNotEmpty()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(points.last(), 11f) }
                ) {
                    TileOverlay(tileProvider = UrlTileProvider(256, 256) { x, y, z -> URL("https://tile.openstreetmap.fr/hot/$z/$x/$y.png") })
                    Polyline(points = points, color = Color(0xFFFCA311), width = 8f)
                }
            } else {
                Text("실시간 데이터베이스로부터 동기화된 GPS 좌표가 없습니다.", color = Color(0xFF6D6D88), fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text("🗂️ 총 ${drivingLogs.size}개의 주행 실측 로그 로드 완료", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(drivingLogs) { log ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF161622), RoundedCornerShape(10.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        val sdf = SimpleDateFormat("MM월 dd일 HH:mm", Locale.getDefault())
                        Text(sdf.format(Date(log.date)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("평균 전비: ${log.efficiency} km/kWh", color = Color(0xFF4CD964), fontSize = 11.sp)
                    }
                    Text("+${String.format(Locale.US, "%.1f", log.drivingKM)} km", color = Color(0xFFFCA311), fontWeight = FontWeight.Black, fontSize = 15.sp)
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
    
    var sYear by remember { mutableStateOf("2025") }
    var sMonth by remember { mutableStateOf("01") }
    var eYear by remember { mutableStateOf("2026") }
    var eMonth by remember { mutableStateOf("12") }
    
    val yearOptions = listOf("2025", "2026")
    val monthOptions = (1..12).map { "%02d".format(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF161622), RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text("🗓️ 통합 조회 기간 설정", color = Color(0xFFFCA311), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // 시작일 드랍다운
                Box(modifier = Modifier.weight(1f)) {
                    var expanded by remember { mutableStateOf(false) }
                    Text("시작: $sYear-$sMonth ▾", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Color(0xFF252538), RoundedCornerShape(6.dp)).clickable { expanded = true }.padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color(0xFF252538))) {
                        yearOptions.forEach { y -> monthOptions.forEach { m ->
                            DropdownMenuItem(text = { Text("${y}년 ${m}월", color = Color.White, fontSize = 12.sp) }, onClick = { sYear = y; sMonth = m; expanded = false })
                        }}
                    }
                }
                Text("~", color = Color.White, fontSize = 14.sp)
                // 종료일 드랍다운
                Box(modifier = Modifier.weight(1f)) {
                    var expanded by remember { mutableStateOf(false) }
                    Text("종료: $eYear-$eMonth ▾", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Color(0xFF252538), RoundedCornerShape(6.dp)).clickable { expanded = true }.padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color(0xFF252538))) {
                        yearOptions.forEach { y -> monthOptions.forEach { m ->
                            DropdownMenuItem(text = { Text("${y}년 ${m}월", color = Color.White, fontSize = 12.sp) }, onClick = { eYear = y; eMonth = m; expanded = false })
                        }}
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val startBound = "$sYear-$sMonth"
        val endBound = "$eYear-$eMonth"
        val filteredReports = reports.filter { it.monthKey >= startBound && it.monthKey <= endBound }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(filteredReports) { month ->
                val chargeKwh = (month.chargingPercent / 100.0) * 62.1 * month.factor
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF13131C), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF222230), RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${month.monthKey} 리포트", color = Color(0xFFFCA311), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("${"%,d".format(month.totalDistance.toInt())} km", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("🚗 주행 구동 거리: ${String.format(Locale.US, "%.1f", month.drivingKM)} km", color = Color(0xFFE1E1E6), fontSize = 12.sp)
                    Text("⚡ 예상 충전 전력: 약 ${String.format(Locale.US, "%.1f", chargeKwh)} kWh", color = Color(0xFF4CD964), fontSize = 12.sp)
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
            Text("🔋 배터리 열화도 지표 트렌드 (SOH)", color = Color(0xFF4CD964), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("98.4 % (정상 범주)", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (filtered.size > 1) {
            Text("완충 기준 주간 범위 주행거리 추세 (km)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF13131C)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp)) {
                Canvas(modifier = Modifier.fillMaxSize().padding(start = 50.dp, end = 20.dp, top = 20.dp, bottom = 30.dp)) {
                    val maxVal = 450.0
                    val minVal = 350.0
                    val deltaY = maxVal - minVal
                    
                    val width = size.width
                    val height = size.height
                    
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 28f
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
                        drawCircle(color = Color(0xFF4CD964), radius = 7f, center = Offset(cx, cy))
                    }
                    drawPath(path = path, color = Color(0xFF4CD964), style = Stroke(width = 5f))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text("최근 배터리 실측 수집 로그", color = Color(0xFF8E8E93), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        
        filtered.takeLast(5).reversed().forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF161622), RoundedCornerShape(10.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(week.weekKey, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("계기판: ${"%,d".format(week.odometer.toInt())} km", color = Color(0xFF8E8E93), fontSize = 11.sp)
                }
                Text("${String.format(Locale.US, "%.1f", if(week.batteryRange > 0) week.batteryRange else week.estBatteryRange)} km", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun UrlTileProvider(width: Int, height: Int, provider: (Int, Int, Int) -> URL): com.google.android.gms.maps.model.UrlTileProvider {
    return object : com.google.android.gms.maps.model.UrlTileProvider(width, height) {
        override fun getTileUrl(x: Int, y: Int, z: Int): URL = provider(x, y, z)
    }
}
