package com.kpit.samplegreeting

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class AppFeedbackManager(context: Context) {

    companion object {
        private const val TAG = "AppFeedback"
        private const val PREFS = "app_feedback_prefs"
        private const val LOG_P = "feedback_log_prefs"
        const val SUPPRESS_THRESHOLD = 3

        val VALID_APPS = mapOf(
            "IDLE_HOME" to listOf("mespace"),
            "COMMUTING_TO_WORK" to listOf("commute_to_work", "music"),
            "COMMUTING_HOME" to listOf("commute_to_home", "music"),
            "STOPPED_EN_ROUTE" to listOf("anti_stress", "music"),
            "COMMUTING_OUTING" to listOf("navigation", "music"),
            "OUTING_IDLE" to listOf("mespace"),
            "ARRIVED_WORK" to emptyList(),
            "ARRIVED_HOME" to emptyList(),
            "IDLE_WORK" to emptyList()
        )

        /**
         * Time buckets — so morning and evening preferences don't conflict.
         * "No to music at 7 PM" won't affect "Yes to music at 7 AM".
         */
        fun timeBucket(hour: Int): String {
            return when (hour) {
                in 5..11 -> "MORNING"
                in 12..16 -> "AFTERNOON"
                in 17..20 -> "EVENING"
                else -> "NIGHT"
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val logPrefs: SharedPreferences =
        context.getSharedPreferences(LOG_P, Context.MODE_PRIVATE)

    // ══════════════════════════════════════
    // RECORD FEEDBACK (time-aware keys)
    // ══════════════════════════════════════

    fun recordYes(ctx: String, appId: String, hour: Int, wd: Int, prev: String, dwell: Int) {
        val tb = timeBucket(hour)
        val k = "${ctx}::${tb}::${appId}::yes"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        addLog(ctx, appId, "yes", hour, wd, prev, dwell)
        Log.i(TAG, "YES: $appId in $ctx [$tb] (count=${prefs.getInt(k, 0)})")
    }

    fun recordNo(ctx: String, appId: String, hour: Int, wd: Int, prev: String, dwell: Int) {
        val tb = timeBucket(hour)
        val k = "${ctx}::${tb}::${appId}::no"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        addLog(ctx, appId, "no", hour, wd, prev, dwell)
        Log.i(TAG, "NO: $appId in $ctx [$tb] (count=${prefs.getInt(k, 0)})")
        if (isSuppressed(ctx, appId, hour)) {
            Log.i(TAG, "SUPPRESSED: $appId in $ctx [$tb]")
        }
    }

    // ══════════════════════════════════════
    // MULTIPLIER (time-aware)
    // ══════════════════════════════════════

    private fun getMultiplier(ctx: String, appId: String, hour: Int): Float {
        val tb = timeBucket(hour)
        val yes = prefs.getInt("${ctx}::${tb}::${appId}::yes", 0)
        val no = prefs.getInt("${ctx}::${tb}::${appId}::no", 0)
        if (no >= SUPPRESS_THRESHOLD && yes == 0) return 0f
        return (yes + 1f) / (yes + no + 2f)
    }

    fun isSuppressed(ctx: String, appId: String, hour: Int): Boolean {
        val tb = timeBucket(hour)
        val yes = prefs.getInt("${ctx}::${tb}::${appId}::yes", 0)
        val no = prefs.getInt("${ctx}::${tb}::${appId}::no", 0)
        return no >= SUPPRESS_THRESHOLD && yes == 0
    }

    // ══════════════════════════════════════
    // ADJUST + VALIDATE (time-aware)
    // ══════════════════════════════════════

    fun adjustAndValidate(
        ctx: String,
        rawProbs: FloatArray,
        appClasses: List<String>,
        hour: Int
    ): Pair<FloatArray, Boolean> {
        val valid = VALID_APPS[ctx] ?: emptyList()
        val adjusted = FloatArray(rawProbs.size)
        var anyAdj = false

        for (i in rawProbs.indices) {
            val app = appClasses[i]
            when {
                app == "none" -> adjusted[i] = rawProbs[i]
                app !in valid -> adjusted[i] = 0f
                else -> {
                    val m = getMultiplier(ctx, app, hour)
                    adjusted[i] = rawProbs[i] * m
                    if (m != 0.5f) anyAdj = true
                }
            }
        }

        val noneIdx = appClasses.indexOf("none")
        val anyLeft = valid.any { a ->
            val idx = appClasses.indexOf(a)
            idx >= 0 && adjusted[idx] > 0.001f
        }
        if (!anyLeft && noneIdx >= 0) adjusted[noneIdx] = 1.0f

        return Pair(adjusted, anyAdj)
    }

    // ══════════════════════════════════════
    // QUERY
    // ══════════════════════════════════════

    fun hasAnyFeedback(): Boolean = prefs.all.any { it.key.contains("::") }

    fun getFeedbackSummary(ctx: String, appId: String, hour: Int): String {
        val tb = timeBucket(hour)
        val y = prefs.getInt("${ctx}::${tb}::${appId}::yes", 0)
        val n = prefs.getInt("${ctx}::${tb}::${appId}::no", 0)
        if (y == 0 && n == 0) return ""
        return "[$tb] yes=$y no=$n" +
                if (isSuppressed(ctx, appId, hour)) " SUPPRESSED" else ""
    }

    // ══════════════════════════════════════
    // FEEDBACK LOG (for server sync)
    // ══════════════════════════════════════

    private fun addLog(
        ctx: String, app: String, action: String,
        hr: Int, wd: Int, prev: String, dw: Int
    ) {
        val e = JSONObject().apply {
            put("context", ctx)
            put("app", app)
            put("action", action)
            put("hour", hr)
            put("weekday", wd)
            put("is_weekend", wd >= 5)
            put("prev_context", prev)
            put("dwell", dw)
            put("is_first_boot", prev == "NONE")
        }
        val arr = logArray()
        arr.put(e)
        logPrefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun logArray(): JSONArray {
        return try {
            JSONArray(logPrefs.getString("entries", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun getLogCount(): Int = logArray().length()

    fun buildExportJson(deviceId: String): String {
        return JSONObject().apply {
            put("device_id", deviceId)
            put("feedback", logArray())
        }.toString(2)
    }

    fun clearAfterSync() {
        prefs.edit().clear().apply()
        logPrefs.edit().clear().apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        logPrefs.edit().clear().apply()
    }
}
