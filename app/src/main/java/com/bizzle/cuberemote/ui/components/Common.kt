package com.bizzle.cuberemote.ui.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalHaptics = staticCompositionLocalOf { 1 }

/** Haptic tick honoring the user's Off / Light / Strong setting. */
@Composable
fun rememberHaptic(): () -> Unit {
    val level = LocalHaptics.current
    val view = LocalView.current
    val ctx = LocalContext.current
    return remember(level) {
        {
            when (level) {
                1 -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                2 -> {
                    val v = ctx.getSystemService(Vibrator::class.java)
                    if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createOneShot(28, 255))
                }
                else -> {}
            }
        }
    }
}

/**
 * Remote key with hold-to-repeat. Tap = one press; hold = repeats every [repeatMs] after a short delay.
 */
@Composable
fun RemoteKey(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    size: Dp = 56.dp,
    width: Dp? = null,
    repeat: Boolean = false,
    repeatMs: Int = 120,
    filled: Boolean = false,
    enabled: Boolean = true,
    onPress: () -> Unit
) {
    val pal = LocalPalette.current
    val haptic = rememberHaptic()
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val bg = when { filled -> pal.accent; pressed -> pal.accent.copy(alpha = 0.25f); else -> pal.surface }
    val fg = if (filled) (if (pal.accent.luminance() > 0.5f) Color.Black else Color.White) else pal.accent
    Box(
        modifier
            .then(if (width != null) Modifier.width(width).height(size) else Modifier.size(size))
            .clip(shape)
            .background(bg)
            .border(BorderStroke(1.5.dp, pal.accent.copy(alpha = if (enabled) 0.9f else 0.3f)), shape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick { if (enabled) { press(); true } else false }
            }
            .pointerInput(enabled, repeat, repeatMs) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        haptic(); press()
                        val job = if (repeat) launch {
                            delay(380)
                            while (true) { press(); delay(repeatMs.toLong()) }
                        } else null
                        waitForUpOrCancellation()
                        job?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.45f))
        else Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = if (label.length > 4) 12.sp else 15.sp, maxLines = 1)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val haptic = rememberHaptic()
    Button(
        onClick = { haptic(); onClick() }, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFFFF5370) else pal.accent, contentColor = Color.Black,
            disabledContainerColor = pal.accent.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NeonOutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val haptic = rememberHaptic()
    OutlinedButton(
        onClick = { haptic(); onClick() }, enabled = enabled, modifier = modifier,
        border = BorderStroke(1.dp, pal.accent.copy(alpha = if (enabled) 1f else 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.accent), shape = RoundedCornerShape(12.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.surface)
            .border(1.dp, pal.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        if (title != null) {
            Text(title, color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Text(text, color = LocalPalette.current.text.copy(alpha = alpha), fontSize = 14.sp, modifier = modifier, lineHeight = 20.sp)
}

@Composable
fun StatusDot(color: Color, desc: String) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color).semantics { contentDescription = desc })
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = pal.text.copy(alpha = 0.65f), fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = pal.accent),
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
fun NeonField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, singleLine: Boolean = true,
              keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
              keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default) {
    val pal = LocalPalette.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, modifier = modifier.fillMaxWidth(),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = pal.accent, unfocusedBorderColor = pal.accent.copy(alpha = 0.45f), focusedLabelColor = pal.accent,
            cursorColor = pal.accent, focusedTextColor = pal.text, unfocusedTextColor = pal.text, unfocusedLabelColor = pal.text.copy(alpha = 0.7f)
        )
    )
}

/** Confirmation dialog used for every destructive action. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Yes, do it", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text(title, color = pal.accent, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = pal.text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Color(0xFFFF5370), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = pal.accent) } }
    )
}
