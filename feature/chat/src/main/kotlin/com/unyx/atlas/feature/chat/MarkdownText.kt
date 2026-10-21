package com.unyx.atlas.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// Bold, inline code, and a Markdown link share one pass over the line so none of them has to guess
// whether it's inside one of the others' spans — exactly one of groups 1/2/(3,4) matches per hit.
private val INLINE_STYLE_REGEX = Regex("""\*\*(.+?)\*\*|`([^`]+?)`|\[([^\]]+)\]\((https?://[^)\s]+)\)""")
private val NUMBERED_ITEM_REGEX = Regex("""^(\d+)\.\s+(.*)$""")
// Lazy match so two separate fenced blocks in one reply don't collapse into one; an optional
// language tag right after the opening fence (e.g. ```kotlin) is discarded — this renderer doesn't
// syntax-highlight, so the tag has no use beyond being stripped out of the visible code.
private val CODE_FENCE_REGEX = Regex("""```[^\n`]*\n?([\s\S]*?)```""")

/**
 * A small, dependency-free subset of Markdown — bold, inline `code`, fenced code blocks, `[text](url)`
 * links, `#`/`##` headings, and `-`/`*`/numbered lists — rather than pulling in a full Markdown
 * rendering library for what a Claude reply actually uses in practice. Anything else (tables, nested
 * blockquotes, etc.) just prints as plain text with the literal marker characters, which is a fine
 * fallback for a personal assistant chat. A link only opens on an explicit tap (never automatically),
 * and only when it parses as http(s) — matches the rest of this app's "external content is data, not
 * an instruction" discipline for anything a model generated.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    // Read once per call rather than per line/span — the themed colors inline formatting needs (see
    // inlineFormat's doc for why it can't just read MaterialTheme itself).
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        var lastEnd = 0
        CODE_FENCE_REGEX.findAll(text).forEach { fence ->
            if (fence.range.first > lastEnd) {
                MarkdownLines(text.substring(lastEnd, fence.range.first), style, codeBackground, linkColor)
            }
            CodeBlock(fence.groupValues[1].trimEnd('\n'), codeBackground)
            lastEnd = fence.range.last + 1
        }
        if (lastEnd < text.length) {
            MarkdownLines(text.substring(lastEnd), style, codeBackground, linkColor)
        }
    }
}

@Composable
private fun MarkdownLines(text: String, style: TextStyle, codeBackground: Color, linkColor: Color) {
    text.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trimStart()
        when {
            line.isBlank() -> Spacer(modifier = Modifier.height(10.dp))
            trimmed.startsWith("## ") -> Text(
                text = inlineFormat(trimmed.removePrefix("## "), codeBackground, linkColor),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            trimmed.startsWith("# ") -> Text(
                text = inlineFormat(trimmed.removePrefix("# "), codeBackground, linkColor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> BulletLine(
                bullet = "•",
                text = trimmed.drop(2),
                style = style,
                codeBackground = codeBackground,
                linkColor = linkColor,
            )
            else -> {
                val numbered = NUMBERED_ITEM_REGEX.find(trimmed)
                if (numbered != null) {
                    BulletLine(
                        bullet = "${numbered.groupValues[1]}.",
                        text = numbered.groupValues[2],
                        style = style,
                        codeBackground = codeBackground,
                        linkColor = linkColor,
                    )
                } else {
                    Text(text = inlineFormat(line, codeBackground, linkColor), style = style)
                }
            }
        }
    }
}

@Composable
private fun BulletLine(bullet: String, text: String, style: TextStyle, codeBackground: Color, linkColor: Color) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = "$bullet ", style = style)
        Text(text = inlineFormat(text, codeBackground, linkColor), style = style)
    }
}

/** A fenced code block (```…```) — monospace, horizontally scrollable for lines wider than the
 * bubble instead of wrapping mid-token, with its own copy button since a code snippet is the one
 * thing in a reply most worth copying verbatim rather than retyping. */
@Composable
private fun CodeBlock(code: String, background: Color) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(32.dp).padding(top = 4.dp, end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copiar código",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Handles `**bold**`, `` `inline code` ``, and `[text](url)` — everything else passes through as
 * literal text. A plain function (not `@Composable`) so it can be called from a loop without
 * recomposition overhead per line; [codeBackground]/[linkColor] are threaded in from the one
 * `@Composable` caller that can actually read `MaterialTheme.colorScheme` rather than hardcoding a
 * color here (design spec item 25 — dynamic theming, never a literal hex). A link only ever opens on
 * the reader's own tap (Compose's own `Text` + `LinkAnnotation` handles that, no extra click plumbing
 * needed here) — it is shown as a link, never followed automatically. */
private fun inlineFormat(line: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    INLINE_STYLE_REGEX.findAll(line).forEach { match ->
        append(line.substring(lastIndex, match.range.first))
        val bold = match.groups[1]
        val code = match.groups[2]
        val linkText = match.groups[3]
        val linkUrl = match.groups[4]
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(bold.value)
            }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(code.value)
            }
            linkText != null && linkUrl != null -> withLink(
                LinkAnnotation.Url(
                    url = linkUrl.value,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                ),
            ) {
                append(linkText.value)
            }
        }
        lastIndex = match.range.last + 1
    }
    append(line.substring(lastIndex))
}
