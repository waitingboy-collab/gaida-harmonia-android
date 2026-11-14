@file:OptIn(ExperimentalLayoutApi::class)

package bg.rado.gaidaharmonia.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import bg.rado.gaidaharmonia.GaidaViewModel
import bg.rado.gaidaharmonia.engine.SourceKind
import bg.rado.gaidaharmonia.engine.Theory
import kotlinx.coroutines.delay

/* ------------------------- палитра ------------------------- */
object Pal {
    val Bg = Color(0xFFF2F4F1)        // планинска мъгла
    val Surface = Color.White
    val Sunk = Color(0xFFE6EAE5)
    val Ink = Color(0xFF1D2420)       // борово мастило
    val Muted = Color(0xFF5D6A62)
    val Line = Color(0xFFCDD5CF)
    val Accent = Color(0xFF9B2C2C)    // родопска вълна
    val AccentSoft = Color(0xFFF6E4E2)
    val Gold = Color(0xFFC99A2E)      // чимшир
    val Ok = Color(0xFF2F7D4F)
    val Warn = Color(0xFFB8741A)
}

@Composable
fun GaidaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pal.Accent, onPrimary = Color.White,
            secondary = Pal.Gold, background = Pal.Bg, onBackground = Pal.Ink,
            surface = Pal.Surface, onSurface = Pal.Ink,
            surfaceVariant = Pal.Sunk, onSurfaceVariant = Pal.Muted, outline = Pal.Line
        ),
        content = content
    )
}

/* ------------------------- екран ------------------------- */
@Composable
fun GaidaApp(vm: GaidaViewModel) {
    val ctx = LocalContext.current
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok; vm.start(ok)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadFile(uri)
    }

    LaunchedEffect(Unit) { while (true) { vm.poll(); delay(16) } }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.running }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // заглавие
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Гайда Хармония", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                Text("Засвири — тоналност, ритъм и акорди се засичат сами", fontSize = 13.sp, color = Pal.Muted)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    when {
                        vm.running -> vm.stop()
                        vm.source == SourceKind.MIC && !micGranted -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else -> vm.start(micGranted)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.running) Pal.Ink else Pal.Accent),
                shape = RoundedCornerShape(50)
            ) { Text(if (vm.running) "■  Стоп" else "▶  Старт", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Weave()

        HeroCard(vm)
        DegreesCard(vm)
        BeatsCard(vm)
        PadsCard(vm)
        StatusBar(vm.status, vm.statusErr)

        DetectedCard(vm)
        SourceCard(vm, micGranted, onPickFile = { fileLauncher.launch(arrayOf("audio/*")) })
        MeterCard(vm)
        MixerCard(vm)
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------- части ------------------------- */
@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = Pal.Surface, border = BorderStroke(1.dp, Pal.Line),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun Cap(t: String) =
    Text(t.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = Pal.Muted)

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = Pal.Muted)

/** Шевица от родопска черга. */
@Composable
private fun Weave() {
    Canvas(Modifier.fillMaxWidth().height(9.dp)) {
        val w = 12.dp.toPx(); var x = -w; var i = 0
        while (x < size.width + w) {
            val p = Path().apply { moveTo(x, size.height); lineTo(x + w / 2, 0f); lineTo(x + w, size.height); close() }
            drawPath(p, if (i % 3 == 0) Pal.Accent else Pal.Ink)
            x += w; i++
        }
    }
}

@Composable
private fun HeroCard(vm: GaidaViewModel) = CardBox {
    Row {
        Column(Modifier.weight(1f)) {
            Cap("Акорд сега")
            Text(vm.chord.label, fontSize = 64.sp, lineHeight = 70.sp, fontWeight = FontWeight.Black, color = Pal.Accent)
            Text("${vm.chord.long} · ${vm.chord.roman}", fontSize = 15.sp, color = Pal.Ink)
            Text(if (vm.auto) "следва гайдата" else "задържан ръчно", fontSize = 12.sp, color = Pal.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Чувам")
            val live = vm.live
            val inMode = live != null && Theory.MODES[vm.modeIdx].steps.any { (vm.tonic + it) % 12 == live.pc }
            Text(
                if (live == null) "·" else Theory.BG[live.pc] + if (inMode) "" else " ✕",
                fontSize = 28.sp, fontWeight = FontWeight.Black, color = if (live == null || inMode) Pal.Ink else Pal.Warn
            )
            Text(
                if (live == null) "— Hz" else "%.1f Hz · %+d¢".format(live.freq, live.cents),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.Muted
            )
            CentsBar(live?.cents)
            Row(Modifier.fillMaxWidth()) {
                Text("−50¢", fontSize = 10.sp, color = Pal.Muted, modifier = Modifier.weight(1f))
                Text("чисто", fontSize = 10.sp, color = Pal.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("+50¢", fontSize = 10.sp, color = Pal.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Sunk)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((vm.rms * 4).coerceIn(0f, 1f)).background(Pal.Ok))
            }
        }
    }
}

@Composable
private fun CentsBar(cents: Int?) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).background(Pal.Sunk)) {
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().padding(vertical = 3.dp).background(Pal.Muted))
        if (cents != null) {
            val c = cents.coerceIn(-50, 50)
            val col = when { kotlin.math.abs(c) <= 10 -> Pal.Ok; kotlin.math.abs(c) <= 25 -> Pal.Gold; else -> Pal.Accent }
            val x: Dp = (maxWidth - 7.dp) * (0.5f + c / 100f)
            Box(Modifier.offset(x = x).padding(vertical = 3.dp).width(7.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(col))
        }
    }
}

