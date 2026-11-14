package dev.zzunkie.whenever.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.zzunkie.whenever.engine.Engine
import dev.zzunkie.whenever.engine.Outcome
import dev.zzunkie.whenever.engine.RunRecord
import dev.zzunkie.whenever.engine.Store
import dev.zzunkie.whenever.rule.Action
import dev.zzunkie.whenever.rule.Edit
import dev.zzunkie.whenever.rule.GearSel
import dev.zzunkie.whenever.rule.Phrase
import dev.zzunkie.whenever.rule.Rule
import dev.zzunkie.whenever.rule.RuleDraft
import dev.zzunkie.whenever.rule.Samples
import dev.zzunkie.whenever.rule.Validator
import dev.zzunkie.whenever.rule.needsPark
import dev.zzunkie.whenever.rule.josa
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun App(a: MainActivity) {
    val car by Engine.carState.collectAsState()
    val driving = car.gear == GearSel.D || car.gear == GearSel.R
    Row(Modifier.fillMaxSize().background(C.bg)) {
        Rail(a, driving)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val pending = a.confirmQueue.firstOrNull()
            when {
                pending != null && driving -> DrivingLock()
                pending != null -> Confirm(a, pending)
                a.screen == Screen.home -> Home(a, driving)
                a.screen == Screen.browse -> Browse(a)
                else -> RunLog()
            }
        }
    }
    a.shareRule?.let { ShareDialog(a, it) }
}

@Composable
private fun Rail(a: MainActivity, driving: Boolean) {
    Column(
        Modifier.width(330.dp).fillMaxHeight().background(C.rail).padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ditto", style = T.title, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        Text("말해 두면, 알아서", style = T.small, modifier = Modifier.padding(start = 20.dp, bottom = 32.dp))
        for ((s, label) in listOf(Screen.home to "내 자동화", Screen.browse to "둘러보기", Screen.log to "실행 기록")) {
            val sel = a.screen == s && a.confirmQueue.isEmpty()
            Box(
                Modifier.fillMaxWidth().height(TOUCH).clip(RoundedCornerShape(20.dp))
                    .background(if (sel) C.primarySoft else Color.Transparent)
                    .clickable { a.confirmQueue.clear(); a.screen = s; a.message = null }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, style = T.head.copy(color = if (sel) C.primary else C.ink, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (driving) C.wait else C.ok))
            Spacer(Modifier.width(14.dp))
            Text(if (driving) "주행 중" else "주차 중", style = T.small.copy(color = C.ink))
        }
    }
}

// ---------------- 내 자동화 ----------------

@Composable
private fun Home(a: MainActivity, driving: Boolean) {
    val rules by Store.rules.collectAsState()
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 48.dp)) {
        Text("내 자동화", style = T.title)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicButton(a.listening) { a.startListening() }
            Spacer(Modifier.width(28.dp))
            if (driving) {
                Text("주행 중에는 말로만 만들 수 있어요. 주차하면 확인 화면을 보여 드려요.", style = T.body.copy(color = C.sub), modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = a.input, onValueChange = { a.input = it },
                    modifier = Modifier.weight(1f).heightIn(min = TOUCH),
                    textStyle = T.body,
                    placeholder = { Text("이럴 땐 이렇게 해 줘", style = T.body.copy(color = C.off)) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { a.submit(a.input, spoken = false) }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.primary),
                )
                Spacer(Modifier.width(20.dp))
                BigButton("만들기", enabled = a.input.isNotBlank() && !a.busy) { a.submit(a.input, spoken = false) }
            }
        }
        a.message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, style = T.body.copy(color = C.sub))
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = C.line)
        if (rules.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text("아직 만든 자동화가 없어요.", style = T.head)
            Spacer(Modifier.height(12.dp))
            Text("마이크를 누르고 \"충전을 시작하면 무드등을 어둡게 해 줘\"처럼 말하거나, 둘러보기에서 골라 보세요.", style = T.body.copy(color = C.sub))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rules, key = { it.id }) { r -> RuleRow(a, r, driving) }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(120.dp).clip(CircleShape).background(if (listening) C.bad else C.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MicGlyph(Color.White, 56.dp) }
}

@Composable
private fun RuleRow(a: MainActivity, r: Rule, driving: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Phrase.sentence(r), style = T.body.copy(color = if (r.enabled) C.ink else C.off))
                if (r.actions.any { it.needsPark }) {
                    Spacer(Modifier.height(6.dp))
                    Text("일부 동작은 주차 중에만 실행해요", style = T.small)
                }
            }
            Spacer(Modifier.width(24.dp))
            if (!driving) {
                TextButton(onClick = { a.shareRule = r }, modifier = Modifier.heightIn(min = TOUCH)) { Text("휴대폰으로", style = T.small.copy(color = C.primary)) }
                TextButton(onClick = { Store.deleteRule(r.id) }, modifier = Modifier.heightIn(min = TOUCH)) { Text("지우기", style = T.small.copy(color = C.sub)) }
                Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.size(width = 150.dp, height = TOUCH), contentAlignment = Alignment.Center) {
                Switch(
                    checked = r.enabled, onCheckedChange = { Store.setEnabled(r.id, it) },
                    modifier = Modifier.scale(1.6f),
                    colors = SwitchDefaults.colors(checkedTrackColor = C.primary),
                )
            }
        }
        HorizontalDivider(color = C.line)
    }
}

