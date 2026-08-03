package cl.cornejotux.antplus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HrSample(val time: Long, val bpm: Int)
data class GpsPoint(val time: Long, val latitude: Double, val longitude: Double, val accuracy: Float)
data class ActivityRecord(val id: Long, val type: String, val start: Long, val end: Long, val samples: List<HrSample>, val route: List<GpsPoint> = emptyList()) {
    val durationMs get() = end - start
    fun routeDistanceKm(): Double = route.zipWithNext().sumOf { (a, b) ->
        val result = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        result[0].toDouble() / 1000.0
    }
}

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("antplus_local", Context.MODE_PRIVATE)
    fun list(): List<ActivityRecord> {
        val array = JSONArray(prefs.getString("records", "[]"))
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val samples = obj.optJSONArray("samples") ?: JSONArray()
            val route = obj.optJSONArray("route") ?: JSONArray()
            ActivityRecord(obj.getLong("id"), obj.getString("type"), obj.getLong("start"), obj.getLong("end"),
                (0 until samples.length()).map { j -> samples.getJSONObject(j).let { HrSample(it.getLong("time"), it.getInt("bpm")) } },
                (0 until route.length()).map { j -> route.getJSONObject(j).let { GpsPoint(it.getLong("time"), it.getDouble("lat"), it.getDouble("lon"), it.getDouble("accuracy").toFloat()) } })
        }.sortedByDescending { it.start }
    }
    fun save(record: ActivityRecord) {
        val array = JSONArray()
        list().filterNot { it.id == record.id }.plus(record).forEach { r ->
            val obj = JSONObject().put("id", r.id).put("type", r.type).put("start", r.start).put("end", r.end)
            obj.put("samples", JSONArray(r.samples.map { JSONObject().put("time", it.time).put("bpm", it.bpm) }))
            obj.put("route", JSONArray(r.route.map { JSONObject().put("time", it.time).put("lat", it.latitude).put("lon", it.longitude).put("accuracy", it.accuracy) }))
            array.put(obj)
        }
        prefs.edit().putString("records", array.toString()).apply()
    }
    fun unit() = prefs.getString("unit", "métrico") ?: "métrico"
    fun setUnit(value: String) = prefs.edit().putString("unit", value).apply()
}
