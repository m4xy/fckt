package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.syntax.Token
import java.io.File

/**
 * How code is laid out: a line no wider than [WIDTH] columns, where it can be. A call's
 * arguments, a declaration's parameters or a list's items that don't fit on the line they start
 * on go one to a line, each with a comma after it, and the bracket that closes them on a line of
 * its own: what `fckt --format` does, and `fckt --check-format` checks.
 */
object Layout {
    const val WIDTH = 100

    /** [source], laid out. */
    fun formatted(source: String): String {
        var text = source
        repeat(1000) {
            val next = splitOnce(text) ?: return text
            text = next
        }
        return text
    }

    /** The lines of [source] that [formatted] would change, by number. */
    fun unformatted(source: String): List<Int> {
        val tokens = tokensOf(source) ?: return emptyList()
        val lines = source.split('\n')
        return lines.indices.filter { i ->
            lines[i].length > WIDTH &&
                (
                    splittable(tokens, lines, i + 1) != null ||
                        annotationsApart(tokens, i + 1, lines[i], lines[i].takeWhile { it == ' ' })?.takeIf { it.last().length <= WIDTH } != null
                    )
        }.map { it + 1 }
    }

    /** [source] with its first line too wide that has a list in it split, or null if none has. */
    private fun splitOnce(source: String): String? {
        val tokens = tokensOf(source) ?: return null
        val lines = source.split('\n').toMutableList()
        for (i in lines.indices) {
            if (lines[i].length <= WIDTH) continue
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }
            val (open, close, commas) = splittable(tokens, lines, i + 1) ?: run {
                val apart = annotationsApart(tokens, i + 1, line, indent)?.takeIf { it.last().length <= WIDTH } ?: return@run null
                lines.removeAt(i)
                lines.addAll(i, apart)
                return lines.joinToString("\n")
            } ?: continue
            val cuts = listOf(open.pos.col) + commas.map { it.pos.col } + listOf(close.pos.col)
            val items = cuts.zipWithNext { from, to -> line.substring(from, to - 1).trim() }.filter { it.isNotEmpty() }
            // A module's parameters, or the modules a functor's given, take no comma after the last.
            val moduleList = tokens.any { it.pos.line == i + 1 && it.pos.col < open.pos.col && it.kind == T.MODULE }
            val replaced = annotatedApart(tokens, i + 1, line.substring(0, open.pos.col).trimEnd(), indent) +
                items.mapIndexed { n, it -> "$indent  $it" + if (moduleList && n == items.size - 1) "" else "," } +
                listOf(indent + line.substring(close.pos.col - 1))
            lines.removeAt(i)
            lines.addAll(i, replaced)
            return lines.joinToString("\n")
        }
        return null
    }

    /**
     * A declaration's first line, [first], its annotations kept on it, as a function's effects are
     * part of what it is: unless they make it too wide, when each goes on a line of its own above.
     */
    private fun annotatedApart(tokens: List<Token>, line: Int, first: String, indent: String): List<String> =
        if (first.length <= WIDTH) listOf(first) else annotationsApart(tokens, line, first, indent) ?: listOf(first)

    /** The line's annotations, at its start, each on a line of its own, and what follows them; null if it starts with none. */
    private fun annotationsApart(tokens: List<Token>, line: Int, first: String, indent: String): List<String>? {
        val onLine = tokens.filter { it.pos.line == line }
        val ends = mutableListOf<Int>()
        var i = 0
        while (i < onLine.size && onLine[i].kind == T.AT) {
            i++
            if (i < onLine.size && onLine[i].kind == T.IDENT) i++
            while (i + 1 < onLine.size && onLine[i].kind == T.DOT && onLine[i + 1].kind == T.IDENT) i += 2
            if (i < onLine.size && onLine[i].kind == T.LPAREN && onLine[i].pos.col == onLine[i - 1].pos.col + onLine[i - 1].text.length) {
                var depth = 0
                while (i < onLine.size) {
                    if (onLine[i].kind == T.LPAREN) depth++
                    if (onLine[i].kind == T.RPAREN && --depth == 0) break
                    i++
                }
                i++
            }
            ends += (onLine.getOrNull(i)?.pos?.col ?: return null) - 1
        }
        if (ends.isEmpty() || first.substring(0, indent.length).isNotBlank()) return null
        val starts = listOf(indent.length) + ends.dropLast(1)
        return starts.zip(ends).map { (from, to) -> indent + first.substring(from, to).trim() } + (indent + first.substring(ends.last()).trim())
    }

    /**
     * The list to split on [line]: a call's arguments, a declaration's parameters or a list's items,
     * between brackets, `(` or `[`, opened and closed on it, at the outermost level of brackets the
     * line reaches: a declaration's parameters before anything in its body, and otherwise the last,
     * as a chain of calls ends with what's long. Not a condition, a lambda's or a
     * function type's parameters, or a list that ends with what's spread into it. With its closing
     * bracket and the commas between them that are its own.
     */
    private fun splittable(tokens: List<Token>, lines: List<String>, line: Int): Triple<Token, Token, List<Token>>? {
        val depths = depthsOf(tokens)
        val onLine = tokens.indices.filter { tokens[it].pos.line == line }
        if (onLine.isEmpty()) return null
        val outermost = onLine.minOf { depths[it] }
        val found = mutableListOf<Triple<Int, Int, List<Int>>>()
        for (index in onLine) {
            val open = tokens[index]
            if (depths[index] != outermost || !opens(tokens, index) || open.kind == T.LT) continue
            if (open.kind != T.LPAREN && open.kind != T.LBRACKET) break
            val close = (index + 1 until tokens.size).firstOrNull { depths[it] == outermost && closes(tokens, it) } ?: break
            if (tokens[close].pos.line != line) break
            val before = tokens.getOrNull(index - 1)
            if (close == index + 1 || tokens.getOrNull(close + 1)?.kind == T.ARROW) continue
            if (before?.kind in setOf(T.IF, T.WHEN) || before?.text in setOf("while", "for")) continue
            if (open.kind == T.LPAREN && before?.kind != T.IDENT && before?.kind != T.GT) continue
            val commas = (index + 1 until close).filter { tokens[it].kind == T.COMMA && depths[it] == outermost + 1 }
            if (tokens[commas.lastOrNull()?.let { it + 1 } ?: (index + 1)].kind == T.STAR) continue
            found += Triple(index, close, commas)
        }
        val first = found.firstOrNull() ?: return null
        val declares = tokens[first.first].kind == T.LPAREN && tokens.getOrNull(first.second + 1)?.kind in setOf(T.COLON, T.ASSIGN)
        val (open, close, commas) = if (declares) first else found.last()
        return Triple(tokens[open], tokens[close], commas.map { tokens[it] })
    }

    /** Each token's depth: how many brackets are open before it, a generic's <...> among them. */
    private fun depthsOf(tokens: List<Token>): IntArray {
        val depths = IntArray(tokens.size)
        var depth = 0
        for (i in tokens.indices) {
            if (closes(tokens, i)) depth--
            depths[i] = depth
            if (opens(tokens, i)) depth++
        }
        return depths
    }

    private fun opens(tokens: List<Token>, i: Int): Boolean = when (tokens[i].kind) {
        T.LPAREN, T.LBRACKET, T.LBRACE -> true
        T.LT -> typeArguments(tokens, i)
        else -> false
    }

    private fun closes(tokens: List<Token>, i: Int): Boolean = when (tokens[i].kind) {
        T.RPAREN, T.RBRACKET, T.RBRACE -> true
        T.GT -> matchingLt(tokens, i) != null
        else -> false
    }

    /** Whether the < at [i] starts type arguments, List<A>: right after a name, with no space. */
    private fun typeArguments(tokens: List<Token>, i: Int): Boolean {
        val before = tokens.getOrNull(i - 1) ?: return false
        if (before.kind != T.IDENT || before.pos.line != tokens[i].pos.line || before.pos.col + before.text.length != tokens[i].pos.col) return false
        return matchingGt(tokens, i) != null
    }

    private fun matchingGt(tokens: List<Token>, i: Int): Int? {
        var depth = 0
        for (j in i + 1 until tokens.size) {
            when (tokens[j].kind) {
                T.LT -> depth++
                T.GT -> if (depth == 0) return j else depth--
                T.LBRACE, T.RBRACE, T.NEWLINE, T.ASSIGN, T.SEMI -> return null
                else -> {}
            }
        }
        return null
    }

    private fun matchingLt(tokens: List<Token>, i: Int): Int? {
        var depth = 0
        for (j in i - 1 downTo 0) {
            when (tokens[j].kind) {
                T.GT -> depth++
                T.LT -> if (depth == 0) return j.takeIf { typeArguments(tokens, it) } else depth--
                T.LBRACE, T.RBRACE, T.NEWLINE, T.ASSIGN, T.SEMI -> return null
                else -> {}
            }
        }
        return null
    }

    private fun tokensOf(source: String): List<Token>? = try {
        Lexer(source).tokenize()
    } catch (e: Exception) {
        null
    }

    /** Lays out each file [paths] name, and those in each folder they name; or, [checking], says which aren't. */
    fun run(paths: List<String>, checking: Boolean): Int {
        val files = paths.flatMap { p ->
            val f = File(p)
            if (f.isDirectory) f.walk().filter { it.isFile && it.extension == "fk" && "build" !in it.relativeTo(f).invariantSeparatorsPath.split('/') }.toList() else listOf(f)
        }.sortedBy { it.path }
        var unformatted = 0
        for (file in files) {
            val source = file.readText()
            if (checking) {
                for (line in unformatted(source)) {
                    System.err.println("${file.path}:$line: wider than $WIDTH columns: put each item of the list on a line of its own, or run fckt --format")
                    unformatted++
                }
            } else {
                val laidOut = formatted(source)
                if (laidOut != source) file.writeText(laidOut)
            }
        }
        return if (unformatted > 0) 1 else 0
    }
}
