package com.lumo.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.lumo.app.data.Repo

/**
 * Username + passcode. The first time a username is used it creates the account;
 * after that the same username and passcode log in. Firebase keeps you signed in.
 */
class AuthFlow {
    var username by mutableStateOf("")
    var passcode by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val validUsername get() = Regex("[a-z0-9_.]{3,20}").matches(username)
    val validPasscode get() = passcode.length >= 6

    fun go(onDone: () -> Unit) {
        if (!validUsername || !validPasscode || busy) return
        busy = true
        error = null
        val auth = FirebaseAuth.getInstance()
        val email = Repo.emailFor(username)
        auth.signInWithEmailAndPassword(email, passcode)
            .addOnSuccessListener { busy = false; onDone() }
            .addOnFailureListener { signInError ->
                if (signInError is FirebaseNetworkException || signInError is FirebaseTooManyRequestsException) {
                    busy = false
                    error = message(signInError)
                    return@addOnFailureListener
                }
                // Not an existing account (or wrong passcode): try to create it.
                auth.createUserWithEmailAndPassword(email, passcode)
                    .addOnSuccessListener { busy = false; onDone() }
                    .addOnFailureListener { e ->
                        busy = false
                        error = if (e is FirebaseAuthUserCollisionException) "Wrong passcode for @$username." else message(e)
                    }
            }
    }

    private fun message(e: Exception) = when (e) {
        is FirebaseNetworkException -> "No internet connection."
        is FirebaseTooManyRequestsException -> "Too many tries. Wait a minute and try again."
        is FirebaseAuthWeakPasswordException -> "Passcode must be at least 6 characters."
        else -> e.localizedMessage ?: "Something went wrong."
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "btn")
    Box(
        modifier.fillMaxWidth().height(56.dp).alpha(alpha).clip(RoundedCornerShape(18.dp))
            .background(C.AccentBrush)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardOptions,
    actions: KeyboardActions = KeyboardActions.Default,
) {
    var show by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface)
            .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix != null) Text(prefix, color = C.Sub, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            keyboardOptions = keyboard, keyboardActions = actions,
            visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(C.Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = C.Sub.copy(alpha = 0.6f), fontSize = 20.sp)
                inner()
            },
        )
        if (secret) IconButton(onClick = { show = !show }) {
            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = C.Sub)
        }
    }
}

@Composable
fun LoginScreen(flow: AuthFlow, onSignedIn: () -> Unit) {
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { userFocus.requestFocus() }

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.AccentBrush), contentAlignment = Alignment.Center) {
            Text("L", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text("Welcome to Lumo", style = MaterialTheme.typography.headlineMedium, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text("Pick a username and passcode. Use the same ones to log in again.", color = C.Sub, fontSize = 16.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(36.dp))

        Field(
            value = flow.username,
            onChange = { v -> flow.username = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }.take(20); flow.error = null },
            placeholder = "username", prefix = "@",
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            actions = KeyboardActions(onNext = { passFocus.requestFocus() }),
            modifier = Modifier.focusRequester(userFocus),
        )
        Spacer(Modifier.height(12.dp))
        Field(
            value = flow.passcode,
            onChange = { flow.passcode = it.take(64); flow.error = null },
            placeholder = "Passcode", secret = true,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { flow.go(onSignedIn) }),
            modifier = Modifier.focusRequester(passFocus),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            flow.error ?: when {
                flow.username.isNotEmpty() && !flow.validUsername -> "Username: 3–20 letters, numbers, _ or ."
                flow.passcode.isNotEmpty() && !flow.validPasscode -> "Passcode: at least 6 characters"
                else -> ""
            },
            color = if (flow.error != null) C.Red else C.Sub, fontSize = 14.sp,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", enabled = flow.validUsername && flow.validPasscode, busy = flow.busy) { flow.go(onSignedIn) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun SetupScreen() {
    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Almost there", style = MaterialTheme.typography.headlineMedium, color = C.Text)
        Spacer(Modifier.height(12.dp))
        Text(
            "This build has no Firebase project connected yet, so login, chat and calls can't work.\n\n" +
                "Add your google-services.json to lumo/app/ and build again. The README explains each step.",
            color = C.Sub, fontSize = 16.sp, lineHeight = 23.sp,
        )
    }
}
