package com.lumo.app.ui

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Country(val iso: String, val name: String, val code: String) {
    val flag: String
        get() = iso.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')) }.joinToString("") { String(it) }
}

val countries = listOf(
    Country("IN", "India", "91"), Country("PK", "Pakistan", "92"), Country("BD", "Bangladesh", "880"),
    Country("NP", "Nepal", "977"), Country("LK", "Sri Lanka", "94"), Country("AE", "United Arab Emirates", "971"),
    Country("SA", "Saudi Arabia", "966"), Country("QA", "Qatar", "974"), Country("KW", "Kuwait", "965"),
    Country("OM", "Oman", "968"), Country("BH", "Bahrain", "973"), Country("US", "United States", "1"),
    Country("CA", "Canada", "1"), Country("GB", "United Kingdom", "44"), Country("IE", "Ireland", "353"),
    Country("DE", "Germany", "49"), Country("FR", "France", "33"), Country("IT", "Italy", "39"),
    Country("ES", "Spain", "34"), Country("NL", "Netherlands", "31"), Country("SE", "Sweden", "46"),
    Country("NO", "Norway", "47"), Country("CH", "Switzerland", "41"), Country("TR", "Türkiye", "90"),
    Country("RU", "Russia", "7"), Country("AU", "Australia", "61"), Country("NZ", "New Zealand", "64"),
    Country("SG", "Singapore", "65"), Country("MY", "Malaysia", "60"), Country("ID", "Indonesia", "62"),
    Country("PH", "Philippines", "63"), Country("TH", "Thailand", "66"), Country("VN", "Vietnam", "84"),
    Country("JP", "Japan", "81"), Country("KR", "South Korea", "82"), Country("CN", "China", "86"),
    Country("EG", "Egypt", "20"), Country("NG", "Nigeria", "234"), Country("KE", "Kenya", "254"),
    Country("ZA", "South Africa", "27"), Country("BR", "Brazil", "55"), Country("MX", "Mexico", "52"),
    Country("AR", "Argentina", "54"), Country("AF", "Afghanistan", "93"),
)

fun defaultCountry(): Country =
    countries.firstOrNull { it.iso == Locale.getDefault().country.uppercase() } ?: countries.first()

/** Phone login state shared by the phone and code screens. */
class AuthFlow {
    var country by mutableStateOf(defaultCountry())
    var number by mutableStateOf("")
    var verificationId by mutableStateOf<String?>(null)
    var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var sentAt by mutableLongStateOf(0L)

    val fullNumber get() = "+${country.code}${number.filter(Char::isDigit).trimStart('0')}"

    fun send(activity: Activity, resend: Boolean = false, onSent: () -> Unit, onDone: () -> Unit) {
        busy = true
        error = null
        val auth = FirebaseAuth.getInstance()
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(fullNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) = signIn(credential, onDone)
                override fun onVerificationFailed(e: FirebaseException) {
                    busy = false
                    error = when (e) {
                        is FirebaseAuthInvalidCredentialsException -> "That number doesn't look right."
                        is FirebaseTooManyRequestsException -> "Too many attempts. Try again later."
                        else -> e.localizedMessage ?: "Couldn't send the code."
                    }
                }
                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    busy = false
                    verificationId = id
                    resendToken = token
                    sentAt = System.currentTimeMillis()
                    onSent()
                }
            })
        if (resend) resendToken?.let { options.setForceResendingToken(it) }
        PhoneAuthProvider.verifyPhoneNumber(options.build())
    }

    fun verify(code: String, onDone: () -> Unit) {
        val id = verificationId ?: return
        signIn(PhoneAuthProvider.getCredential(id, code), onDone)
    }

    private fun signIn(credential: PhoneAuthCredential, onDone: () -> Unit) {
        busy = true
        error = null
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnSuccessListener { busy = false; onDone() }
            .addOnFailureListener {
                busy = false
                error = if (it is FirebaseAuthInvalidCredentialsException) "Wrong code. Check and try again." else it.localizedMessage
            }
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
private fun AuthScaffold(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding().padding(horizontal = 24.dp),
    ) {
        Box(Modifier.height(56.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = C.Text)
            }
        }
        content()
    }
}

