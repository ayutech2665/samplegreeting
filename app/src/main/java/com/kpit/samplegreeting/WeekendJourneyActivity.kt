package com.kpit.samplegreeting

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Weekend Home-to-Home Journey (18 steps)
 * NO office involved. Proves model generalizes.
 */
class WeekendJourneyActivity : AppCompatActivity() {

    private lateinit var tvOut: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnRun: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var progressContainer: LinearLayout
    private lateinit var tflite: Interpreter
    private lateinit var meta: ModelMeta

    private val usedGreetings = mutableSetOf<String>()
    private var isRunning = false
    private val totalSteps = 18

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weekend)

        tvOut = findViewById(R.id.tvOut2)
        scrollView = findViewById(R.id.scrollView2)
        btnRun = findViewById(R.id.btnRun2)
        progressBar = findViewById(R.id.progressBar2)
        tvProgress = findViewById(R.id.tvProgress2)
        progressContainer = findViewById(R.id.progressContainer2)

        meta = loadMetaFromAssets("context_model_metadata_v2.json")
        tflite = Interpreter(loadModelFileFromAssets("context_classifier_v2.tflite"))

        btnRun.setOnClickListener {
            if (!isRunning) runWeekendJourneyAnimated()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tflite.isInitialized) tflite.close()
    }

    private fun runWeekendJourneyAnimated() {
        isRunning = true
        usedGreetings.clear()
        btnRun.isEnabled = false
        btnRun.text = "⏳  Running..."

        progressContainer.visibility = View.VISIBLE
        progressBar.max = totalSteps
        progressBar.progress = 0
        tvProgress.text = "0 / $totalSteps"

        val sb = SpannableStringBuilder()

        appendColored(sb, "═══════════════════════════════════\n", "#00796B")
        appendColored(sb, "  WEEKEND HOME-TO-HOME JOURNEY\n", "#00796B")
        appendColored(sb, "  Saturday → Sunday (No Office)\n", "#00796B")
        appendColored(sb, "═══════════════════════════════════\n\n", "#00796B")

        val inputDim = 8 + 3 + meta.prev_state_classes.size
        sb.append("Model: Input dim=$inputDim\n")
        sb.append("Uses distances NEVER seen in training.\n\n")

        tvOut.text = sb
        scrollToBottom()

        val steps = buildWeekendSteps()

        lifecycleScope.launch {
            var prevState = "NONE"
            var journeySection = ""

            for (step in steps) {
                val section = step.journeySection
                if (section != journeySection) {
                    journeySection = section
                    withContext(Dispatchers.Main) {
                        appendColored(sb, "\n───── $section ─────\n\n", "#00796B")
                        tvOut.text = sb
                        scrollToBottom()
                    }
                    delay(600)
                }

                if (step.prevOverride != null) prevState = step.prevOverride

                val result = withContext(Dispatchers.IO) { predict(step.rawNumeric8, prevState) }

                val topIdx = result.indices.maxBy { result[it] }
                val label = meta.class_names[topIdx]
                val conf = result[topIdx]
                val hour = step.rawNumeric8[4].toInt()

                val greeting = getSmartGreeting(label, conf, hour, prevState)

                withContext(Dispatchers.Main) {
                    appendColored(sb, "Step ${step.stepNo}: ", "#333333")
                    appendBold(sb, "${step.title}\n")
                    sb.append("  PrevState: $prevState\n")

                    val predColor = if (conf > 0.7f) "#2E7D32" else "#F57C00"
                    sb.append("  Predicted: ")
                    appendColored(sb, "$label ", predColor)
                    sb.append("(conf=${"%.3f".format(conf)})\n")

                    val emoji = emojiFor(label)
                    appendColored(sb, "  $emoji $greeting\n", "#00695C")

                    val top3 = topK(result, 3)
                    sb.append("  Top-3: $top3\n\n")

                    tvOut.text = sb
                    scrollToBottom()

                    progressBar.progress = step.stepNo
                    tvProgress.text = "${step.stepNo} / $totalSteps"
                }

                prevState = label
                delay(1200)
            }

            withContext(Dispatchers.Main) {
                appendColored(sb, "═══════════════════════════════════\n", "#00796B")
                appendColored(sb, "  ✅ Weekend journey complete!\n", "#2E7D32")
                appendColored(sb, "═══════════════════════════════════\n", "#00796B")
                tvOut.text = sb
                scrollToBottom()

                btnRun.isEnabled = true
                btnRun.text = "▶  Run Weekend Journey"
                isRunning = false
            }
        }
    }

    private fun buildWeekendSteps(): List<ScenarioStep> = listOf(
        ScenarioStep(1, "Saturday morning at home (8:00 AM)",
            floatArrayOf(8f, 1500f, 0f, 0f, 8f, 5f, 0f, 1200f),
            "NONE", "🏠 SATURDAY MORNING"),

        ScenarioStep(2, "Leave home for park (9:00 AM)",
            floatArrayOf(350f, 1800f, 150f, 100f, 9f, 5f, 500f, 0f),
            null, "🌳 DRIVE TO PARK"),

        ScenarioStep(3, "Traffic signal — 3 min wait",
            floatArrayOf(600f, 2000f, 0f, 0f, 9f, 5f, 0f, 180f),
            null, "🌳 DRIVE TO PARK"),

        ScenarioStep(4, "Continue driving to park",
            floatArrayOf(1200f, 2800f, 250f, 200f, 9f, 5f, 800f, 0f),
            null, "🌳 DRIVE TO PARK"),

        // OUTING_IDLE: Vehicle ON at park after arrival → "enjoy your time here"
        ScenarioStep(5, "Vehicle ON at park — just arrived (9:30 AM)",
            floatArrayOf(2500f, 3500f, 0f, 0f, 9f, 5f, 0f, 600f),
            null, "🌲 AT THE PARK"),

        // OUTING_IDLE: Vehicle ON after 3 hours → "hope you had a great time"
        ScenarioStep(6, "Vehicle ON after 3 hours at park",
            floatArrayOf(2500f, 3500f, 0f, 0f, 12f, 5f, 0f, 10800f),
            null, "🌲 AT THE PARK"),

        ScenarioStep(7, "Leave park for restaurant (12:30 PM)",
            floatArrayOf(1800f, 2800f, -200f, 100f, 12f, 5f, 700f, 0f),
            null, "🍽️ LUNCH BREAK"),

        // OUTING_IDLE: Vehicle ON after 2 hours at restaurant
        ScenarioStep(8, "Vehicle ON after 2 hour lunch",
            floatArrayOf(1500f, 2200f, 0f, 0f, 14f, 5f, 0f, 7200f),
            null, "🍽️ LUNCH BREAK"),

        ScenarioStep(9, "Leave restaurant for home (4:00 PM)",
            floatArrayOf(800f, 1800f, -300f, -100f, 16f, 5f, 600f, 0f),
            null, "🏡 HEADING HOME"),

        ScenarioStep(10, "Traffic jam — 8 min wait",
            floatArrayOf(500f, 1700f, 0f, 0f, 16f, 5f, 0f, 480f),
            null, "🏡 HEADING HOME"),

        // ARRIVED_HOME: dist_home = 10m (within 10m radius) ✓
        ScenarioStep(11, "Reached home — within 10m (4:30 PM)",
            floatArrayOf(10f, 1500f, -200f, 50f, 16f, 5f, 0f, 60f),
            null, "🏡 HEADING HOME"),

        ScenarioStep(12, "Settled at home — evening (7:00 PM)",
            floatArrayOf(8f, 1500f, 0f, 0f, 19f, 5f, 0f, 2400f),
            null, "🏠 EVENING AT HOME"),

        ScenarioStep(13, "Leave for night market (8:00 PM)",
            floatArrayOf(400f, 2200f, 200f, 100f, 20f, 5f, 600f, 0f),
            null, "🌃 NIGHT MARKET"),

        // OUTING_IDLE: Vehicle ON after 2 hours at night market
        ScenarioStep(14, "Vehicle ON after 2 hours at night market",
            floatArrayOf(3500f, 4200f, 0f, 0f, 22f, 5f, 0f, 7200f),
            null, "🌃 NIGHT MARKET"),

        ScenarioStep(15, "Head home — midnight",
            floatArrayOf(1500f, 3000f, -500f, -300f, 0f, 6f, 800f, 0f),
            null, "🌙 MIDNIGHT RETURN"),

        // ARRIVED_HOME: dist_home = 10m ✓
        ScenarioStep(16, "Reached home — within 10m (12:30 AM Sun)",
            floatArrayOf(10f, 1500f, -300f, 100f, 0f, 6f, 0f, 60f),
            null, "🌙 MIDNIGHT RETURN"),

        ScenarioStep(17, "Settled at home — Sunday morning (9 AM)",
            floatArrayOf(8f, 1500f, 0f, 0f, 9f, 6f, 0f, 1800f),
            null, "☀️ SUNDAY MORNING"),

        ScenarioStep(18, "Relaxing at home — late morning",
            floatArrayOf(8f, 1500f, 0f, 0f, 11f, 6f, 0f, 3600f),
            null, "☀️ SUNDAY MORNING"),
    )

    // ---------------------------------------------------
    // Smart greeting — time-aware, non-repeating
    // ---------------------------------------------------
    private fun getSmartGreeting(label: String, confidence: Float, hour: Int, prevState: String): String {
        val timePrefix = when (hour) {
            in 5..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else      -> "Late night"
        }

        val isFirstBoot = (prevState == "NONE")
        val pool = buildGreetingPool(label, timePrefix, hour, isFirstBoot)
        val available = pool.filter { it !in usedGreetings }
        val chosen = if (available.isNotEmpty()) available.random() else pool.random()
        usedGreetings.add(chosen)

        val confNote = when {
            confidence > 0.7f  -> " ✦"
            confidence > 0.4f  -> " ~"
            else               -> " ?"
        }
        return chosen + confNote
    }

    private fun buildGreetingPool(label: String, timePrefix: String, hour: Int, isFirstBoot: Boolean): List<String> {
        return when (label) {

            "IDLE_HOME" -> {
                val base = listOf(
                    "$timePrefix! Weekend vibes at home.",
                    "$timePrefix! Relaxing at home — no rush today.",
                    "$timePrefix! Lazy day at home. Love it!",
                    "$timePrefix! Home sweet home on this lovely weekend.",
                    "$timePrefix! Weekend mode ON. Enjoy your time.",
                    "$timePrefix! Chilling at home — the best plan.",
                    "$timePrefix from home! What a great day.",
                    "$timePrefix! Home is where the heart is."
                )
                if (isFirstBoot) {
                    base + listOf(
                        "$timePrefix! Vehicle started at home. Weekend adventure awaits!",
                        "$timePrefix! System online. Starting your weekend from home.",
                        "$timePrefix! Welcome! Looks like a perfect day from home."
                    )
                } else base
            }

            "COMMUTING_OUTING" -> listOf(
                "Weekend drive! Heading somewhere fun.",
                "Off on a weekend adventure! Enjoy!",
                "Leisure drive in progress. Where to?",
                "The open road awaits. Have a great ride!",
                "Weekend outing started. Drive safe!",
                "Heading somewhere exciting! Enjoy the journey.",
                "Non-work trip! That's the weekend spirit.",
                "Road trip vibes on this fine weekend!"
            )

            "STOPPED_EN_ROUTE" -> listOf(
                "Quick pause on the road. Traffic signal?",
                "Brief stop detected. Patience!",
                "Waiting at a signal. Won't be long!",
                "Traffic pause. The road will open up soon.",
                "A short halt. Deep breaths!",
                "Red light? You'll be moving again shortly.",
                "Paused on the journey. Stay calm!",
                "Traffic hold — enjoy the music while you wait!"
            )

            // OUTING_IDLE = Vehicle turned ON after outing stay
            // Past tense — outing is DONE, about to leave
            "OUTING_IDLE" -> listOf(
                "Hope you had a great time! Ready to go?",
                "What a visit! Where to next?",
                "That was a good stop. Starting up again!",
                "Done with your stop? Let's hit the road!",
                "Hope you enjoyed your time there! Vehicle ready.",
                "Break's over — hope it was refreshing!",
                "Great stop! Engine's running, where to now?",
                "All done here? Let's get moving!",
                "What a wonderful outing! Ready for the next leg?",
                "Hope that was fun! Shall we head out?"
            )

            "COMMUTING_HOME" -> listOf(
                "Heading back home. What a great day!",
                "Homeward bound. Rest awaits!",
                "Driving home. What a weekend!",
                "On the way home — memories made!",
                "Return journey started. Drive safe!",
                "Heading home after a great time out.",
                "Home is calling after a wonderful outing.",
                "Almost home! What an adventure!"
            )

            "ARRIVED_HOME" -> listOf(
                "Welcome back home! You're within 10 meters.",
                "Home sweet home. Journey complete!",
                "You're home! Time to relax.",
                "Back at home base. Well done!",
                "Arrived home safely. Great outing!",
                "Home again! Put your feet up.",
                "Journey ends at home. Perfect!",
                "Home arrival — time to unwind!"
            )

            "ARRIVED_WORK" -> listOf(
                "Arrived at your destination!",
                "You've reached your stop.",
                "Destination detected. Have a great time!",
                "You're here! Enjoy your visit."
            )

            "IDLE_WORK" -> listOf(
                "$timePrefix! Settled at your location.",
                "$timePrefix! You've been here a while. Enjoying it?",
                "$timePrefix! Relaxing at your current spot.",
                "$timePrefix! Staying put. All good!"
            )

            "COMMUTING_TO_WORK" -> listOf(
                "On the move! Heading to your destination.",
                "Driving to your next stop.",
                "Journey in progress. Stay safe!",
                "Off to your next destination!"
            )

            else -> listOf(
                "Context: $label detected.",
                "Status: $label."
            )
        }
    }

    private fun emojiFor(label: String): String = when (label) {
        "IDLE_HOME"          -> "🏠"
        "COMMUTING_TO_WORK"  -> "🚗"
        "ARRIVED_WORK"       -> "🏢"
        "IDLE_WORK"          -> "💼"
        "COMMUTING_HOME"     -> "🏡"
        "ARRIVED_HOME"       -> "🎉"
        "STOPPED_EN_ROUTE"   -> "🚦"
        "COMMUTING_OUTING"   -> "🛣️"
        "OUTING_IDLE"        -> "✅"
        else                 -> "📌"
    }

    private fun topK(probs: FloatArray, k: Int): String {
        val idxs = probs.indices.sortedByDescending { probs[it] }.take(k)
        return idxs.joinToString { "${meta.class_names[it]}:${"%.3f".format(probs[it])}" }
    }

    private fun appendColored(sb: SpannableStringBuilder, text: String, hexColor: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(Color.parseColor(hexColor)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendBold(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun predict(rawNumeric8: FloatArray, prevState: String): FloatArray {
        require(rawNumeric8.size == 8)
        val xNum = normalizeNumeric(rawNumeric8, meta)
        val isWeekend    = if (rawNumeric8[5] >= 5f) 1f else 0f
        val isNearHome   = if (rawNumeric8[0] < 100f) 1f else 0f
        val isNearOffice = if (rawNumeric8[1] < 100f) 1f else 0f
        val xPrev = oneHotPrevState(prevState, meta)
        val totalDim = 8 + 3 + xPrev.size
        val x = FloatArray(totalDim)
        System.arraycopy(xNum, 0, x, 0, 8)
        x[8] = isWeekend; x[9] = isNearHome; x[10] = isNearOffice
        System.arraycopy(xPrev, 0, x, 11, xPrev.size)
        val buf = ByteBuffer.allocateDirect(4 * totalDim).order(ByteOrder.nativeOrder())
        x.forEach { buf.putFloat(it) }; buf.rewind()
        val output = Array(1) { FloatArray(meta.class_names.size) }
        tflite.run(buf, output)
        return output[0]
    }

    private fun normalizeNumeric(raw: FloatArray, meta: ModelMeta): FloatArray {
        val o = raw.copyOf()
        o[0]/=meta.dist_scale_m; o[1]/=meta.dist_scale_m
        o[2]/=meta.delta_dist_scale_m; o[3]/=meta.delta_dist_scale_m
        o[4]/=meta.hour_scale; o[5]/=meta.weekday_scale
        o[6]/=meta.movement_scale_m; o[7]/=meta.dwell_scale_s
        for (i in o.indices) o[i] = clamp(o[i], -5f, 5f)
        return o
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    private fun oneHotPrevState(prevState: String, meta: ModelMeta): FloatArray {
        val c = meta.prev_state_classes
        val idx = c.indexOf(prevState).let { if (it >= 0) it else c.indexOf("NONE").coerceAtLeast(0) }
        val o = FloatArray(c.size); if (idx in o.indices) o[idx] = 1f; return o
    }

    private fun loadMetaFromAssets(f: String): ModelMeta {
        val json = assets.open(f).bufferedReader().use(BufferedReader::readText)
        return Gson().fromJson(json, ModelMeta::class.java)
    }

    private fun loadModelFileFromAssets(f: String): ByteBuffer {
        assets.openFd(f).use { afd ->
            val ch = afd.createInputStream().channel
            return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    data class ScenarioStep(val stepNo: Int, val title: String, val rawNumeric8: FloatArray, val prevOverride: String?, val journeySection: String)
    data class ModelMeta(val schema_version: String, val features_numeric_order: List<String>, val prev_state_classes: List<String>, val class_names: List<String>, val dist_scale_m: Float, val delta_dist_scale_m: Float, val hour_scale: Float, val weekday_scale: Float, val movement_scale_m: Float, val dwell_scale_s: Float)
}
