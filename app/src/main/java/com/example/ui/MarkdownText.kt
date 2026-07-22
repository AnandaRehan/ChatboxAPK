package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!text.contains("```")) {
            // No triple backticks, simple markdown block
            StandardMarkdownBlock(text = text, textColor = textColor)
        } else {
            // Split by triple backticks for block code segments
            val segments = text.split("```")
            for (index in segments.indices) {
                val segment = segments[index]
                if (index % 2 == 1) {
                    // Segment is a code block
                    val lines = segment.split("\n")
                    val possibleLang = lines.firstOrNull()?.trim() ?: ""
                    
                    val (language, codeBody) = if (possibleLang.isNotEmpty() && possibleLang.all { it.isLetterOrDigit() }) {
                        possibleLang to lines.drop(1).joinToString("\n")
                    } else {
                        "code" to segment
                    }
                    
                    if (codeBody.trim().isNotEmpty()) {
                        CodeBox(codeText = codeBody.trim(), language = language)
                    }
                } else {
                    // Standard markdown segment
                    if (segment.isNotEmpty()) {
                        StandardMarkdownBlock(text = segment, textColor = textColor)
                    }
                }
            }
        }
    }
}

@Composable
fun StandardMarkdownBlock(
    text: String,
    textColor: Color
) {
    val lines = text.split("\n")
    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                Text(
                    text = trimmed.substring(4),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            trimmed.startsWith("## ") -> {
                Text(
                    text = trimmed.substring(3),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
            }
            trimmed.startsWith("# ") -> {
                Text(
                    text = trimmed.substring(2),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "• ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = parseInlineMarkdown(trimmed.substring(2)),
                        fontSize = 14.sp,
                        color = textColor
                    )
                }
            }
            else -> {
                if (trimmed.isNotEmpty()) {
                    Text(
                        text = parseInlineMarkdown(line),
                        fontSize = 14.sp,
                        color = textColor,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBox(
    codeText: String,
    language: String
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E24)) // Dark background for code
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2E2E38))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.uppercase(),
                color = Color(0xFFC5C5D2),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Code", codeText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Kode disalin ke clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = Color(0xFFC5C5D2),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        
        // Code Text
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = codeText,
                color = Color(0xFFE3E3E3),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Parses inline markdown elements such as bold (**text**), italic (*text*), and inline code (`code`)
 */
fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        
        while (cursor < text.length) {
            val nextBold = text.indexOf("**", cursor)
            val nextItalic = text.indexOf("*", cursor)
            val nextInlineCode = text.indexOf("`", cursor)
            
            // Find the closest formatting symbol
            val nextEvent = listOf(
                if (nextBold != -1) nextBold else Int.MAX_VALUE,
                if (nextItalic != -1) nextItalic else Int.MAX_VALUE,
                if (nextInlineCode != -1) nextInlineCode else Int.MAX_VALUE
            ).minOrNull() ?: Int.MAX_VALUE
            
            if (nextEvent == Int.MAX_VALUE) {
                // No more symbols, append rest of text
                append(text.substring(cursor))
                break
            }
            
            // Append plain text up to the closest event
            if (nextEvent > cursor) {
                append(text.substring(cursor, nextEvent))
                cursor = nextEvent
            }
            
            when (nextEvent) {
                nextBold -> {
                    val endBold = text.indexOf("**", cursor + 2)
                    if (endBold != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(cursor + 2, endBold))
                        pop()
                        cursor = endBold + 2
                    } else {
                        append("**")
                        cursor += 2
                    }
                }
                nextItalic -> {
                    val endItalic = text.indexOf("*", cursor + 1)
                    if (endItalic != -1) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(cursor + 1, endItalic))
                        pop()
                        cursor = endItalic + 1
                    } else {
                        append("*")
                        cursor += 1
                    }
                }
                nextInlineCode -> {
                    val endInlineCode = text.indexOf("`", cursor + 1)
                    if (endInlineCode != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x1F888888),
                                color = Color(0xFFE11D48) // pinkish/red inline code text
                            )
                        )
                        append(text.substring(cursor + 1, endInlineCode))
                        pop()
                        cursor = endInlineCode + 1
                    } else {
                        append("`")
                        cursor += 1
                    }
                }
            }
        }
    }
}
