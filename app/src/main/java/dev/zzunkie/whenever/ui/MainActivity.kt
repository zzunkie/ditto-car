package dev.zzunkie.whenever.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.zzunkie.whenever.car.Listener
import dev.zzunkie.whenever.car.TAG
import dev.zzunkie.whenever.engine.Engine
import dev.zzunkie.whenever.engine.RuleService
import dev.zzunkie.whenever.engine.Store
import dev.zzunkie.whenever.parse.Interpreter
import dev.zzunkie.whenever.parse.ParseResult
import dev.zzunkie.whenever.rule.GearSel
import dev.zzunkie.whenever.rule.Rule
import dev.zzunkie.whenever.rule.RuleDraft
import dev.zzunkie.whenever.rule.RuleJson
import dev.zzunkie.whenever.rule.Samples
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { home, browse, log }

/** 확인 화면에 올릴 초안. origin 은 어디서 왔는지(말, 예시, 넘겨받음) */
data class Pending(val draft: RuleDraft, val spoken: String, val origin: String, val editingId: String? = null)

class MainActivity : ComponentActivity() {
    var screen by mutableStateOf(Screen.home)
    /** 확인 화면 대기열 (예시 묶음은 규칙이 여럿일 수 있음) */
    val confirmQueue = mutableStateListOf<Pending>()
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var shareRule by mutableStateOf<Rule?>(null)
    var input by mutableStateOf("")

    private var listener: Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        RuleService.start(this)
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, "pleos.car.permission.FUSED_LOCATION")
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)

        listener = Listener(this, ::onListenState) { text -> runOnUiThread { listening = false; submit(text, spoken = true) } }
            .also { it.start() }

        setContent { WheneverTheme { App(this) } }

        // 주행 중에 받아 둔 문장: 주차하면 해석해서 확인 화면을 연다
        lifecycleScope.launch {
            combine(Engine.carState, Engine.pendingText) { st, p -> st.gear to p }.collect { (gear, p) ->
                if (p != null && gear == GearSel.P) {
                    Engine.pendingText.value = null
                    interpret(p)
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * 시연 스크립트와 QR 이 쓰는 입구.
     *  - whenever://rule?d=...  넘겨받은 규칙 (확인 화면을 거쳐야 저장됨)
     *  - extra "text": 글 입력과 같다 (에뮬레이터 마이크에 소리를 넣을 수 없어 시연에서 쓴다)
     *  - extra "screen": home | browse | log
     *  - extra "pick": 둘러보기 예시 제목 (확인 화면을 연다)
     *  - extra "route_to": home | work  저장 장소로 길 안내 시작 (시연 준비용)
     */
    private fun handle(i: Intent?) {
        i ?: return
        i.data?.let { importUri(it) }
        i.getStringExtra("screen")?.let { s -> Screen.entries.firstOrNull { it.name == s }?.let { screen = it; confirmQueue.clear() } }
        i.getStringExtra("pick")?.let { title -> Samples.bundles.firstOrNull { it.title == title }?.let { pickBundle(it) } }
        i.getStringExtra("route_to")?.let { p -> dev.zzunkie.whenever.rule.Place.entries.firstOrNull { it.name == p }?.let { Engine.navigateTo(it) } }
        i.getStringExtra("text")?.let { submit(it, spoken = i.getBooleanExtra("spoken", true)) }
        setIntent(Intent())
    }

    private fun importUri(u: Uri) {
        if (u.scheme != "whenever") return
        val d = u.getQueryParameter("d") ?: return
        runCatching {
            val json = String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP))
            RuleJson.decodeFromString(RuleDraft.serializer(), json)
        }.onSuccess {
            message = null
            confirmQueue.clear()
            confirmQueue += Pending(it, "", "넘겨받은 자동화")
        }.onFailure { message = "넘겨받은 자동화를 읽지 못했어요." }
    }

    fun pickBundle(b: dev.zzunkie.whenever.rule.Bundle) {
        message = null
        confirmQueue.clear()
        b.rules.forEach { confirmQueue += Pending(it, "", "둘러보기: ${b.title}") }
    }

    private fun onListenState(s: String) = runOnUiThread {
        when {
            s == "ready" || s == "listening" -> { listening = true; message = "듣고 있어요. 이럴 땐 이렇게 해 달라고 말해 주세요." }
            s.startsWith("partial:") -> message = s.removePrefix("partial:")
            s == "error" -> { listening = false; message = "음성 인식을 시작하지 못했어요. 글로 써 주셔도 돼요." }
            s == "ended" -> listening = false
        }
    }

    fun startListening() {
        message = null
        listening = true
        listener?.listen()
    }

    val driving get() = Engine.carState.value.gear.let { it == GearSel.D || it == GearSel.R }

    /** 말이나 글로 받은 문장. 주행 중이면 받아 두기만 한다. */
    fun submit(text: String, spoken: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        Log.i(TAG, "submit '$t' spoken=$spoken")
        if (driving) {
            Engine.pendingText.value = t
            Engine.say("받아 뒀어요. 주차하면 확인할게요")
            message = "\"$t\"\n받아 뒀어요. 주차하면 확인 화면을 보여 드릴게요."
            return
        }
        interpret(t)
    }

    private fun interpret(t: String) {
        busy = true
        message = "\"$t\" 해석하는 중이에요."
        lifecycleScope.launch {
            val r = Interpreter.interpret(this@MainActivity, t)
            busy = false
            when (r) {
                is ParseResult.Ok -> {
                    message = null
                    input = ""
                    confirmQueue.clear()
                    confirmQueue += Pending(r.draft, t, r.by)
                    screen = Screen.home
                }
                is ParseResult.CannotDo -> { message = "\"$t\"\n${r.message}"; Engine.say(r.message) }
                is ParseResult.NotUnderstood -> message = "\"$t\"\n${r.message}"
            }
        }
    }

    fun save(p: Pending, draft: RuleDraft) {
        val rule = Rule(
            id = p.editingId ?: UUID.randomUUID().toString(),
            trigger = draft.trigger, conditions = draft.conditions, actions = draft.actions,
            spoken = p.spoken, createdAt = System.currentTimeMillis(),
        )
        Store.saveRule(rule)
        confirmQueue.remove(p)
        if (confirmQueue.isEmpty()) {
            message = "저장했어요. 이제 조건이 맞으면 알아서 실행해요."
            screen = Screen.home
        }
    }

    fun tryNow(draft: RuleDraft) {
        Engine.runNow(Rule("try-" + UUID.randomUUID(), draft.trigger, draft.conditions, draft.actions, createdAt = System.currentTimeMillis()))
        message = "지금 실행했어요. 결과는 실행 기록에서 볼 수 있어요."
    }

    fun shareUri(r: Rule): String {
        val json = RuleJson.encodeToString(RuleDraft.serializer(), r.draft)
        return "whenever://rule?d=" + Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    override fun onDestroy() {
        listener?.release()
        super.onDestroy()
    }
}
