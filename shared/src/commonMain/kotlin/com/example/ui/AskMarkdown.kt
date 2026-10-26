package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The pieces of an answer: the small part of markdown Claude's answers use. */
sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListBlock(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val head: List<String>, val rows: List<List<String>>) : MdBlock
}

private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{2,}""")
private val HEADING = Regex("""^#{1,4}\s""")
private val LIST_ITEM = Regex("""^\s*([-*]|\d+\.)\s+""")
private val ORDERED = Regex("""^\s*\d+\.""")

/** Splits an answer into blocks, the same rules as the dashboard's renderer. */
fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r", "").split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0
    fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) body += lines[i++]
                i++
                out += MdBlock.Code(body.joinToString("\n"))
            }
            TABLE_ROW.matches(line) && TABLE_RULE.containsMatchIn(lines.getOrElse(i + 1) { "" }) -> {
                val head = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && TABLE_ROW.matches(lines[i])) rows += cells(lines[i++])
                out += MdBlock.Table(head, rows)
            }
            HEADING.containsMatchIn(line) -> {
                out += MdBlock.Heading(line.trimStart('#').trim())
                i++
            }
            LIST_ITEM.containsMatchIn(line) -> {
                val ordered = ORDERED.containsMatchIn(line)
                val items = mutableListOf<String>()
                while (i < lines.size && LIST_ITEM.containsMatchIn(lines[i])) items += lines[i++].replace(LIST_ITEM, "")
                out += MdBlock.ListBlock(items, ordered)
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("```") &&
                    !HEADING.containsMatchIn(lines[i]) && !LIST_ITEM.containsMatchIn(lines[i]) &&
                    !lines[i].trimStart().startsWith("|")
                ) para += lines[i++]
                if (para.isEmpty()) para += lines[i++]
                out += MdBlock.Paragraph(para.joinToString(" "))
            }
        }
    }
    return out
}

private val INLINE = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*\s][^*]*)\*""")

/** **bold**, *italic* and `code` as styled text; everything else as written. */
fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(at, m.range.first))
        val (code, bold, italic) = m.destructured
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}

@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parseMarkdown(src).forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.ListBlock -> Column {
                    block.items.forEachIndexed { n, item ->
                        Row {
                            Text(
                                if (block.ordered) "${n + 1}. " else "• ",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(inlineMarkdown(item), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is MdBlock.Code -> CodeBox(block.text)
                is MdBlock.Table -> MdTable(block)
            }
        }
    }
}

@Composable
fun CodeBox(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    )
}

/** A table that scrolls sideways when it is wider than the phone. */
@Composable
private fun MdTable(table: MdBlock.Table) {
    val columns = maxOf(table.head.size, table.rows.maxOfOrNull { it.size } ?: 0)
    // Width from the longest cell, so short columns stay narrow.
    val widths = (0 until columns).map { c ->
        val longest = (listOf(table.head) + table.rows).maxOf { it.getOrElse(c) { "" }.length }
        (longest * 7 + 16).coerceIn(44, 200).dp
    }
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            (0 until columns).forEach { c ->
                Text(
                    inlineMarkdown(table.head.getOrElse(c) { "" }),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(widths[c]).padding(end = 8.dp, bottom = 2.dp)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }))
        table.rows.forEach { row ->
            Row {
                (0 until columns).forEach { c ->
                    Text(
                        inlineMarkdown(row.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(widths[c]).padding(end = 8.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}
