package com.baris.tracker

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class FlightSegment(
    val id: String,
    val origin: String,
    val dest: String,
    val date: String,
    val label: String
)

data class FlightResult(
    val segment: FlightSegment,
    val price: Double?,
    val flightDetails: String,
    val previousPrice: Double? = null,
    val error: String? = null
)

val SEGMENTS = listOf(
    FlightSegment("VNO_TRN", "VNO", "TRN", "2026-12-26", "Vilnius -> Turinas"),
    FlightSegment("TRN_BRI", "TRN", "BRI", "2026-12-26", "Turinas -> Baris"),
    FlightSegment("BRI_VIE", "BRI", "VIE", "2027-01-03", "Baris -> Viena"),
    FlightSegment("VIE_VNO", "VIE", "VNO", "2027-01-03", "Viena -> Vilnius"),
    FlightSegment("BRI_BGY", "BRI", "BGY", "2027-01-02", "Baris -> Bergamas"),
    FlightSegment("BGY_VNO", "BGY", "VNO", "2027-01-03", "Bergamas -> Vilnius")
)

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("FlightPrices", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FlightTrackerScreen()
                }
            }
        }
    }

    @Composable
    fun FlightTrackerScreen() {
        val coroutineScope = rememberCoroutineScope()
        var results by remember { mutableStateOf<List<FlightResult>>(emptyList()) }
        var isLoading by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Baris Flight Tracker", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    isLoading = true
                    coroutineScope.launch {
                        val fetchedResults = fetchAllPrices()
                        results = fetchedResults
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Kraunama..." else "Atnaujinti kainas")
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn {
                items(results) { result ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = result.segment.label, style = MaterialTheme.typography.titleMedium)
                            Text(text = "Data: ${result.segment.date}")
                            if (result.price != null) {
                                Text(text = "Kaina: ${result.price} EUR")
                                Text(text = "Skrydis: ${result.flightDetails}")

                                if (result.previousPrice != null && result.price < result.previousPrice) {
                                    val diff = String.format("%.2f", result.previousPrice - result.price)
                                    Text(
                                        text = "📉 Atpigo $diff EUR! (Buvo ${result.previousPrice} EUR)",
                                        color = Color(0xFF4CAF50), // Green color
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else if (result.previousPrice != null && result.price > result.previousPrice) {
                                    Text(
                                        text = "Pabrango (Buvo ${result.previousPrice} EUR)",
                                        color = Color.Red,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(text = result.error ?: "Nerasta skrydžių")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchAllPrices(): List<FlightResult> = withContext(Dispatchers.IO) {
        SEGMENTS.map { segment ->
            fetchRyanairLowestFare(segment)
        }
    }

    private fun fetchRyanairLowestFare(segment: FlightSegment): FlightResult {
        val url = "https://services-api.ryanair.com/farfnd/v4/oneWayFares?" +
                "departureAirportIataCode=${segment.origin}&" +
                "arrivalAirportIataCode=${segment.dest}&" +
                "outboundDepartureDateFrom=${segment.date}&" +
                "outboundDepartureDateTo=${segment.date}&" +
                "currency=EUR"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return FlightResult(segment, null, "", null, "Klaida: ${response.code}")
            }

            val responseBody = response.body?.string() ?: ""
            val jsonObject = JSONObject(responseBody)

            if (jsonObject.has("fares")) {
                val fares = jsonObject.getJSONArray("fares")
                if (fares.length() == 0) {
                    return FlightResult(segment, null, "", null, "Nėra skrydžių")
                }

                var bestPrice = Double.MAX_VALUE
                var bestFlightDetails = ""

                for (i in 0 until fares.length()) {
                    val fare = fares.getJSONObject(i)
                    val outbound = fare.getJSONObject("outbound")
                    val price = outbound.getJSONObject("price").getDouble("value")
                    if (price < bestPrice) {
                        bestPrice = price
                        val depTime = outbound.getString("departureDate").split("T")[1].substring(0, 5)
                        val arrTime = outbound.getString("arrivalDate").split("T")[1].substring(0, 5)
                        val flightNo = outbound.optString("flightNumber", "")
                        bestFlightDetails = "$flightNo ($depTime-$arrTime)"
                    }
                }

                val previousPriceFloat = sharedPreferences.getFloat(segment.id, -1f)
                val previousPrice = if (previousPriceFloat != -1f) previousPriceFloat.toDouble() else null

                // Save the new best price
                sharedPreferences.edit().putFloat(segment.id, bestPrice.toFloat()).apply()

                FlightResult(segment, bestPrice, bestFlightDetails, previousPrice)
            } else {
                FlightResult(segment, null, "", null, "Nėra skrydžių")
            }
        } catch (e: Exception) {
            FlightResult(segment, null, "", null, "Klaida: ${e.message}")
        }
    }
}
