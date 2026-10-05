package com.crossberry.noxs.terminal.emulator

/** A terminal cell position anchored to a stable row identity, not a screen y. */
data class TerminalSelectionPoint(val lineIdentity: Long, val column: Int)

data class TerminalSelection(
    val anchor: TerminalSelectionPoint,
    val focus: TerminalSelectionPoint
)

data class ResolvedTerminalSelection(
    val firstRow: Int,
    val firstColumn: Int,
    val lastRow: Int,
    val lastColumn: Int,
    val anchorRow: Int,
    val anchorColumn: Int,
    val focusRow: Int,
    val focusColumn: Int
)

/** Resolve a selection against the active terminal document. */
fun TerminalBuffer.resolveSelection(selection: TerminalSelection): ResolvedTerminalSelection? {
    val anchorRow = documentRowOf(selection.anchor.lineIdentity) ?: return null
    val focusRow = documentRowOf(selection.focus.lineIdentity) ?: return null
    val anchorLine = documentLineAt(anchorRow) ?: return null
    val focusLine = documentLineAt(focusRow) ?: return null
    val anchorColumn = selection.anchor.column.coerceIn(0, anchorLine.chars.lastIndex.coerceAtLeast(0))
    val focusColumn = selection.focus.column.coerceIn(0, focusLine.chars.lastIndex.coerceAtLeast(0))
    val anchorFirst = anchorRow < focusRow || (anchorRow == focusRow && anchorColumn <= focusColumn)
    return if (anchorFirst) {
        ResolvedTerminalSelection(
            anchorRow, anchorColumn, focusRow, focusColumn,
            anchorRow, anchorColumn, focusRow, focusColumn
        )
    } else {
        ResolvedTerminalSelection(
            focusRow, focusColumn, anchorRow, anchorColumn,
            anchorRow, anchorColumn, focusRow, focusColumn
        )
    }
}

/** Exact selected grid text for clipboard use; soft-wrapped rows have no newline. */
fun TerminalSelection.copyText(buffer: TerminalBuffer): String? {
    val range = buffer.resolveSelection(this) ?: return null
    return buildString {
        for (row in range.firstRow..range.lastRow) {
            val line = buffer.documentLineAt(row) ?: continue
            val count = minOf(line.chars.size, line.styles.size)
            if (count > 0) {
                val from = (if (row == range.firstRow) range.firstColumn else 0).coerceIn(0, count - 1)
                val to = if (row == range.lastRow) {
                    range.lastColumn.coerceIn(from, count - 1)
                } else if (line.lineWrap) {
                    count - 1
                } else {
                    // Do not copy the terminal's unused right-hand padding on
                    // hard lines. Spaces inside the text (including expanded
                    // tabs) remain untouched, and an explicitly selected end
                    // cell on the final row is always preserved.
                    minOf(count - 1, line.text().length - 1)
                }
                if (to >= from) {
                    for (column in from..to) {
                        if (!TextStyle.isWideCont(line.styles[column])) append(line.chars[column])
                    }
                }
            }
            if (row < range.lastRow && !line.lineWrap) append('\n')
        }
    }
}

/** Select all retained scrollback plus the meaningful part of the active screen. */
fun TerminalBuffer.selectAllText(): TerminalSelection? {
    val rowCount = documentRowCount()
    if (rowCount <= 0) return null
    val firstLine = documentLineAt(0) ?: return null
    var lastRow = rowCount - 1
    while (lastRow > 0 && documentLineAt(lastRow)?.text().isNullOrEmpty()) lastRow--
    val lastLine = documentLineAt(lastRow) ?: return null
    val lastColumn = (lastLine.text().length - 1).coerceAtLeast(0)
    return TerminalSelection(
        TerminalSelectionPoint(firstLine.identity, 0),
        TerminalSelectionPoint(lastLine.identity, lastColumn)
    )
}