@Composable
private fun DegreesCard(vm: GaidaViewModel) = CardBox {
    Cap("Тонове на лада — колко ги свириш")
    val steps = Theory.MODES[vm.modeIdx].steps
    val mx = maxOf(0.0001f, vm.weights.maxOrNull() ?: 0f)
    val livePc = vm.live?.pc
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s ->
            val pc = (vm.tonic + s) % 12
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.weight(1f).height(88.dp).clip(shape).background(Pal.Sunk)
                    .then(if (pc == livePc) Modifier.border(2.dp, Pal.Ink, shape) else Modifier)
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .fillMaxHeight((vm.weights[pc] / mx).coerceIn(0f, 1f))
                        .background(Pal.Gold.copy(alpha = 0.55f))
                )
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Theory.BG[pc], fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (i == 0) Pal.Accent else Pal.Ink, maxLines = 1)
                    Text("${i + 1}", fontSize = 10.sp, color = Pal.Muted)
                }
            }
        }
    }
}

@Composable
private fun BeatsCard(vm: GaidaViewModel) = CardBox {
    Cap("Ритъм")
    val g = vm.groups
    if (g == null) {
        Hint("Без тъпан — акордът се държи и следва мелодията. Тъпанът влиза, щом ритъмът е равен.")
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var u = 0
            g.forEach { len ->
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Sunk).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (k in 0 until len) {
                        val now = u == vm.unit
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (now) Pal.Accent else Pal.Surface)
                                .border(2.dp, if (now) Pal.Accent else if (k == 0) Pal.Muted else Pal.Line, CircleShape)
                        )
                        u++
                    }
                }
            }
        }
        Hint("${g.joinToString("+")} = ${g.sum()}/8 · ${vm.bpm} удара в минута")
    }
}

@Composable
private fun PadsCard(vm: GaidaViewModel) = CardBox {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Cap("Акорди в лада") }
        Text("Следвай гайдата", fontSize = 13.sp, color = Pal.Ink)
        Spacer(Modifier.width(8.dp))
        Switch(checked = vm.auto, onCheckedChange = vm::chooseAuto,
            colors = SwitchDefaults.colors(checkedTrackColor = Pal.Ok))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.candidates.forEach { c ->
            val on = c.same(vm.chord)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (on) Pal.AccentSoft else Pal.Surface,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Pal.Accent else Pal.Line),
                modifier = Modifier.widthIn(min = 72.dp).clickable { vm.forceChord(c) }
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.label, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                    Text(c.roman, fontSize = 12.sp, color = Pal.Muted)
                }
            }
        }
    }
    Hint("Докосни акорд, за да го задържиш. „Следвай гайдата“ връща автоматичния режим.")
}

