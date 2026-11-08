package com.roywatson.lunar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.about_body
import com.roywatson.lunar.resources.about_call
import com.roywatson.lunar.resources.about_close
import com.roywatson.lunar.resources.about_email_label
import com.roywatson.lunar.resources.about_invitation
import com.roywatson.lunar.resources.about_logo
import com.roywatson.lunar.resources.about_open
import com.roywatson.lunar.resources.about_open_website
import com.roywatson.lunar.resources.about_phone_label
import com.roywatson.lunar.resources.about_send_email
import com.roywatson.lunar.resources.about_title
import com.roywatson.lunar.resources.about_web_label
import org.jetbrains.compose.resources.stringResource

/** Contact details; not translated, so they live here rather than in the string resources. */
private object Contact {
    const val PHONE = "1-802-735-1500"
    const val PHONE_URI = "tel:+18027351500"
    const val EMAIL = "rwatson@roywatson.com"
    const val EMAIL_URI = "mailto:$EMAIL"
    const val WEB = "www.roywatson.app"
    const val WEB_URI = "https://$WEB"
}

/** The header's round "i" button that opens the about screen. */
@Composable
internal fun AboutButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.about_open)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, LunarColors.Accent.copy(alpha = .6f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Text(
            "i",
            color = LunarColors.Accent,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The about screen: a contact card that rises over a dimmed app while [visible]. It is a focusable
 * popup, so it gets its own window: on desktop that window sits above the heavyweight Vulkan canvas
 * (see `compose.layers.type` in the desktop `main`), and Back or Escape calls [onDismiss].
 */
@Composable
internal fun AboutOverlay(visible: Boolean, onDismiss: () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    // Keep the popup until the exit animation has finished.
    if (!transition.currentState && !transition.targetState) return
    // Read outside the popup: its window provides the platform's handler again, hiding any
    // handler supplied around App (a test's, for one).
    val uriHandler = LocalUriHandler.current
    // A device with no handler for the scheme (no dialer on desktop, say) just does nothing.
    val openUri: (String) -> Unit = { uri -> runCatching { uriHandler.openUri(uri) } }

    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(transition, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Tapping the dimmed app closes the card; screen readers use the Close button.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LunarColors.Space.copy(alpha = .78f))
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
                )
                AboutCard(
                    onOpenUri = openUri,
                    onDismiss = onDismiss,
                    modifier = Modifier.animateEnterExit(
                        enter = slideInVertically { it / 6 } + scaleIn(initialScale = .94f),
                        exit = slideOutVertically { it / 6 } + scaleOut(targetScale = .94f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun AboutCard(onOpenUri: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.about_title)
    Column(
        modifier = modifier
            .padding(20.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LunarColors.Paper)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoyWatsonMark(
            stringResource(Res.string.about_logo),
            Modifier.fillMaxWidth(.6f).align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(Res.string.about_body),
            color = LunarColors.Ink,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(Res.string.about_invitation),
            color = LunarColors.Ink,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column {
            ContactRow(
                stringResource(Res.string.about_phone_label),
                Contact.PHONE,
                Contact.PHONE_URI,
                stringResource(Res.string.about_call),
                onOpenUri,
            )
            ContactRow(
                stringResource(Res.string.about_email_label),
                Contact.EMAIL,
                Contact.EMAIL_URI,
                stringResource(Res.string.about_send_email),
                onOpenUri,
            )
            ContactRow(
                stringResource(Res.string.about_web_label),
                Contact.WEB,
                Contact.WEB_URI,
                stringResource(Res.string.about_open_website),
                onOpenUri,
            )
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(
                stringResource(Res.string.about_close).uppercase(),
                color = LunarColors.Link,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** A labelled contact detail that opens [uri] (dialer, mail client, or browser) when tapped. */
@Composable
private fun ContactRow(label: String, value: String, uri: String, actionLabel: String, onOpenUri: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = actionLabel, role = Role.Button) { onOpenUri(uri) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Text(
            label.uppercase(),
            color = LunarColors.InkMuted,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            color = LunarColors.Link,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * [PrintTransparent] cropped to its artwork: the vector's 1280 x 1024 viewport is mostly empty
 * margin. Its background is transparent, so the card's colour shows through.
 */
@Composable
private fun RoyWatsonMark(description: String, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(PrintTransparent)
    Box(
        modifier
            .aspectRatio(LogoArtwork.width / LogoArtwork.height)
            .semantics { contentDescription = description }
            .drawBehind {
                val scale = size.width / LogoArtwork.width
                withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                    translate(-LogoArtwork.left, -LogoArtwork.top) {
                        with(painter) { draw(Size(PrintTransparent.viewportWidth, PrintTransparent.viewportHeight)) }
                    }
                }
            },
    )
}

/** The logo's artwork bounds, plus a small margin, in [PrintTransparent]'s viewport units. */
private val LogoArtwork = Rect(left = 205f, top = 175f, right = 1076f, bottom = 825f)
