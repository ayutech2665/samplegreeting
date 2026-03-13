package com.kpit.samplegreeting

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "Main"
        private const val SERVER_URL = "https://ayush2665.pythonanywhere.com/"
        private const val DEVICE_ID = "infotainment_001"
    }

    private lateinit var tvStep: TextView; private lateinit var tvSection: TextView
    private lateinit var tvBadge: TextView; private lateinit var tvTitle: TextView
    private lateinit var tvContext: TextView; private lateinit var tvGreet: TextView
    private lateinit var tvConf: TextView; private lateinit var tvSource: TextView
    private lateinit var loading: View; private lateinit var suggCard: CardView
    private lateinit var tvApp: TextView; private lateinit var tvMsg: TextView
    private lateinit var tvNone: TextView; private lateinit var btnYes: Button
    private lateinit var btnNo: Button; private lateinit var btnDetail: Button
    private lateinit var detailCard: CardView; private lateinit var tvDetail: TextView
    private lateinit var btnPrev: Button; private lateinit var btnNext: Button
    private lateinit var btnSync: Button; private lateinit var btnReset: Button
    private lateinit var scroll: ScrollView

    private var ctxModel: Interpreter? = null; private var appModel: Interpreter? = null
    private lateinit var meta: Meta; private lateinit var fb: AppFeedbackManager
    private var cur = 0; private var animating = false; private var modelV = 1
    private val usedGreetings = mutableSetOf<String>()
    data class CR(val s: Step, val l: String, val c: Float, val p: FloatArray, val prev: String, val g: String)
    private val results = mutableListOf<CR>()
    private val appDisp = mapOf(
        "commute_to_work" to Pair("🏢 Commute to Work","Open commute assistant for office?"),
        "commute_to_home" to Pair("🏡 Commute to Home","Open commute assistant for home?"),
        "navigation" to Pair("🗺️ Navigation","Open navigation for this trip?"),
        "music" to Pair("🎵 Music","Play some music?"),
        "anti_stress" to Pair("🧘 Anti-Stress","Long wait — open anti-stress?"),
        "mespace" to Pair("⭐ MeSpace","Set preferences with MeSpace?"),
        "none" to Pair("",""))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); setContentView(R.layout.activity_main); bind()
        meta = Gson().fromJson(assets.open("context_model_metadata_v2.json").bufferedReader().use(BufferedReader::readText), Meta::class.java)
        fb = AppFeedbackManager(this); loadModels(); compute(); show(0, true)

        btnNext.setOnClickListener { if (!animating && cur < results.size-1) { cur++; show(cur, true) } }
        btnPrev.setOnClickListener { if (!animating && cur > 0) { cur--; show(cur, true) } }
        btnDetail.setOnClickListener {
            if (detailCard.visibility == View.GONE) { detailCard.visibility = View.VISIBLE; detailCard.alpha = 0f
                detailCard.animate().alpha(1f).setDuration(200).start(); btnDetail.text = "▼ Hide details"
            } else { detailCard.visibility = View.GONE; btnDetail.text = "▶ More details" }
        }
        btnYes.setOnClickListener { val cr=results[cur]; val a=live(cur)
            if (a.l!="none") { val r=cr.s.r; fb.recordYes(cr.l,a.l,r[4].toInt(),r[5].toInt(),cr.prev,r[7].toInt()) }
            btnYes.isEnabled=false; btnNo.isEnabled=false; btnYes.text="✓ Opened"; badge(); detail() }
        btnNo.setOnClickListener { val cr=results[cur]; val a=live(cur)
            if (a.l!="none") { val r=cr.s.r; fb.recordNo(cr.l,a.l,r[4].toInt(),r[5].toInt(),cr.prev,r[7].toInt()) }
            btnYes.isEnabled=false; btnNo.isEnabled=false; btnNo.text="✓ Dismissed"; badge(); detail() }
        btnSync.setOnClickListener { sync() }
        btnReset.setOnClickListener { reset() }
        badge()
    }

    override fun onDestroy() { super.onDestroy(); ctxModel?.close(); appModel?.close() }

    private fun loadModels() {
        ctxModel?.close(); appModel?.close()
        ctxModel = Interpreter(loadAsset("context_classifier_v2.tflite"))
        val local = File(filesDir, "app_suggestion_v1.tflite")
        if (local.exists() && local.length() > 100) {
            try {
                val buf = ByteBuffer.allocateDirect(local.length().toInt()).order(ByteOrder.nativeOrder())
                FileInputStream(local).channel.use { it.read(buf); buf.rewind() }
                appModel = Interpreter(buf)
                modelV = getSharedPreferences("mp", MODE_PRIVATE).getInt("v", 1)
                Log.d(TAG, "Loaded retrained v$modelV")
            } catch (e: Exception) { local.delete(); appModel = Interpreter(loadAsset("app_suggestion_v1.tflite")); modelV = 1 }
        } else { appModel = Interpreter(loadAsset("app_suggestion_v1.tflite")); modelV = 1 }
    }

    private fun sync() {
        val n = fb.getLogCount(); if (n==0) { Toast.makeText(this,"No feedback",Toast.LENGTH_SHORT).show(); return }
        btnSync.isEnabled=false; btnSync.text="⏳"
        lifecycleScope.launch {
            try {
                val json = fb.buildExportJson(DEVICE_ID)
                val resp = withContext(Dispatchers.IO) { post("$SERVER_URL/api/feedback", json) } ?: throw Exception("Server unreachable")
                val j = org.json.JSONObject(resp)
                if (j.getString("status")!="ok") throw Exception(j.optString("message","Error"))
                val nv = j.getInt("model_version")
                val bytes = withContext(Dispatchers.IO) { get("$SERVER_URL/api/model") } ?: throw Exception("Download failed")
                File(filesDir,"app_suggestion_v1.tflite").writeBytes(bytes)
                getSharedPreferences("mp",MODE_PRIVATE).edit().putInt("v",nv).apply()
                fb.clearAfterSync(); loadModels()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity,"✅ v$nv",Toast.LENGTH_LONG).show(); show(cur,false); badge() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { AlertDialog.Builder(this@MainActivity).setTitle("Sync Failed").setMessage(e.message).setPositiveButton("OK",null).show() }
            } finally { withContext(Dispatchers.Main) { btnSync.isEnabled=true; btnSync.text="☁️ Sync" } }
        }
    }

    private fun reset() {
        AlertDialog.Builder(this).setTitle("Reset").setMessage("Restore original model v1?\nAll feedback will be cleared.")
            .setPositiveButton("Reset") { _,_ ->
                btnReset.isEnabled=false
                lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { post("$SERVER_URL/api/reset","{}") }
                        val bytes = withContext(Dispatchers.IO) { get("$SERVER_URL/api/model") }
                        if (bytes!=null) File(filesDir,"app_suggestion_v1.tflite").writeBytes(bytes)
                        else File(filesDir,"app_suggestion_v1.tflite").delete()
                    } catch (_: Exception) { File(filesDir,"app_suggestion_v1.tflite").delete() }
                    getSharedPreferences("mp",MODE_PRIVATE).edit().putInt("v",1).apply()
                    fb.resetAll(); loadModels()
                    withContext(Dispatchers.Main) { show(cur,false); badge(); btnReset.isEnabled=true
                        Toast.makeText(this@MainActivity,"Reset to v1 ✓",Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun post(url: String, json: String): String? = try {
        val c=URL(url).openConnection() as HttpURLConnection; c.requestMethod="POST"
        c.setRequestProperty("Content-Type","application/json"); c.doOutput=true; c.connectTimeout=30000; c.readTimeout=120000
        c.outputStream.use{it.write(json.toByteArray())}; if(c.responseCode==200) c.inputStream.bufferedReader().readText() else null
    } catch(e:Exception){null}

    private fun get(url: String): ByteArray? = try {
        val c=URL(url).openConnection() as HttpURLConnection; c.connectTimeout=15000; c.readTimeout=30000
        if(c.responseCode==200) c.inputStream.readBytes() else null
    } catch(e:Exception){null}

    // ══ LIVE APP SUGGESTION with STRICT VALIDATION ══
    data class AR(val l: String, val c: Float, val src: String)
    private fun live(i: Int): AR {
        val cr=results[i]; val r=cr.s.r
        val raw = predApp(cr.l,r[4].toInt(),r[5],r[5]>=5f,cr.prev,r[7],cr.prev=="NONE")
        // STRICT: adjustAndValidate enforces context-valid apps only
        val (adj, wasAdj) = fb.adjustAndValidate(cr.l, raw, meta.app_classes)
        val bi=adj.indices.maxBy{adj[it]}
        val src=when{wasAdj->"On-device learning";modelV>1->"Retrained model (v$modelV)";else->"Original model (v1)"}
        return AR(meta.app_classes[bi], adj[bi], src)
    }

    private fun compute() { results.clear(); usedGreetings.clear(); var p="NONE"
        for(s in steps()){if(s.po!=null)p=s.po; val pr=predCtx(s.r,p); val i=pr.indices.maxBy{pr[it]}
            val l=meta.class_names[i]; results.add(CR(s,l,pr[i],pr,p,greet(l,pr[i],s.r[4].toInt(),p))); p=l} }

    private fun show(i: Int, anim: Boolean) {
        animating=anim; val cr=results[i]
        btnPrev.isEnabled=i>0; btnNext.isEnabled=i<results.size-1
        tvStep.text="Step ${i+1} / ${results.size}"; tvSection.text=cr.s.j
        detailCard.visibility=View.GONE; btnDetail.text="▶ More details"
        suggCard.visibility=View.GONE; tvNone.visibility=View.GONE; loading.visibility=View.GONE
        btnYes.isEnabled=true;btnNo.isEnabled=true;btnYes.text="  Yes  ▶  ";btnNo.text="  No thanks  "
        tvTitle.text=cr.s.t; tvContext.text="${emoji(cr.l)}  ${cr.l}"; tvGreet.text=cr.g
        tvConf.text="Confidence: ${"%.3f".format(cr.c)}"; scroll.scrollTo(0,0); detail()
        if(anim){lifecycleScope.launch{delay(300);loading.visibility=View.VISIBLE;loading.alpha=0f
            loading.animate().alpha(1f).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator()).start()
            delay(1000);loading.animate().alpha(0f).setDuration(150).setListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(a:Animator){loading.visibility=View.GONE}}).start()
            delay(200);showSugg(i);animating=false}
        }else{showSugg(i);animating=false}
    }

    private fun showSugg(i: Int) {
        val a = live(i)
        tvSource.text = "Source: ${a.src}"

        if (a.l != "none" && a.c > 0.01f) {
            val d = appDisp[a.l] ?: Pair(a.l, "Open?")
            tvApp.text = d.first
            tvMsg.text = d.second
            suggCard.visibility = View.VISIBLE
            suggCard.alpha = 0f
            suggCard.translationY = 20f
            suggCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            tvNone.text =
                if (fb.hasAnyFeedback())
                    "Suppressed by feedback.\nSync to make permanent."
                else
                    "No suggestion for this context."

            tvNone.visibility = View.VISIBLE
        }
    }

    private fun detail(){if(cur>=results.size)return;val cr=results[cur];val a=live(cur)
        val t3=cr.p.indices.sortedByDescending{cr.p[it]}.take(3).joinToString("\n"){"  ${meta.class_names[it]}:${"%.4f".format(cr.p[it])}"}
        tvDetail.text=buildString{append("── Context ──\nPrev:${cr.prev}\nPred:${cr.l}\nTop3:\n$t3\n\n")
            append("── App ──\nSuggested:${a.l} (${"%.3f".format(a.c)})\nSource:${a.src}\nModel:v$modelV\n")
            val valid=AppFeedbackManager.VALID_APPS[cr.l]?:emptyList()
            append("Valid apps:$valid\n")
            if(a.l!="none"){val f=fb.getFeedbackSummary(cr.l,a.l);if(f.isNotEmpty())append("Feedback:$f\n")}
            append("\nPending:${fb.getLogCount()} Model:v$modelV")}}

    private fun badge(){tvBadge.text=if(fb.getLogCount()>0)"${fb.getLogCount()} pending" else "v$modelV"}

    // TFLite
    private fun predCtx(r:FloatArray,ps:String):FloatArray{val n=norm(r);val p=oh(ps);val d=8+3+p.size;val x=FloatArray(d)
        System.arraycopy(n,0,x,0,8);x[8]=if(r[5]>=5f)1f else 0f;x[9]=if(r[0]<100f)1f else 0f;x[10]=if(r[1]<100f)1f else 0f
        System.arraycopy(p,0,x,11,p.size);val b=ByteBuffer.allocateDirect(4*d).order(ByteOrder.nativeOrder())
        x.forEach{b.putFloat(it)};b.rewind();val o=Array(1){FloatArray(meta.class_names.size)};ctxModel?.run(b,o);return o[0]}

    private fun predApp(ctx:String,hr:Int,wd:Float,wk:Boolean,ps:String,dw:Float,fb:Boolean):FloatArray{
        val co=FloatArray(meta.class_names.size);val ci=meta.class_names.indexOf(ctx);if(ci>=0)co[ci]=1f
        val po=oh(ps);val d=co.size+3+po.size+2;val x=FloatArray(d);var i=0
        System.arraycopy(co,0,x,i,co.size);i+=co.size;x[i++]=hr/23f;x[i++]=wd/6f;x[i++]=if(wk)1f else 0f
        System.arraycopy(po,0,x,i,po.size);i+=po.size;x[i++]=dw/3600f;x[i]=if(fb)1f else 0f
        val b=ByteBuffer.allocateDirect(4*d).order(ByteOrder.nativeOrder());x.forEach{b.putFloat(it)};b.rewind()
        val o=Array(1){FloatArray(meta.app_classes.size)};appModel?.run(b,o);return o[0]}

    private fun norm(r:FloatArray):FloatArray{val o=r.copyOf();o[0]/=meta.ds;o[1]/=meta.ds;o[2]/=meta.dd;o[3]/=meta.dd
        o[4]/=meta.hs;o[5]/=meta.ws;o[6]/=meta.ms;o[7]/=meta.dws;for(i in o.indices)o[i]=max(-5f,min(5f,o[i]));return o}
    private fun oh(ps:String):FloatArray{val c=meta.prev_state_classes;val i=c.indexOf(ps).let{if(it>=0)it else c.indexOf("NONE").coerceAtLeast(0)}
        val o=FloatArray(c.size);if(i in o.indices)o[i]=1f;return o}

    // Greetings
    private fun greet(l:String,c:Float,h:Int,p:String):String{
        val t=when(h){in 5..11->"Good morning";in 12..16->"Good afternoon";in 17..20->"Good evening";else->"Late night"}
        val pool=gp(l,t,p=="NONE");val a=pool.filter{it !in usedGreetings};val pick=if(a.isNotEmpty())a.random() else pool.random();usedGreetings.add(pick);return pick}
    private fun gp(l:String,t:String,b:Boolean)=when(l){
        "IDLE_HOME"->{val r=listOf("$t! Relaxing at home.","$t! Home sweet home.","$t! Home mode.","$t! Cozy at home.","$t from home!","$t! Nothing beats home.","$t! You're home.","$t! Settled at home.")
            if(b)r+listOf("$t! Vehicle started at home.","$t! System initialized.","$t! Welcome from home!")else r}
        "COMMUTING_TO_WORK"->listOf("Heading to office!","On the way to work!","Commute started!","Work-bound!","Off to the office!","Journey to work!","Heading to work!","Office commute!")
        "ARRIVED_WORK"->listOf("Reached the office!","Welcome to work!","Office — within 10m!","Arrived at work!","Parked at office!","Made it!","Office confirmed!","At the office!")
        "IDLE_WORK"->listOf("$t! Settled at office.","$t! Work mode.","$t! Deep work.","$t from office!","At office. $t!","$t! Stay productive.","$t! Momentum!","Settled at work. $t!")
        "COMMUTING_HOME"->listOf("Heading home!","On the way home!","Home-bound!","Commuting home!","Going home!","Home calling!","Return journey!","Making way home!")
        "ARRIVED_HOME"->listOf("Welcome home!","Reached home!","Home again!","Back home!","Home sweet home!","Arrived safely!","You're home!","Home arrival!")
        "STOPPED_EN_ROUTE"->listOf("Quick stop.","Paused. Traffic?","Short stop.","Traffic stop.","Stopped briefly.","Journey paused.","Signal detected.","Brief halt.","Traffic hold.","Almost through!")
        "COMMUTING_OUTING"->listOf("Heading somewhere fun!","Outing mode!","Off on an adventure!","Going exciting?","Leisure drive!","Heading out!","Non-work trip!","Road trip vibes!")
        "OUTING_IDLE"->listOf("Hope you had a great time!","What a visit!","Good stop!","Done exploring?","Hope you enjoyed!","Break's over!","All done?","Great stop!","Wonderful outing!","That was fun!")
        else->listOf("Context: $l")}
    private fun emoji(l:String)=when(l){"IDLE_HOME"->"🏠";"COMMUTING_TO_WORK"->"🚗";"ARRIVED_WORK"->"🏢";"IDLE_WORK"->"💼"
        "COMMUTING_HOME"->"🏡";"ARRIVED_HOME"->"🎉";"STOPPED_EN_ROUTE"->"🚦";"COMMUTING_OUTING"->"🛣️";"OUTING_IDLE"->"✅";else->"📌"}

    private fun steps()=listOf(
        Step(1,"Initial State — Home (7 AM)",f(10f,2000f,0f,0f,7f,4f,0f,900f),"NONE","🏠 FRI MORNING"),
        Step(2,"Commute to office (7:15 AM)",f(800f,1200f,300f,-300f,7f,4f,700f,0f),null,"🏠 FRI MORNING"),
        Step(3,"Waiting 2 min at signal",f(1100f,900f,0f,0f,7f,4f,0f,120f),null,"🏠 FRI MORNING"),
        Step(4,"Waiting 15 min in traffic",f(1200f,800f,0f,0f,7f,4f,0f,900f),null,"🏠 FRI MORNING"),
        Step(5,"Reached office — 10m",f(2000f,8f,0f,0f,8f,4f,0f,60f),null,"🏢 OFFICE"),
        Step(6,"Settled at office (1 PM)",f(2000f,10f,0f,0f,13f,4f,0f,3600f),null,"🏢 OFFICE"),
        Step(7,"Leave office to home (2 PM)",f(1200f,800f,-300f,300f,14f,4f,800f,0f),null,"🏡 AFTERNOON"),
        Step(8,"Waiting 2 min signal",f(1000f,1000f,0f,0f,14f,4f,0f,120f),null,"🏡 AFTERNOON"),
        Step(9,"Vehicle ON after 1hr cafe",f(900f,1200f,0f,0f,15f,4f,0f,3600f),null,"☕ CAFE"),
        Step(10,"Commute home (3:20 PM)",f(400f,1600f,-200f,150f,15f,4f,600f,0f),null,"🏡 HOME"),
        Step(11,"Reached home (3:45 PM)",f(10f,2000f,-200f,200f,15f,4f,0f,60f),null,"🏡 ARRIVED"),
        Step(12,"Settled at home (4:30 PM)",f(10f,2000f,0f,0f,16f,4f,0f,2700f),null,"🏡 AT HOME"),
        Step(13,"Commute to work (7 PM)",f(800f,1200f,300f,-300f,19f,4f,700f,0f),null,"🌆 EVENING"),
        Step(14,"Waiting 5 min traffic",f(1200f,800f,0f,0f,19f,4f,0f,300f),null,"🌆 EVENING"),
        Step(15,"Reached office (7:45 PM)",f(2000f,8f,0f,0f,19f,4f,0f,60f),null,"🏢 OFFICE"),
        Step(16,"Settled at office (8:30 PM)",f(2000f,10f,0f,0f,20f,4f,0f,2700f),null,"🌃 NIGHT"),
        Step(17,"Leave for mall (9 PM)",f(1800f,800f,200f,300f,21f,4f,700f,0f),null,"🛍️ OUTING"),
        Step(18,"Waiting 5 min traffic",f(2200f,1200f,0f,0f,21f,4f,0f,300f),null,"🛍️ OUTING"),
        Step(19,"Continuing to mall",f(2800f,1800f,300f,300f,21f,4f,900f,0f),null,"🛍️ OUTING"),
        Step(20,"Vehicle ON 5hr mall",f(3000f,2000f,0f,0f,22f,4f,0f,12600f),null,"🛍️ MALL"),
        Step(21,"Still at mall 1AM",f(3000f,2000f,0f,0f,1f,5f,0f,12600f),null,"🌙 LATE"),
        Step(22,"Commute home (1:05AM)",f(2000f,2500f,-400f,200f,1f,5f,900f,0f),null,"🌙 LATE"),
        Step(23,"Waiting 10 min traffic",f(1000f,2000f,0f,0f,1f,5f,0f,600f),null,"🌙 LATE"),
        Step(24,"Reached home (1:30AM)",f(10f,2000f,-400f,200f,1f,5f,0f,60f),null,"🌙 HOME"),
        Step(25,"Settled — Sat (9AM)",f(10f,2000f,0f,0f,9f,5f,0f,1800f),null,"☀️ SAT"),
        Step(26,"Commute outing (9:30)",f(600f,2500f,200f,150f,9f,5f,700f,0f),null,"🌳 OUTING"),
        Step(27,"Vehicle ON 5hr spot",f(4000f,5000f,0f,0f,15f,5f,0f,18000f),null,"🌳 SPOT"),
        Step(28,"Commute home (3:30PM)",f(2500f,3500f,-500f,-200f,15f,5f,900f,0f),null,"🌳 HOME"),
        Step(29,"Reached home (4PM)",f(10f,2000f,-400f,200f,16f,5f,0f,60f),null,"🌳 ARRIVED"))
    private fun f(vararg v:Float)=v
    data class Step(val n:Int,val t:String,val r:FloatArray,val po:String?,val j:String)

    private fun bind(){tvStep=findViewById(R.id.tvStepIndicator);tvSection=findViewById(R.id.tvJourneySection)
        tvBadge=findViewById(R.id.tvFeedbackCount);tvTitle=findViewById(R.id.tvConditionTitle)
        tvContext=findViewById(R.id.tvPredictedContext);tvGreet=findViewById(R.id.tvGreeting)
        tvConf=findViewById(R.id.tvConfidence);tvSource=findViewById(R.id.tvSource)
        loading=findViewById(R.id.loadingContainer);suggCard=findViewById(R.id.suggestionCard)
        tvApp=findViewById(R.id.tvSuggestedApp);tvMsg=findViewById(R.id.tvSuggestionMsg)
        tvNone=findViewById(R.id.tvNoSuggestion);btnYes=findViewById(R.id.btnYes);btnNo=findViewById(R.id.btnNo)
        btnDetail=findViewById(R.id.btnMoreDetails);detailCard=findViewById(R.id.detailsCard)
        tvDetail=findViewById(R.id.tvDetails);btnPrev=findViewById(R.id.btnPrev);btnNext=findViewById(R.id.btnNext)
        btnSync=findViewById(R.id.btnSync);btnReset=findViewById(R.id.btnReset);scroll=findViewById(R.id.scrollView)}
    private fun loadAsset(f:String):ByteBuffer{assets.openFd(f).use{a->return a.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY,a.startOffset,a.declaredLength)}}

    data class Meta(val schema_version:String,val features_numeric_order:List<String>,val prev_state_classes:List<String>,
        val class_names:List<String>,val app_classes:List<String>,
        val dist_scale_m:Float,val delta_dist_scale_m:Float,val hour_scale:Float,
        val weekday_scale:Float,val movement_scale_m:Float,val dwell_scale_s:Float){
        val ds get()=dist_scale_m;val dd get()=delta_dist_scale_m;val hs get()=hour_scale
        val ws get()=weekday_scale;val ms get()=movement_scale_m;val dws get()=dwell_scale_s}
}