@Composable
private fun StatusBar(text: String, err: Boolean) {
    Surface(shape = RoundedCornerShape(10.dp), color = if (err) Pal.AccentSoft else Pal.Sunk, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 14.sp, color = Pal.Ink, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Pal.Line, RoundedCornerShape(10.dp))) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).background(if (on) Pal.Ink else Pal.Surface).clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text(o, fontSize = 14.sp, color = if (on) Pal.Bg else Pal.Ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun Picker(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Pal.Line)) {
            Text(options[selected], color = Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
            Text("▾", color = Pal.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun SourceCard(vm: GaidaViewModel, micGranted: Boolean, onPickFile: () -> Unit) = CardBox {
    Cap("Звук от")
    val kinds = listOf(SourceKind.MIC, SourceKind.FILE, SourceKind.DEMO)
    Segmented(listOf("Микрофон", "Файл", "Демо"), kinds.indexOf(vm.source)) { vm.chooseSource(kinds[it], micGranted) }
    when (vm.source) {
        SourceKind.MIC -> Hint("Свири със слушалки, иначе микрофонът чува и акомпанимента.")
        SourceKind.FILE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickFile, enabled = vm.decoding < 0, border = BorderStroke(1.dp, Pal.Line)) { Text("Избери файл", color = Pal.Ink) }
                Spacer(Modifier.width(10.dp))
                Text(vm.fileName ?: "няма избран файл", fontSize = 13.sp, color = Pal.Muted, maxLines = 1)
            }
            if (vm.decoding >= 0) LinearProgressIndicator(progress = { vm.decoding / 100f }, modifier = Modifier.fillMaxWidth(), color = Pal.Accent)
            Hint("Запис на гайда (mp3, m4a, wav, ogg). Всичко се засича от записа.")
        }
        SourceKind.DEMO -> Hint("Синтезирана гайда: ръченица в Ре, с орнаменти — за проба без инструмент.")
    }
}

@Composable
private fun DetectedCard(vm: GaidaViewModel) = CardBox {
    Cap("Засечено автоматично")
    @Composable
    fun line(name: String, value: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Pal.Ink)
    }
    val g = vm.groups
    line("Тоналност", if (!vm.running) "—" else if (vm.keyKnown) "${Theory.BG[vm.tonic]} · ${Theory.MODES[vm.modeIdx].name}" else "слушам…")
    val meterName = g?.let { gg -> Theory.METERS.firstOrNull { it.groups?.contentEquals(gg) == true }?.name ?: gg.joinToString("+") }
    line("Ритъм", if (!vm.running) "—" else if (g != null) "$meterName · ${vm.bpm}" else "свободен (бавна песен)")
    line("Реакция", if (vm.running && vm.reactionMs > 0) "${vm.reactionMs} ms след тона" else "—")
}

@Composable
private fun MeterCard(vm: GaidaViewModel) = CardBox {
    Cap("Размер")
    Picker(listOf("Авто — засича сам") + Theory.METERS.map { it.name }, vm.meterChoice + 1, { vm.chooseMeter(it - 1) }, Modifier.fillMaxWidth())
    Hint("Остави „Авто“. Избери размер само ако засичането греши.")
}

@Composable
private fun LabeledSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 14.sp, color = Pal.Ink, modifier = Modifier.width(72.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Pal.Accent, activeTrackColor = Pal.Accent)
        )
    }
}

@Composable
private fun MixerCard(vm: GaidaViewModel) = CardBox {
    Cap("Миксер")
    LabeledSlider("Акорди", vm.volChord, 0f..1f) { vm.changeVol(0, it) }
    LabeledSlider("Бас", vm.volBass, 0f..1f) { vm.changeVol(1, it) }
    LabeledSlider("Тъпан", vm.volDrum, 0f..1f) { vm.changeVol(2, it) }
    LabeledSlider("Гайда", vm.volSrc, 0f..1f) { vm.changeVol(3, it) }
    Hint("„Гайда“ е силата на файла или демото. Микрофонът не се пуска в говорителя.")
}
