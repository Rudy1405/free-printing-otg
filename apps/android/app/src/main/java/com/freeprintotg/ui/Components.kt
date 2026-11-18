package com.freeprintotg.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeprintotg.Printer
import com.freeprintotg.R

/** Turquoise app bar (NokoPrint-style): title, optional back, action icons and an optional extra row below. */
@Composable
fun AppHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(color = Palette.Turquoise, contentColor = Palette.OnTurquoise, shadowElevation = 6.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) HeaderIcon(R.drawable.ic_back, "Back", onBack) else Spacer(Modifier.width(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                actions()
            }
            below()
        }
    }
}

@Composable
fun HeaderIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(painterResource(icon), description, Modifier.size(26.dp)) }
}

/** Wi-Fi / USB style tabs on the turquoise header: icon + visible label, selected state exposed to TalkBack. */
@Composable
fun HeaderTabs(tabs: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        tabs.forEachIndexed { i, (icon, label) ->
            val on = i == selected
            val tone = Palette.OnTurquoise.copy(alpha = if (on) 1f else 0.82f)
            Column(
                Modifier.weight(1f).selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tone)
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tone, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) Palette.OnTurquoise else Color.Transparent))
            }
        }
    }
}

/** The selected printer, shown under the title on Home and Print. Tapping it opens the printer list. */
@Composable
fun PrinterBar(printer: Printer?, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = "Choose printer", onClick = onClick) else it }
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(R.drawable.ic_printer, size = 46.dp, background = Color.White, tint = Palette.TurquoiseDeep, shape = CircleShape)
        Column(Modifier.weight(1f)) {
            Text(printer?.name ?: "No printer selected", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (printer != null) printerSubtitle(printer) else "Tap to choose a Wi-Fi or USB printer",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.OnTurquoise.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(26.dp))
    }
}

fun printerSubtitle(p: Printer) = when {
    p.usb != null -> "USB (OTG)"
    p.ble != null -> "Bluetooth bridge"
    else -> "${p.transport} · ${p.host}"
}

fun printerIcon(p: Printer) = when {
    p.usb != null -> R.drawable.ic_usb
    p.ble != null -> R.drawable.ic_bluetooth
    else -> R.drawable.ic_printer
}

@Composable
fun IconTile(
    @DrawableRes icon: Int,
    size: Dp = 56.dp,
    background: Color = Palette.Paper,
    tint: Color = Palette.TurquoiseDeep,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
) {
    Box(Modifier.size(size).clip(shape).background(background), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.54f), tint = tint)
    }
}

/** Large tappable row with an icon tile: the Home actions. One surface layer: grey fill + soft shadow. */
@Composable
fun ActionCard(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Palette.Mist,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(icon, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            }
            Icon(painterResource(R.drawable.ic_chevron), null, tint = Palette.InkSoft)
        }
    }
}

/** A plain section card for grouped content. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Palette.Mist,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Palette.Ink,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
    )
}

enum class Tone { Info, Warning, Error }

/** Status message. Amber = needs attention, red = failure; each tone has its own icon and a spoken label, never colour alone. */
@Composable
fun Banner(text: String, tone: Tone) {
    val (bg, icon, accent, label) = when (tone) {
        Tone.Info -> BannerStyle(Palette.Mist, R.drawable.ic_info, Palette.InkSoft, "Info")
        Tone.Warning -> BannerStyle(Palette.AmberTint, R.drawable.ic_warning, Palette.AmberDeep, "Warning")
        Tone.Error -> BannerStyle(Palette.RedTint, R.drawable.ic_error, Palette.Red, "Error")
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(bg).padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
    }
}

private data class BannerStyle(val bg: Color, val icon: Int, val accent: Color, val label: String)

/** Scanning / working state with a real spinner. */
@Composable
fun ProgressBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Palette.Mist).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Turquoise, strokeWidth = 2.5.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
    }
}

/** Row in a list of printers. Selection = turquoise border + tint + check icon. */
@Composable
fun PrinterRow(p: Printer, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Palette.TurquoiseTint else Palette.Mist,
        border = if (selected) BorderStroke(2.dp, Palette.Turquoise) else null,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(printerIcon(p), size = 48.dp, shape = CircleShape)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(printerSubtitle(p), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            }
            if (selected) Icon(painterResource(R.drawable.ic_check), "Selected", tint = Palette.TurquoiseDeep)
        }
    }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Mist), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(36.dp), tint = Palette.InkSoft)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, textAlign = TextAlign.Center)
    }
}

/** One step of the "connect by USB" guide: numbered because the order matters. */
@Composable
fun StepRow(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.TurquoiseTint), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = Palette.TurquoiseDeep)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Filled turquoise primary button. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Turquoise, contentColor = Palette.OnTurquoise,
            disabledContainerColor = Palette.MistStrong, disabledContentColor = Palette.InkSoft,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Secondary action: outlined turquoise, text in the deep turquoise. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.5.dp, Palette.Turquoise),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TurquoiseDeep) }
}

/** Toggle / choice chip. Selected = turquoise fill + a check mark, so it never relies on colour alone. */
@Composable
fun SoftChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) }) else null,
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Palette.Paper,
            labelColor = Palette.Ink,
            selectedContainerColor = Palette.Turquoise,
            selectedLabelColor = Palette.OnTurquoise,
            selectedLeadingIconColor = Palette.OnTurquoise,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Palette.InkSoft.copy(alpha = 0.5f), selectedBorderColor = Palette.Turquoise,
        ),
    )
}

/** A label on the left with chips on the right: one option per line, named. */
@Composable
fun OptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.width(72.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Indeterminate progress with a spoken description. */
@Composable
fun BusyBar(description: String) {
    LinearProgressIndicator(
        Modifier.fillMaxWidth().semantics { contentDescription = description },
        color = Palette.Turquoise, trackColor = Palette.MistStrong,
    )
}