// ---------------- 확인 ----------------

@Composable
private fun Confirm(a: MainActivity, p: Pending) {
    var draft by remember(p) { mutableStateOf(p.draft) }
    val unsupported by Engine.unsupported.collectAsState()
    val problems = Validator.check(draft, unsupported)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 64.dp, end = 64.dp, top = 48.dp, bottom = 40.dp)) {
        Text("이렇게 이해했어요", style = T.title)
        if (p.spoken.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("\"${p.spoken}\"", style = T.small)
        } else {
            Spacer(Modifier.height(10.dp))
            Text(p.origin, style = T.small)
        }
        Spacer(Modifier.height(24.dp))
        Text(Phrase.sentence(draft), style = T.head.copy(color = C.primary))
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = C.line)

        Section("언제") {
            Line(Phrase.trigger(draft.trigger), Edit.editable(draft.trigger)) { d ->
                Edit.step(draft.trigger, d)?.let { draft = draft.copy(trigger = it) }
            }
        }
        if (draft.conditions.isNotEmpty()) Section("단") {
            draft.conditions.forEachIndexed { i, c ->
                Line(Phrase.condition(c).first, Edit.editable(c)) { d ->
                    Edit.step(c, d)?.let { n -> draft = draft.copy(conditions = draft.conditions.toMutableList().also { it[i] = n }) }
                }
            }
        }
        Section("이렇게") {
            draft.actions.forEachIndexed { i, act ->
                Line(Phrase.action(act).second, Edit.editable(act)) { d ->
                    Edit.step(act, d)?.let { n -> draft = draft.copy(actions = draft.actions.toMutableList().also { it[i] = n }) }
                }
            }
        }
        val parkOnly = draft.actions.filter { it.needsPark }
        if (parkOnly.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${parkOnly.joinToString(", ") { parkName(it) }.josa("은", "는")} 주차(P) 중에만 움직여요. 주행 중에 조건이 맞으면 주차한 뒤 이어서 실행해요.",
                style = T.body.copy(color = C.wait),
            )
        }
        problems.forEach {
            Spacer(Modifier.height(12.dp))
            Text(it, style = T.body.copy(color = C.bad))
        }
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigButton("저장", enabled = problems.isEmpty()) { a.save(p, draft) }
            OutlineButton("지금 한번 해 보기", enabled = problems.isEmpty()) { a.tryNow(draft) }
            OutlineButton(if (p.spoken.isNotBlank()) "다시 말하기" else "그만두기") {
                a.confirmQueue.clear()
                if (p.spoken.isNotBlank()) a.startListening()
            }
        }
        if (a.confirmQueue.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text("이 묶음에 자동화가 ${a.confirmQueue.size - 1}개 더 있어요. 저장하면 다음 것을 보여 드려요.", style = T.small)
        }
        a.message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = T.small)
        }
    }
}

private fun parkName(a: Action) = when (a) {
    is Action.CabinLight -> "실내등"
    is Action.ReadingLight -> "독서등"
    Action.DoorLock -> "문 잠금"
    Action.ChargePort -> "충전구"
    else -> ""
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Text(label, style = T.head.copy(color = C.sub), modifier = Modifier.width(170.dp).padding(top = 26.dp))
        Column(Modifier.weight(1f)) { content() }
    }
    HorizontalDivider(color = C.line)
}

@Composable
private fun Line(text: String, editable: Boolean, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TOUCH), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.head.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        if (editable) {
            StepButton("−") { onStep(-1) }
            Spacer(Modifier.width(16.dp))
            StepButton("+") { onStep(1) }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(TOUCH).clip(CircleShape).background(C.rail).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = T.title.copy(color = C.primary, fontWeight = FontWeight.Medium)) }
}

@Composable
private fun DrivingLock() {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center) {
        Text("주행 중이에요", style = T.title)
        Spacer(Modifier.height(16.dp))
        Text("주차하면 이어서 확인할게요.", style = T.body.copy(color = C.sub))
    }
}

// ---------------- 둘러보기 ----------------