@Composable
fun PhoneScreen(flow: AuthFlow, onCodeSent: () -> Unit, onSignedIn: () -> Unit) {
    val activity = LocalContext.current as Activity
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = flow.number.count(Char::isDigit) in 6..14

    AuthScaffold(onBack = null) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.AccentBrush), contentAlignment = Alignment.Center) {
            Text("L", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text("Your phone number", style = MaterialTheme.typography.headlineMedium, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text("We'll text you a code to confirm it's you.", color = C.Sub, fontSize = 16.sp)
        Spacer(Modifier.height(36.dp))

        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface)
                .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.fillMaxHeight().clickable { picking = true }.padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(flow.country.flag, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("+${flow.country.code}", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = C.Sub)
            }
            Box(Modifier.width(1.dp).height(28.dp).background(C.Stroke))
            BasicTextField(
                value = flow.number,
                onValueChange = { v -> flow.number = v.filter { it.isDigit() || it == ' ' }.take(17); flow.error = null },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = TextStyle(color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                cursorBrush = SolidColor(C.Accent),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp).focusRequester(focus),
                decorationBox = { inner ->
                    if (flow.number.isEmpty()) Text("Phone number", color = C.Sub.copy(alpha = 0.6f), fontSize = 20.sp)
                    inner()
                },
            )
        }
        flow.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = C.Red, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", enabled = valid, busy = flow.busy) {
            flow.send(activity, onSent = onCodeSent, onDone = onSignedIn)
        }
        Spacer(Modifier.height(20.dp))
    }

    if (picking) CountryPicker(onPick = { flow.country = it; picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPicker(onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search country") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxHeight(0.7f)) {
            items(countries.filter { it.name.contains(query, true) || it.code.startsWith(query.trimStart('+')) }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.flag, fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Text(c.name, color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("+${c.code}", color = C.Sub, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun CodeScreen(flow: AuthFlow, onBack: () -> Unit, onSignedIn: () -> Unit) {
    val activity = LocalContext.current as Activity
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var secondsLeft by remember { mutableIntStateOf(60) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(flow.sentAt) {
        while (true) {
            secondsLeft = (60 - (System.currentTimeMillis() - flow.sentAt) / 1000).toInt().coerceAtLeast(0)
            if (secondsLeft == 0) break
            delay(500)
        }
    }

    AuthScaffold(onBack = onBack) {
        Spacer(Modifier.height(24.dp))
        Text("Enter the code", style = MaterialTheme.typography.headlineMedium, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text("Sent to ${flow.fullNumber}", color = C.Sub, fontSize = 16.sp)
        Spacer(Modifier.height(36.dp))

        BasicTextField(
            value = code,
            onValueChange = { v ->
                code = v.filter(Char::isDigit).take(6)
                flow.error = null
                if (code.length == 6) flow.verify(code, onSignedIn)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(focus),
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { i ->
                        val active = i == code.length
                        Box(
                            Modifier.weight(1f).aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).background(C.Surface)
                                .border(if (active) 2.dp else 1.dp, if (active) C.Accent else C.Stroke, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString() ?: "", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(20.dp))
        flow.error?.let { Text(it, color = C.Red, fontSize = 14.sp); Spacer(Modifier.height(12.dp)) }
        if (flow.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = C.Accent, trackColor = C.Surface2)
        Spacer(Modifier.height(12.dp))
        Text(
            if (secondsLeft > 0) "Resend code in 0:%02d".format(secondsLeft) else "Resend code",
            color = if (secondsLeft > 0) C.Sub else C.Accent,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(enabled = secondsLeft == 0 && !flow.busy) {
                code = ""
                flow.send(activity, resend = true, onSent = {}, onDone = onSignedIn)
            }.padding(vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Verify", enabled = code.length == 6, busy = flow.busy) { flow.verify(code, onSignedIn) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun NameScreen(busy: Boolean, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AuthScaffold(onBack = null) {
        Spacer(Modifier.height(24.dp))
        Text("Your name", style = MaterialTheme.typography.headlineMedium, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text("This is what people see when you message or call.", color = C.Sub, fontSize = 16.sp)
        Spacer(Modifier.height(36.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(name.ifBlank { "?" }, name, 96.dp) }
        Spacer(Modifier.height(28.dp))
        BasicTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            textStyle = TextStyle(color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(C.Accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface)
                .border(1.dp, C.Stroke, RoundedCornerShape(18.dp)).padding(20.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (name.isEmpty()) Text("Full name", color = C.Sub.copy(alpha = 0.6f), fontSize = 22.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Get started", enabled = name.isNotBlank(), busy = busy) { onSave(name) }
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
