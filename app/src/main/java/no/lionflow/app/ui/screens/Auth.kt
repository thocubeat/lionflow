package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.LionLogo
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.Wordmark
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

@Composable
private fun AuthColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@Composable
private fun SecurityNote(text: String) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LfIcon(LfIcons.Shield, c.accentInk, 18.dp)
        Text(text, style = Type.tiny.copy(lineHeight = 17.sp), color = c.text2)
    }
}

@Composable
fun SignInScreen(vm: AppViewModel, ui: UiState, settings: AppSettings) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    AuthColumn {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LionLogo(84.dp)
            Wordmark(34)
            Text("Sign in with your MyPeugeot account", style = Type.body, color = c.muted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox) { vm.settings.update { it.copy(rememberMe = !it.rememberMe) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = settings.rememberMe,
                onCheckedChange = { v -> vm.settings.update { it.copy(rememberMe = v) } },
                colors = CheckboxDefaults.colors(checkedColor = c.meter, uncheckedColor = c.muted, checkmarkColor = Color.White),
            )
            Text("Remember me", style = Type.body, color = c.text)
        }
        Text(
            if (settings.rememberMe) "You stay signed in on this phone. The app still asks for your security code or fingerprint."
            else "You'll be signed out every time the app is closed.",
            style = Type.tiny, color = c.muted,
        )
        if (ui.signingIn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Busy(); Spacer(Modifier.size(10.dp)); Text("Signing in…", style = Type.body, color = c.text)
            }
        } else {
            PrimaryButton("Sign in with Peugeot", Modifier.fillMaxWidth(), icon = LfIcons.Lock) {
                platform.openSignIn(vm.authorizeUrl())
            }
        }
        Spacer(Modifier.height(8.dp))
        SecurityNote("You type your email and password on Peugeot's own sign-in page, and the app opens again by itself. If Android asks which app to use, choose LionFlow. Your password never passes through LionFlow — it only keeps an encrypted token on this phone.")
        Text("Not affiliated with Peugeot or Stellantis.", style = Type.tiny, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun lfFieldColors() = LocalLf.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
        focusedContainerColor = c.card, unfocusedContainerColor = c.card,
    )
}

@Composable
fun RemoteSetupScreen(vm: AppViewModel, ui: UiState) {
    val c = LocalLf.current
    var sms by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    AuthColumn {
        Spacer(Modifier.height(8.dp))
        LionLogo(56.dp)
        Text("Enable remote control", style = Type.title, color = c.text)
        Text(
            "To lock, unlock, pre-heat and control charging from LionFlow, Peugeot needs to confirm this phone once: we send you an SMS code, and you enter it together with your 4-digit MyPeugeot security code.",
            style = Type.body.copy(lineHeight = 21.sp), color = c.text2,
        )
        if (!ui.smsSent) {
            PrimaryButton(if (ui.busy) "Sending…" else "Send SMS code", Modifier.fillMaxWidth(), enabled = !ui.busy) { vm.requestSms() }
        } else {
            OutlinedTextField(
                value = sms, onValueChange = { sms = it.filter { ch -> ch.isLetterOrDigit() }.take(12) },
                label = { Text("SMS code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = lfFieldColors(),
            )
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("MyPeugeot security code (4 digits)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = lfFieldColors(),
            )
            PrimaryButton(if (ui.busy) "Activating…" else "Activate", Modifier.fillMaxWidth(), enabled = !ui.busy && sms.length >= 4 && pin.length == 4) {
                vm.activateRemote(sms, pin)
            }
            Text("Didn't get it? Send again", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.clickable { vm.requestSms() }.padding(vertical = 8.dp))
        }
        ui.setupError?.let { Text(it, style = Type.small, color = c.danger) }
        SecurityNote("Your security code is stored encrypted on this phone only and is used to sign remote commands, exactly like the MyPeugeot app. Too many wrong codes can make Peugeot block remote control for a while.")
        SecondaryButton("Skip for now — status only", Modifier.fillMaxWidth()) { vm.skipRemoteSetup() }
    }
}

@Composable
fun LockScreen(vm: AppViewModel, settings: AppSettings) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bio = settings.bioUnlock && platform.biometricAvailable

    fun tryBio() {
        platform.authenticate("Unlock LionFlow", "Use your fingerprint", onSuccess = { vm.unlock() }, onFail = { })
    }
    LaunchedEffect(Unit) { if (bio) tryBio() }
    LaunchedEffect(code) {
        if (code.length == 4) {
            if (vm.checkPin(code)) vm.unlock() else { error = true; delay(600); code = ""; error = false }
        }
    }
    AuthColumn {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LionLogo(52.dp)
            Text(if (error) "Wrong code" else "Enter security code", style = Type.title.copy(fontSize = 26.sp), color = if (error) c.danger else c.text)
            Text(if (bio) "Your 4-digit MyPeugeot code, or use your fingerprint" else "Your 4-digit MyPeugeot code", style = Type.small, color = c.muted, textAlign = TextAlign.Center)
        }
        PinDots(code.length, error)
        Spacer(Modifier.height(16.dp))
        PinPad(
            showBio = bio,
            onDigit = { if (code.length < 4) code += it },
            onDelete = { code = code.dropLast(1) },
            onBio = { tryBio() },
        )
        Text("Forgot your code? Sign out and sign in again", style = Type.small, color = c.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { vm.signOut() }.padding(vertical = 10.dp))
    }
}

@Composable
fun PinDots(filled: Int, error: Boolean) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = "$filled of 4 digits entered" }, horizontalArrangement = Arrangement.Center) {
        for (i in 0 until 4) {
            val on = i < filled
            val col = if (error) c.danger else c.text
            Box(Modifier.padding(horizontal = 9.dp).size(16.dp).clip(CircleShape).background(if (on) col else Color.Transparent).border(2.dp, if (on) col else c.bar, CircleShape))
        }
    }
}

@Composable
fun PinPad(showBio: Boolean, onDigit: (String) -> Unit, onDelete: () -> Unit, onBio: () -> Unit) {
    val c = LocalLf.current
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (r in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                for (k in r) {
                    val m = Modifier.weight(1f).height(64.dp).clip(CircleShape)
                    when (k) {
                        "bio" -> Box(m.clickable(enabled = showBio, role = Role.Button, onClick = onBio).semantics { contentDescription = "Use fingerprint" }, contentAlignment = Alignment.Center) {
                            if (showBio) LfIcon(LfIcons.Fingerprint, c.accentInk, 30.dp)
                        }
                        "del" -> Box(m.clickable(role = Role.Button, onClick = onDelete).semantics { contentDescription = "Delete" }, contentAlignment = Alignment.Center) {
                            LfIcon(LfIcons.Backspace, c.text, 26.dp)
                        }
                        else -> Box(m.background(c.card).border(1.dp, c.line, CircleShape).clickable(role = Role.Button) { onDigit(k) }, contentAlignment = Alignment.Center) {
                            Text(k, style = Type.display.copy(fontSize = 26.sp), color = c.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PinDialog(title: String, check: (String) -> Boolean, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val c = LocalLf.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        if (code.length == 4) {
            if (check(code)) onSuccess() else { error = true; delay(600); code = ""; error = false }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.card).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$title?", style = Type.stat, color = c.text)
            Text(if (error) "Wrong code — try again" else "Enter your 4-digit security code", style = Type.small, color = if (error) c.danger else c.muted)
            PinDots(code.length, error)
            PinPad(showBio = false, onDigit = { if (code.length < 4) code += it }, onDelete = { code = code.dropLast(1) }, onBio = {})
            Text("Cancel", style = Type.label, color = c.muted, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
        }
    }
}