@Composable
private fun Browse(a: MainActivity) {
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 48.dp)) {
        Text("둘러보기", style = T.title)
        Spacer(Modifier.height(8.dp))
        Text("고른 뒤 확인 화면에서 값을 내 차에 맞게 고쳐 저장해요.", style = T.body.copy(color = C.sub))
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = C.line)
        LazyColumn(Modifier.fillMaxSize()) {
            items(Samples.bundles.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { i, b ->
                        if (i == 1) Box(Modifier.width(1.dp).height(10.dp))
                        Column(Modifier.weight(1f).padding(top = 32.dp, bottom = 32.dp, end = if (i == 0) 48.dp else 0.dp)) {
                            Text(b.title, style = T.head.copy(fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(8.dp))
                            Text(b.summary, style = T.body.copy(color = C.sub))
                            b.rules.forEach {
                                Spacer(Modifier.height(10.dp))
                                Text(Phrase.sentence(it), style = T.small.copy(color = C.ink))
                            }
                            Spacer(Modifier.height(20.dp))
                            OutlineButton("골라서 고치기") { a.pickBundle(b) }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
}

// ---------------- 실행 기록 ----------------

@Composable
private fun RunLog() {
    val runs by Store.runs.collectAsState()
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("실행 기록", style = T.title, modifier = Modifier.weight(1f))
            if (runs.isNotEmpty()) TextButton(onClick = { Store.clearRuns() }, modifier = Modifier.heightIn(min = TOUCH)) {
                Text("기록 지우기", style = T.small.copy(color = C.sub))
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = C.line)
        if (runs.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text("아직 실행된 자동화가 없어요.", style = T.head)
        }
        val list = rememberLazyListState()
        LaunchedEffect(runs.firstOrNull()?.id) { if (runs.isNotEmpty()) list.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(runs, key = { it.id }) { RunRow(it) }
        }
    }
}

private fun timeText(ms: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = ms
    val f = if (c.get(Calendar.DAY_OF_YEAR) == today) "a h:mm:ss" else "M월 d일 a h:mm"
    return SimpleDateFormat(f, Locale.KOREA).format(Date(ms))
}

@Composable
private fun RunRow(r: RunRecord) {
    Row(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(timeText(r.at), style = T.small.copy(color = C.ink), modifier = Modifier.width(260.dp).padding(top = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(r.ruleText, style = T.body)
            Spacer(Modifier.height(6.dp))
            Text(if (r.manual) "직접 실행했어요" else "\"${r.cause}\" 조건이 맞아서 실행했어요", style = T.small)
            r.actions.forEach { ar ->
                Spacer(Modifier.height(10.dp))
                val (word, color) = when (ar.outcome) {
                    Outcome.done -> "했어요" to C.ok
                    Outcome.deferred -> "주차하면 이어서" to C.wait
                    Outcome.done_after_park -> "주차 후 했어요" to C.ok
                    Outcome.cancelled -> "취소됨" to C.off
                    Outcome.failed -> "못 했어요" to C.bad
                }
                Row {
                    Text(word, style = T.body.copy(color = color, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(260.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ar.text, style = T.body)
                        if (ar.note.isNotBlank()) Text(ar.note + if (ar.outcome == Outcome.done_after_park) " (${timeText(ar.at)})" else "", style = T.small)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = C.line)
}

// ---------------- 넘겨주기 ----------------

@Composable
private fun ShareDialog(a: MainActivity, r: Rule) {
    val uri = remember(r) { a.shareUri(r) }
    val bmp = remember(uri) { qr(uri, 640) }
    Dialog(onDismissRequest = { a.shareRule = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.widthIn(max = 1500.dp).clip(RoundedCornerShape(28.dp)).background(C.bg).padding(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(bmp.asImageBitmap(), contentDescription = "자동화 QR", modifier = Modifier.size(560.dp))
            Spacer(Modifier.width(56.dp))
            Column(Modifier.width(700.dp)) {
                Text("휴대폰으로 넘겨주기", style = T.title)
                Spacer(Modifier.height(20.dp))
                Text(Phrase.sentence(r), style = T.head.copy(color = C.primary))
                Spacer(Modifier.height(20.dp))
                Text("휴대폰 카메라로 찍으면 이 자동화를 글자로 가져갈 수 있어요. 다른 차에서 받을 때도 확인 화면을 거쳐 저장해요.", style = T.body.copy(color = C.sub))
                Spacer(Modifier.height(32.dp))
                BigButton("닫기") { a.shareRule = null }
            }
        }
    }
}

private fun qr(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

// ---------------- 버튼 ----------------

@Composable
fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = TOUCH).widthIn(min = 220.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.primary),
    ) { Text(label, style = T.button) }
}

@Composable
fun OutlineButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = TOUCH).widthIn(min = 220.dp),
        shape = RoundedCornerShape(20.dp),
    ) { Text(label, style = T.button.copy(color = if (enabled) C.primary else C.off)) }
}
