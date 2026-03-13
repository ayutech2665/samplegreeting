package com.kpit.samplegreeting

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * STRICT context-to-app mapping enforced here.
 * Even if the model outputs commute_to_work for STOPPED_EN_ROUTE,
 * this manager will reject it and return "none" instead.
 */
class AppFeedbackManager(context: Context) {
    companion object {
        private const val TAG = "AppFeedback"
        private const val PREFS = "app_feedback_prefs"
        private const val LOG_P = "feedback_log_prefs"
        const val SUPPRESS_THRESHOLD = 3

        // STRICT: Only these apps are EVER valid per context
        val VALID_APPS = mapOf(
            "IDLE_HOME"         to listOf("mespace"),
            "COMMUTING_TO_WORK" to listOf("commute_to_work", "music"),
            "COMMUTING_HOME"    to listOf("commute_to_home", "music"),
            "STOPPED_EN_ROUTE"  to listOf("anti_stress", "music"),
            "COMMUTING_OUTING"  to listOf("navigation", "music"),
            "OUTING_IDLE"       to listOf("mespace"),
            "ARRIVED_WORK"      to emptyList(),
            "ARRIVED_HOME"      to emptyList(),
            "IDLE_WORK"         to emptyList()
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val logPrefs: SharedPreferences = context.getSharedPreferences(LOG_P, Context.MODE_PRIVATE)

    fun recordYes(ctx: String, appId: String, hour: Int, wd: Int, prev: String, dwell: Int) {
        val k = "${ctx}::${appId}::yes"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        addLog(ctx, appId, "yes", hour, wd, prev, dwell)
    }

    fun recordNo(ctx: String, appId: String, hour: Int, wd: Int, prev: String, dwell: Int) {
        val k = "${ctx}::${appId}::no"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
        addLog(ctx, appId, "no", hour, wd, prev, dwell)
        if (isSuppressed(ctx, appId)) Log.d(TAG, "SUPPRESSED: $appId in $ctx")
    }

    private fun getMultiplier(ctx: String, appId: String): Float {
        val yes = prefs.getInt("${ctx}::${appId}::yes", 0)
        val no = prefs.getInt("${ctx}::${appId}::no", 0)
        if (no >= SUPPRESS_THRESHOLD && yes == 0) return 0f
        return (yes + 1f) / (yes + no + 2f)
    }

    fun isSuppressed(ctx: String, appId: String): Boolean {
        val yes = prefs.getInt("${ctx}::${appId}::yes", 0)
        val no = prefs.getInt("${ctx}::${appId}::no", 0)
        return no >= SUPPRESS_THRESHOLD && yes == 0
    }

    /**
     * Adjust model probabilities WITH STRICT VALIDATION.
     *
     * Step 1: Zero out any app that's NOT in VALID_APPS for this context
     * Step 2: Apply suppression multipliers from feedback
     * Step 3: If nothing left → return "none"
     *
     * This guarantees: STOPPED_EN_ROUTE will NEVER suggest commute_to_work
     */
    fun adjustAndValidate(
        ctx: String, rawProbs: FloatArray, appClasses: List<String>
    ): Pair<FloatArray, Boolean> {
        val valid = VALID_APPS[ctx] ?: emptyList()
        val adjusted = FloatArray(rawProbs.size)
        var anyAdj = false

        for (i in rawProbs.indices) {
            val app = appClasses[i]
            when {
                app == "none" -> {
                    // "none" is always allowed
                    adjusted[i] = rawProbs[i]
                }
                app !in valid -> {
                    // STRICT: this app is NOT valid for this context → zero
                    adjusted[i] = 0f
                }
                else -> {
                    // Valid app → apply feedback multiplier
                    val m = getMultiplier(ctx, app)
                    adjusted[i] = rawProbs[i] * m
                    if (m != 0.5f) anyAdj = true
                }
            }
        }

        // If all valid apps are zeroed (suppressed) → boost "none"
        val noneIdx = appClasses.indexOf("none")
        val anyValidLeft = valid.any { app ->
            val idx = appClasses.indexOf(app)
            idx >= 0 && adjusted[idx] > 0.001f
        }
        if (!anyValidLeft && noneIdx >= 0) {
            adjusted[noneIdx] = 1.0f
        }

        return Pair(adjusted, anyAdj)
    }

    fun hasAnyFeedback(): Boolean = prefs.all.any { it.key.contains("::") }

    fun getFeedbackSummary(ctx: String, appId: String): String {
        val y = prefs.getInt("${ctx}::${appId}::yes", 0)
        val n = prefs.getInt("${ctx}::${appId}::no", 0)
        if (y == 0 && n == 0) return ""
        return "yes=$y no=$n" + if (isSuppressed(ctx, appId)) " SUPPRESSED" else ""
    }

    // ── Log for export ──
    private fun addLog(ctx: String, app: String, action: String, hr: Int, wd: Int, prev: String, dw: Int) {
        val e = JSONObject().apply {
            put("context", ctx); put("app", app); put("action", action)
            put("hour", hr); put("weekday", wd); put("is_weekend", wd >= 5)
            put("prev_context", prev); put("dwell", dw); put("is_first_boot", prev == "NONE")
        }
        val arr = logArray(); arr.put(e)
        logPrefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun logArray(): JSONArray {
        return try { JSONArray(logPrefs.getString("entries", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    fun getLogCount(): Int = logArray().length()

    fun buildExportJson(deviceId: String): String {
        return JSONObject().apply {
            put("device_id", deviceId)
            put("feedback", logArray())
        }.toString(2)
    }

    fun clearAfterSync() { prefs.edit().clear().apply(); logPrefs.edit().clear().apply() }
    fun resetAll() { prefs.edit().clear().apply(); logPrefs.edit().clear().apply() }
}
