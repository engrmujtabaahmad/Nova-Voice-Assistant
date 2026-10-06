package com.mujtaba.nova.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

object Http {
    fun get(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NovaAssistant")
            setRequestProperty("Accept", "*/*")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    fun today(): String =
        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))
}

// ---------------------------------------------------------------
// News / web headlines (Google News RSS, free, no key)
// ---------------------------------------------------------------
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Latest news headlines from the web (Google News) on any topic: Pakistan news, petrol or diesel " +
                "price news, technology news, news about a person or company, 'what happened today'. " +
                "For exchange rates use get_exchange_rate and for weather use get_weather instead. " +
                "query must be a short English search phrase."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put(
                "query",
                JSONObject().put("type", "STRING").put("description", "Short English search phrase, e.g. petrol price Pakistan")
            )
        )
        .put("required", JSONArray().put("query"))

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolResult(false, "Search ka sawal samajh nahi aaya.")

        return try {
            val full = URLEncoder.encode("$q when:7d", "UTF-8")
            val url = "https://news.google.com/rss/search?q=$full&hl=en-PK&gl=PK&ceid=PK:en"
            val (code, body) = Http.get(url)
            if (code !in 200..299) {
                return ToolResult(false, "News search nahi ho saki (HTTP $code).")
            }

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(body.byteInputStream())
            val items = doc.getElementsByTagName("item")
            val lines = mutableListOf<String>()
            for (i in 0 until minOf(items.length, 8)) {
                val el = items.item(i) as Element
                val title = el.getElementsByTagName("title").item(0)?.textContent?.trim() ?: continue
                val pub = el.getElementsByTagName("pubDate").item(0)?.textContent?.trim() ?: ""
                lines.add("${lines.size + 1}) [${pub.take(16)}] $title")
            }

            if (lines.isEmpty()) {
                ToolResult(false, "Is topic par koi taza khabar nahi mili.")
            } else {
                ToolResult(
                    true,
                    "Google News headlines (checked on ${Http.today()}). These are headlines only, not full articles. " +
                            "If the exact figure or fact is not in the headlines, say so honestly. " +
                            lines.joinToString(" ")
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "News search nahi ho saki: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }
}

// ---------------------------------------------------------------
// Exchange rates (open.er-api.com, free, no key)
// ---------------------------------------------------------------
class ExchangeRateTool : Tool {
    override val name = "get_exchange_rate"
    override val description =
        "Currency exchange rate, e.g. USD to PKR, SAR to PKR, EUR to USD. Use 3-letter currency codes. " +
                "amount is optional (default 1)."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("from", JSONObject().put("type", "STRING").put("description", "Currency code, default USD"))
                .put("to", JSONObject().put("type", "STRING").put("description", "Currency code, default PKR"))
                .put("amount", JSONObject().put("type", "NUMBER").put("description", "Default 1"))
        )

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val from = args.optString("from").trim().uppercase().ifBlank { "USD" }
        val to = args.optString("to").trim().uppercase().ifBlank { "PKR" }
        val amount = args.optDouble("amount", 1.0).let { if (it.isNaN() || it <= 0) 1.0 else it }

        return try {
            val (code, body) = Http.get("https://open.er-api.com/v6/latest/$from")
            if (code !in 200..299) {
                return ToolResult(false, "Exchange rate nahi mil saka (HTTP $code).")
            }
            val json = JSONObject(body)
            if (json.optString("result") != "success") {
                return ToolResult(false, "'$from' currency code samajh nahi aaya.")
            }
            val rates = json.getJSONObject("rates")
            if (!rates.has(to)) return ToolResult(false, "'$to' currency code nahi mila.")

            val rate = rates.getDouble(to)
            val updated = json.optString("time_last_update_utc", "unknown")
            val total = rate * amount
            ToolResult(
                true,
                "${String.format(Locale.US, "%.2f", amount)} $from = ${String.format(Locale.US, "%.2f", total)} $to " +
                        "(1 $from = ${String.format(Locale.US, "%.2f", rate)} $to). " +
                        "Rate last updated: $updated. This is the interbank/market rate; local open-market or bank rates in Pakistan can differ slightly."
            )
        } catch (e: Exception) {
            ToolResult(false, "Exchange rate nahi mil saka: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }
}

// ---------------------------------------------------------------
// Weather (Open-Meteo, free, no key)
// ---------------------------------------------------------------
class WeatherTool : Tool {
    override val name = "get_weather"
    override val description =
        "Current weather and today's forecast for a city, e.g. Lahore, Karachi, Islamabad. " +
                "If the user did not say which city, ask them first."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put("city", JSONObject().put("type", "STRING").put("description", "City name in English"))
        )
        .put("required", JSONArray().put("city"))

    private fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        in 95..99 -> "thunderstorm"
        else -> "unknown conditions"
    }

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val city = args.optString("city").trim()
        if (city.isEmpty()) return ToolResult(false, "Shehar ka naam samajh nahi aaya.")

        return try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, "UTF-8") + "&count=1&language=en&format=json"
            val (gc, gb) = Http.get(geoUrl)
            if (gc !in 200..299) return ToolResult(false, "Shehar dhoondh nahi saka (HTTP $gc).")

            val results = JSONObject(gb).optJSONArray("results")
            if (results == null || results.length() == 0) {
                return ToolResult(false, "'$city' naam ka shehar nahi mila.")
            }
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val placeName = place.optString("name", city)
            val country = place.optString("country", "")

            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1"
            val (wc, wb) = Http.get(url)
            if (wc !in 200..299) return ToolResult(false, "Mausam ki maloomat nahi mil saki (HTTP $wc).")

            val json = JSONObject(wb)
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val temp = cur.getDouble("temperature_2m")
            val feels = cur.getDouble("apparent_temperature")
            val hum = cur.getInt("relative_humidity_2m")
            val wind = cur.getDouble("wind_speed_10m")
            val cond = describe(cur.getInt("weather_code"))
            val max = daily.getJSONArray("temperature_2m_max").getDouble(0)
            val min = daily.getJSONArray("temperature_2m_min").getDouble(0)
            val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)

            ToolResult(
                true,
                "Weather in $placeName, $country (checked ${Http.today()}): now ${temp.toInt()} C " +
                        "(feels like ${feels.toInt()} C), $cond, humidity $hum percent, wind ${wind.toInt()} km/h. " +
                        "Today: high ${max.toInt()} C, low ${min.toInt()} C, chance of rain $rain percent."
            )
        } catch (e: Exception) {
            ToolResult(false, "Mausam nahi mil saka: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }
}