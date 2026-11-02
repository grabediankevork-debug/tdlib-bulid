package com.couchreel.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Screens used only in direct (no-server) mode: choosing the mode, API keys, Telegram login, settings.

@Composable
fun ModeScreen(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Welcome to Couchreel", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("How should the app reach your Telegram videos?", color = TextDim, fontSize = 18.sp)
        PanelRow(modifier = Modifier.focusMemory("mode", "direct", defaultFirst = true), onClick = { onPick("direct") }) {
            Text("Directly from Telegram (no server)", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Log in once with a QR code. The app scans your channels and streams videos itself.", color = TextDim, fontSize = 15.sp)
        }
        PanelRow(modifier = Modifier.focusMemory("mode", "server"), onClick = { onPick("server") }) {
            Text("Through my own tgbridge server", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Use the Python server on a PC or Pi (the earlier setup).", color = TextDim, fontSize = 15.sp)
        }
    }
}

/** Needed once: your own Telegram API id/hash from my.telegram.org (Telegram requires every app to have one). */
@Composable
fun CredentialsScreen(onSaved: (Int, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Telegram API keys", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Telegram asks every app for its own keys. Get yours free at my.telegram.org → API development tools.\n" +
                "Tip: put tgApiId and tgApiHash in gradle.properties before building and this screen is skipped.",
            color = TextDim,
            fontSize = 16.sp,
        )
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.filter { c -> c.isDigit() } },
            singleLine = true,
            label = { Text("api_id (numbers)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it.trim() },
            singleLine = true,
            label = { Text("api_hash (32 characters)") },
            modifier = Modifier.fillMaxWidth(),
        )
        ButtonRow {
            Chip("Save and continue") {
                val n = id.toIntOrNull()
                if (n == null || n <= 0 || hash.length < 16) msg = "Check both values." else onSaved(n, hash)
            }
        }
        StatusLine(msg)
    }
}

@Composable
fun QrImage(text: String, dimension: Dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(dimension).background(Color.White)) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until matrix.height) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

@Composable
fun LoginScreen(td: TdClient, state: TdClient.Auth) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        msg = ""
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = e.message ?: "Something went wrong"
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Log in to Telegram", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        when (state) {
            is TdClient.Auth.NeedCode -> {
                Text(state.info, color = TextDim, fontSize = 18.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("Login code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                ButtonRow { Chip("Continue", Modifier.focusMemory("login", "go", true)) { run { td.submitCode(code.trim()) } } }
            }
            is TdClient.Auth.NeedPassword -> {
                Text(
                    "Two-step verification is on." + if (state.hint.isNotEmpty()) " Hint: ${state.hint}" else "",
                    color = TextDim,
                    fontSize = 18.sp,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                )
                ButtonRow { Chip("Continue", Modifier.focusMemory("login", "go", true)) { run { td.submitPassword(password) } } }
            }
            else -> {
                if (state is TdClient.Auth.QrCode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        QrImage(state.link, 280.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Scan with your phone", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Telegram on your phone → Settings → Devices → Link Desktop Device, then scan this code.",
                                color = TextDim,
                                fontSize = 17.sp,
                            )
                        }
                    }
                } else {
                    ButtonRow {
                        Chip("Log in with a QR code (easiest on TV)", Modifier.focusMemory("login", "qr", true)) { run { td.requestQr() } }
                    }
                }
                Text("or use your phone number", color = TextDim, fontSize = 16.sp)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    singleLine = true,
                    label = { Text("Phone number with country code, e.g. +491701234567") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                ButtonRow { Chip(if (busy) "Working…" else "Send code") { run { td.submitPhone(phone.trim()) } } }
            }
        }
        StatusLine(msg)
    }
}

@Composable
fun DirectSettingsScreen(backend: LocalBackend, onSwitchMode: () -> Unit, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by backend.status.collectAsState()
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        StatusLine(if (status.isNotBlank()) status else msg)
        ButtonRow {
            Chip("Rescan my channels", Modifier.focusMemory("dsettings", "rescan", true)) {
                msg = "Scan started."
                backend.startRescan()
            }
            Chip("Clear download cache") {
                scope.launch {
                    msg = try {
                        backend.clearCache()
                        "Cache cleared."
                    } catch (e: Exception) {
                        "Couldn't clear cache: ${e.message}"
                    }
                }
            }
        }
        ButtonRow {
            Chip("Switch to server mode") { onSwitchMode() }
            Chip("Log out of Telegram") {
                scope.launch {
                    try {
                        backend.logOut()
                        Graph.reset()
                        onLoggedOut()
                    } catch (e: Exception) {
                        msg = "Couldn't log out: ${e.message}"
                    }
                }
            }
        }
        StatusLine(
            "Videos are streamed from Telegram as you watch and stored temporarily on this device. " +
                "Finished videos are removed from the cache automatically.",
        )
    }
}
