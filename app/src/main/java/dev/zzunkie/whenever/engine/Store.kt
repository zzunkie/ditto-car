package dev.zzunkie.whenever.engine

import android.content.Context
import dev.zzunkie.whenever.rule.Rule
import dev.zzunkie.whenever.rule.RuleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
enum class Outcome { done, deferred, done_after_park, cancelled, failed }

@Serializable
data class ActionRun(val text: String, val outcome: Outcome, val note: String = "", val at: Long = System.currentTimeMillis())

@Serializable
data class RunRecord(
    val id: String,
    val ruleId: String,
    val ruleText: String,
    val cause: String,
    val at: Long,
    val actions: List<ActionRun>,
    val manual: Boolean = false,
)

/** 규칙과 실행 기록을 앱 내부 JSON 파일에 둔다. 서버 없음. */
object Store {
    private lateinit var dir: File
    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules
    val runs: StateFlow<List<RunRecord>> = _runs
    private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        dir = ctx.filesDir
        _rules.value = read("rules.json", ListSerializer(Rule.serializer())) ?: emptyList()
        _runs.value = read("runs.json", ListSerializer(RunRecord.serializer())) ?: emptyList()
        loaded = true
    }

    private fun <T> read(name: String, s: kotlinx.serialization.KSerializer<T>): T? = runCatching {
        File(dir, name).takeIf { it.exists() }?.readText()?.let { RuleJson.decodeFromString(s, it) }
    }.getOrNull()

    private fun <T> write(name: String, s: kotlinx.serialization.KSerializer<T>, v: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(RuleJson.encodeToString(s, v))
        tmp.renameTo(File(dir, name))
    }

    @Synchronized
    fun saveRule(r: Rule) {
        val list = _rules.value.filterNot { it.id == r.id } + r
        _rules.value = list.sortedBy { it.createdAt }
        write("rules.json", ListSerializer(Rule.serializer()), _rules.value)
    }

    @Synchronized
    fun deleteRule(id: String) {
        _rules.value = _rules.value.filterNot { it.id == id }
        write("rules.json", ListSerializer(Rule.serializer()), _rules.value)
    }

    fun setEnabled(id: String, on: Boolean) {
        _rules.value.firstOrNull { it.id == id }?.let { saveRule(it.copy(enabled = on)) }
    }

    @Synchronized
    fun putRun(r: RunRecord) {
        _runs.value = (listOf(r) + _runs.value.filterNot { it.id == r.id }).sortedByDescending { it.at }.take(200)
        write("runs.json", ListSerializer(RunRecord.serializer()), _runs.value)
    }

    fun run(id: String) = _runs.value.firstOrNull { it.id == id }

    @Synchronized
    fun clearRuns() {
        _runs.value = emptyList()
        write("runs.json", ListSerializer(RunRecord.serializer()), _runs.value)
    }
}
