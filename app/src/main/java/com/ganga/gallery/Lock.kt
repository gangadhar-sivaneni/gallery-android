package com.ganga.gallery

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private const val PIN_LENGTH = 4

fun canUseBiometric(act: FragmentActivity) =
    BiometricManager.from(act).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun biometric(act: FragmentActivity, title: String, onOk: () -> Unit) {
    if (!canUseBiometric(act)) return
    BiometricPrompt(
        act, ContextCompat.getMainExecutor(act),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        },
    ).authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIOMETRIC_WEAK).build(),
    )
}

/** Shows PIN setup if this lock has no PIN yet, otherwise PIN / fingerprint unlock. */
@Composable
fun LockGate(vm: GalleryViewModel, kind: PinKind, title: String, onUnlock: () -> Unit) {
    if (!vm.hasPin(kind)) PinSetup(vm, kind, "Create a ${kind.label.lowercase()} PIN", "Choose $PIN_LENGTH digits you'll remember", onUnlock)
    else PinUnlock(vm, kind, title, allowBiometric = true, onUnlock = onUnlock)
}

/** [allowBiometric] false = the PIN itself must be typed (used before changing or switching off a lock). */
@Composable
fun PinUnlock(vm: GalleryViewModel, kind: PinKind, title: String, allowBiometric: Boolean, onUnlock: () -> Unit) {
    val act = LocalContext.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    val bio = allowBiometric && vm.useBiometric && canUseBiometric(act)
    LaunchedEffect(Unit) { if (bio) biometric(act, title) { vm.pinOk(kind); onUnlock() } }
    // countdown while locked out after too many wrong PINs
    var wait by remember { mutableLongStateOf(vm.pinWait(kind)) }
    LaunchedEffect(wait > 0) { while (wait > 0) { kotlinx.coroutines.delay(1000); wait = vm.pinWait(kind) } }
    PinPad(
        title = title,
        subtitle = if (wait > 0) "Too many tries. Wait ${(wait + 999) / 1000}s" else "Enter your ${kind.label.lowercase()} PIN",
        error = error, resetKey = attempts, enabled = wait == 0L,
        extra = if (bio) ({ biometric(act, title) { vm.pinOk(kind); onUnlock() } }) else null,
    ) { pin ->
        if (vm.checkPin(kind, pin)) { vm.pinOk(kind); onUnlock() }
        else { vm.pinFailed(kind); attempts++; error = "Wrong PIN"; wait = vm.pinWait(kind) }
    }
}

@Composable
fun PinSetup(vm: GalleryViewModel, kind: PinKind, title: String, subtitle: String, onDone: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    PinPad(
        title = title,
        subtitle = if (first == null) subtitle else "Enter the same PIN again",
        error = error, resetKey = first,
    ) { pin ->
        when {
            first == null -> { first = pin; error = null }
            first == pin -> { vm.setPin(kind, pin); vm.toast("PIN saved"); onDone() }
            else -> { first = null; error = "PINs didn't match. Try again." }
        }
    }
}

/**
 * Every change to a lock goes through here. Switching app lock off or changing a PIN needs the
 * current PIN typed in (fingerprint isn't accepted), so someone holding an unlocked phone can't disable it.
 */
@Composable
fun PinFlow(vm: GalleryViewModel, s: Screen.Pin, onDone: () -> Unit) {
    val needsOld = vm.hasPin(s.kind) && s.action != PinAction.ENABLE_APP_LOCK
    var verified by remember { mutableStateOf(!needsOld) }
    if (!verified) {
        PinUnlock(vm, s.kind, "Confirm it's you", allowBiometric = false) {
            verified = true
            if (s.action == PinAction.DISABLE_APP_LOCK) { vm.changeAppLock(false); vm.toast("App lock turned off"); onDone() }
        }
        return
    }
    when (s.action) {
        PinAction.DISABLE_APP_LOCK -> LaunchedEffect(Unit) { onDone() }
        PinAction.ENABLE_APP_LOCK ->
            if (vm.hasPin(PinKind.APP)) LaunchedEffect(Unit) { vm.changeAppLock(true); vm.toast("App lock on"); onDone() }
            else PinSetup(vm, PinKind.APP, "Create app lock PIN", "Asked every time Gallery opens") {
                vm.changeAppLock(true); onDone()
            }
        PinAction.CHANGE -> PinSetup(
            vm, s.kind, if (vm.hasPin(s.kind)) "New ${s.kind.label.lowercase()} PIN" else "Create ${s.kind.label.lowercase()} PIN",
            "Choose $PIN_LENGTH digits you'll remember", onDone,
        )
    }
}

/** Numeric keypad with dot indicators. Calls [onComplete] once PIN_LENGTH digits are entered. */
@Composable
private fun PinPad(
    title: String, subtitle: String, error: String?, resetKey: Any? = null,
    extra: (() -> Unit)? = null, enabled: Boolean = true, onComplete: (String) -> Unit,
) {
    var pin by remember(resetKey, error) { mutableStateOf("") }
    LaunchedEffect(enabled) { if (!enabled) pin = "" }
    val header: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(PIN_LENGTH) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier.size(16.dp).clip(CircleShape)
                            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val keypad: @Composable (Dp) -> Unit = { key ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del").chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(key).clip(CircleShape)
                                .background(if (k.length == 1) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
                                .clickable(enabled = enabled && (k != "bio" || extra != null)) {
                                    when (k) {
                                        "del" -> pin = pin.dropLast(1)
                                        "bio" -> extra?.invoke()
                                        else -> if (pin.length < PIN_LENGTH) {
                                            pin += k
                                            if (pin.length == PIN_LENGTH) onComplete(pin)
                                        }
                                    }
                                },
                            Alignment.Center,
                        ) {
                            when (k) {
                                "del" -> Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete")
                                "bio" -> if (extra != null) Icon(Icons.Outlined.Fingerprint, "Use fingerprint", Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                                else -> Text(k, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val key = ((maxHeight - 60.dp) / 4).coerceAtMost(64.dp)
        if (maxWidth > maxHeight) Row(
            Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) { header(); keypad(key) }
        else Column(horizontalAlignment = Alignment.CenterHorizontally) { header(); Spacer(Modifier.height(12.dp)); keypad(72.dp) }
    }
}

