package uk.m4xy.fckt.intellij

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.formatting.WrapType
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/**
 * Formats fckt as ktlint formats Kotlin, with fckt's two-space indent: a level for each bracket,
 * and one more for a line that continues the one before it; one space around operators, after
 * commas and colons and inside braces, and none inside parentheses, around `.` or before a call's
 * arguments; at most one blank line, and none after an opening bracket or before a closing one.
 * A list across lines has an item on each, and one too long for the margin is chopped down;
 * braces across lines have what's in them on lines of its own. The same blocks tell the editor
 * where the cursor goes after Enter.
 */
class FcktFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(context: FormattingContext): FormattingModel {
        val file = context.containingFile
        return FormattingModelProvider.createFormattingModelForPsiFile(file, Layout(file.text).root, context.codeStyleSettings)
    }
}

/** A token the formatter places: anything but whitespace. [i] is its place among them. */
class Atom(val tok: Tok, val text: String, val i: Int) {
    val kind get() = tok.kind
    val range get() = TextRange(tok.start, tok.end)
    val isComment get() = kind == TokKind.LINE_COMMENT || kind == TokKind.BLOCK_COMMENT
}

/** A token, or brackets and what's between them. */
sealed interface Item {
    val first: Atom
    val last: Atom
}

class Leaf(val atom: Atom) : Item {
    override val first get() = atom
    override val last get() = atom
}

/** A bracket and what follows it, to its closing bracket if there is one yet. */
class Brackets(val open: Atom, val items: List<Item>, val close: Atom?) : Item {
    override val first get() = open
    override val last get() = close ?: items.lastOrNull()?.last ?: open
}

/** Operators that are a line's last only when the next line continues it, as the lexer has it. */
private val CONTINUES = setOf("=", "->", ".", ":", "|>", "~", "!", "+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=")

/**
 * A file's tokens, nested by brackets, and the blocks IntelliJ formats them by. A bracket's
 * contents are statements, each a first line and the lines that continue it, so that a group
 * opened on a continuing line is indented from that line.
 */
class Layout(private val text: String) {
    val atoms: List<Atom> = scan(text).filter { it.kind != TokKind.WHITESPACE }.mapIndexed { i, tok -> Atom(tok, text.substring(tok.start, tok.end), i) }

    /** `<` and `>` around a type's arguments, rather than comparing. */
    private val typeBrackets = mutableSetOf<Int>()

    /** Operators applied to what follows them: `!`, `~`, `-1`, `*rest`. */
    private val prefix = mutableSetOf<Int>()

    /** The `:` before a module's spec, `module Name : Spec`, which has a space on both sides. */
    private val specColons = mutableSetOf<Int>()

    val root: Block

    init {
        for (a in atoms) {
            if (a.text == "<" && a.i !in typeBrackets) markTypeArguments(a.i)
            if (a.kind == TokKind.OPERATOR && a.i !in typeBrackets && isPrefix(a)) prefix += a.i
            if (a.kind == TokKind.COLON && isSpecColon(a.i)) specColons += a.i
        }
        root = GroupBlock(null, items(), null, Indent.getNoneIndent())
    }

    /** Nests the tokens by brackets, except those in a string, whose braces are its templates'. */
    private fun items(): List<Item> {
        val stack = ArrayDeque<Pair<Atom?, MutableList<Item>>>()
        stack.addLast(null to mutableListOf())
        for (a in atoms) {
            when {
                a.tok.nested -> stack.last().second += Leaf(a)
                a.kind in OPENERS -> stack.addLast(a to mutableListOf())
                a.kind in CLOSERS && stack.size > 1 -> {
                    val (open, items) = stack.removeLast()
                    stack.last().second += Brackets(open!!, items, a)
                }
                else -> stack.last().second += Leaf(a)
            }
        }
        // Brackets not closed yet, as while typing, close at the end.
        while (stack.size > 1) {
            val (open, items) = stack.removeLast()
            stack.last().second += Brackets(open!!, items, null)
        }
        return stack.single().second
    }

    /** If the `<` at [i] opens type arguments, `List<A>` or `M.M<(A, B)>` with no space before, adds them to [typeBrackets]. */
    private fun markTypeArguments(i: Int) {
        val before = atoms.getOrNull(i - 1) ?: return
        if (before.kind != TokKind.IDENT || before.tok.end != atoms[i].tok.start) return
        var depth = 0
        var parens = 0
        for (j in i until atoms.size) {
            val a = atoms[j]
            when {
                a.text == "<" -> depth++
                a.text == ">" -> if (--depth == 0) {
                    if (parens == 0) typeBrackets += (i..j).filter { atoms[it].text == "<" || atoms[it].text == ">" }
                    return
                }
                a.kind == TokKind.LPAREN -> parens++
                a.kind == TokKind.RPAREN -> if (--parens < 0) return
                a.kind !in setOf(TokKind.IDENT, TokKind.COMMA, TokKind.DOT, TokKind.ARROW) -> return
            }
        }
    }

    /** Whether the operator [a] applies to what follows it rather than to both sides. */
    private fun isPrefix(a: Atom): Boolean {
        if (a.text == "!" || a.text == "~") return true
        if (a.text != "-" && a.text != "*" && a.text != "+") return false
        val before = atoms.getOrNull(a.i - 1) ?: return true
        return when (before.kind) {
            TokKind.IDENT, TokKind.NUMBER, TokKind.STRING, TokKind.CHAR, TokKind.RPAREN, TokKind.RBRACKET, TokKind.RBRACE -> false
            TokKind.KEYWORD -> before.text !in setOf("true", "false", "this")
            TokKind.OPERATOR -> before.i !in typeBrackets
            else -> true
        }
    }

    /** Whether the `:` at [i] follows a module's name (and its parameters), back to `module`. */
    private fun isSpecColon(i: Int): Boolean {
        var j = i - 1
        var parens = 0
        while (j >= 0) {
            val a = atoms[j]
            when {
                a.kind == TokKind.RPAREN -> parens++
                a.kind == TokKind.LPAREN -> parens--
                parens > 0 -> {}
                a.kind == TokKind.KEYWORD && a.text == "module" -> return true
                a.kind != TokKind.IDENT -> return false
            }
            if (parens < 0) return false
            j--
        }
        return false
    }

    /** Whether there's a line break between the token before [a] and [a]. */
    private fun startsLine(a: Atom): Boolean {
        val before = atoms.getOrNull(a.i - 1) ?: return true
        return text.indexOf('\n', before.tok.end).let { it in before.tok.end until a.tok.start }
    }

    /** Whether a line ending with [a] goes on to the next: after `=`, `->` or an operator. */
    private fun continues(a: Atom?): Boolean =
        a != null && !a.tok.nested && a.text in CONTINUES && a.i !in typeBrackets && a.i !in prefix

    /** A line, as the items on it; [continuing] if it continues the line before it. */
    private class Line(val items: List<Item>, val continuing: Boolean) {
        /** Its last token that isn't a comment, to tell if the next line continues it. */
        val lastCode: Atom? get() = items.lastOrNull { !(it is Leaf && it.atom.isComment) }?.last
    }

    /** [items] as statements: lines, each but a statement's first continuing the one before. */
    private fun statements(items: List<Item>): List<List<Line>> {
        val lines = mutableListOf<MutableList<Item>>()
        for (item in items) {
            if (lines.isEmpty() || startsLine(item.first)) lines += mutableListOf<Item>()
            lines.last() += item
        }
        val statements = mutableListOf<MutableList<Line>>()
        // The last line with code on it, which says if the next continues it.
        var code: Line? = null
        for (items in lines) {
            val head = items.first().first
            val line = when {
                code == null -> Line(items, false)
                // `else` lines up with the `if` it belongs to.
                head.text == "else" -> Line(items, code.continuing)
                continues(code.lastCode) || head.text == "." || head.text == "|>" -> Line(items, true)
                else -> null
            }
            if (line == null) statements += mutableListOf(Line(items, false)) else if (statements.isEmpty()) statements += mutableListOf(line) else statements.last() += line
            if (!(items.size == 1 && head.isComment)) code = statements.last().last()
        }
        return statements
    }

    /** Spaces between [a] and the token after it, [b], or null to leave what's there. */
    private fun spaces(a: Atom, b: Atom): Int? {
        val k = a.kind
        val l = b.kind
        if (a.tok.nested || b.tok.nested || (k == TokKind.STRING && l == TokKind.STRING) || k == TokKind.BAD || l == TokKind.BAD) return null
        return when {
            b.isComment || k == TokKind.BLOCK_COMMENT -> 1
            k == TokKind.AT -> 0
            l == TokKind.COMMA || l == TokKind.SEMI -> 0
            k == TokKind.COMMA || k == TokKind.SEMI -> 1
            k == TokKind.LPAREN || k == TokKind.LBRACKET || l == TokKind.RPAREN || l == TokKind.RBRACKET -> 0
            k == TokKind.LBRACE && l == TokKind.RBRACE -> 0
            k == TokKind.LBRACE || l == TokKind.RBRACE -> 1
            k == TokKind.DOT || l == TokKind.DOT -> 0
            l == TokKind.COLON -> if (b.i in specColons) 1 else 0
            k == TokKind.COLON -> 1
            b.i in typeBrackets -> 0
            a.i in typeBrackets -> if (a.text == "<" || l == TokKind.LPAREN) 0 else 1
            a.i in prefix -> 0
            // `@Route("/a")` takes arguments, but `@Console (String) -> Unit` is an effect on a function type.
            l == TokKind.LPAREN && k == TokKind.IDENT && atoms.getOrNull(a.i - 1)?.kind == TokKind.AT -> null
            l == TokKind.LPAREN -> if ((k == TokKind.IDENT && a.text != "by") || a.text == "private" || k == TokKind.RPAREN || k == TokKind.RBRACKET) 0 else 1
            else -> 1
        }
    }

    /** What goes between two blocks: spaces by their tokens; line breaks as they are, with at most one blank line. */
    private fun spacing(left: Block, right: Block): Spacing? {
        val a = (left as FcktBlock).last
        val b = (right as FcktBlock).first
        val n = spaces(a, b) ?: return Spacing.getReadOnlySpacing()
        val blank = if (a.kind in OPENERS || b.kind in CLOSERS) 0 else 1
        // Comments lined up at the ends of lines stay lined up.
        return Spacing.createSpacing(n, if (b.kind == TokKind.LINE_COMMENT) Int.MAX_VALUE else n, 0, true, blank)
    }

    private abstract inner class FcktBlock(private val indent: Indent, private val wrap: Wrap? = null) : Block {
        abstract val first: Atom
        abstract val last: Atom
        override fun getTextRange() = TextRange(first.tok.start, last.tok.end)
        override fun getIndent() = indent
        override fun getWrap() = wrap
        override fun getAlignment(): Alignment? = null
        override fun getSpacing(child1: Block?, child2: Block) = child1?.let { spacing(it, child2) }
        override fun isLeaf() = subBlocks.isEmpty()
    }

    private inner class LeafBlock(val atom: Atom, wrap: Wrap? = null) : FcktBlock(Indent.getNoneIndent(), wrap) {
        override val first get() = atom
        override val last get() = atom
        override fun getSubBlocks() = emptyList<Block>()
        override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(Indent.getNoneIndent(), null)
        override fun isIncomplete() = false
    }

    private fun block(item: Item): FcktBlock = when (item) {
        is Leaf -> LeafBlock(item.atom)
        is Brackets -> GroupBlock(item.open, item.items, item.close, Indent.getNoneIndent())
    }

    /**
     * Brackets and what's in them, one level in; or, with no brackets, the file. In brackets that
     * hold a list, `(a, b)` or `{ A, B }`, each item is a block of its own, so that a list across
     * lines has one item on each, and a list too long for a line is chopped down to one.
     */
    private inner class GroupBlock(val open: Atom?, items: List<Item>, val close: Atom?, indent: Indent) : FcktBlock(indent) {
        private val inner = if (open == null) Indent.getNoneIndent() else Indent.getNormalIndent()
        // Braces hold a list only of a type's cases, `{ A, B }`: not `A, B -> x` in a when.
        private val list = if (open == null || (open.kind == TokKind.LBRACE && items.any { (it as? Leaf)?.atom?.kind in setOf(TokKind.ARROW, TokKind.ASSIGN) })) null else listItems(items)

        /** Whether what's in the brackets is across lines, so each item, and the closing bracket, starts one. */
        private val across = (items.map { it.first } + listOfNotNull(close)).any { startsLine(it) }
        private val wrap = if (list != null && !across) Wrap.createWrap(WrapType.CHOP_DOWN_IF_LONG, true) else null
        private val blocks: List<FcktBlock> = listOfNotNull(open?.let { LeafBlock(it) }) +
            (list?.map { StatementBlock(statements(it).flatten(), inner, wrap) } ?: statements(items).map { StatementBlock(it, inner) }) +
            listOfNotNull(close?.let { LeafBlock(it, wrap) })
        override val first get() = blocks.first().first
        override val last get() = blocks.last().last
        override fun getTextRange(): TextRange = if (blocks.isEmpty()) TextRange(0, text.length) else super.getTextRange()
        override fun getSubBlocks() = blocks
        override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(inner, null)
        override fun isIncomplete() = open != null && close == null

        override fun getSpacing(child1: Block?, child2: Block): Spacing? {
            if (child1 == null) return null
            val edge = child1 === blocks.first() || child2 === blocks.last()
            // Across lines, ktlint puts each item of a list, and what's in braces, on a line of its
            // own, though a comment can stay at the end of the line it's on.
            val own = open != null && across && (list != null || (open.kind == TokKind.LBRACE && edge))
            val b = (child2 as FcktBlock).first
            if (!own || spaces((child1 as FcktBlock).last, b) == null || (b.isComment && !startsLine(b))) return spacing(child1, child2)
            return Spacing.createSpacing(0, 0, 1, true, if (edge) 0 else 1)
        }
    }

    /**
     * [items] split after each comma, or null if there's no comma: not those in a type's arguments,
     * `Map<K, V>`. A comment on the same line as a comma stays with the item before it.
     */
    private fun listItems(items: List<Item>): List<List<Item>>? {
        val split = mutableListOf(mutableListOf<Item>())
        var angles = 0
        var commas = 0
        for (item in items) {
            val a = (item as? Leaf)?.atom
            val last = split.last()
            if (last.isEmpty() && split.size > 1 && a != null && a.isComment && !startsLine(a)) split[split.size - 2] += item else last += item
            if (a == null || a.i !in typeBrackets) {
                if (a?.kind == TokKind.COMMA && angles == 0) {
                    commas++
                    split += mutableListOf<Item>()
                }
            } else if (a.text == "<") angles++ else angles--
        }
        if (split.last().isEmpty()) split.removeLast()
        return if (commas == 0) null else split
    }

    /** A statement: its first line, and the lines that continue it, one level in. */
    private inner class StatementBlock(lines: List<Line>, indent: Indent, wrap: Wrap? = null) : FcktBlock(indent, wrap) {
        private val blocks: List<LineBlock> = lines.map { LineBlock(it) }
        override val first get() = blocks.first().first
        override val last get() = blocks.last().last
        override fun getSubBlocks() = blocks
        override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(Indent.getNormalIndent(), null)
        override fun isIncomplete() = blocks.last().isIncomplete
    }

    private inner class LineBlock(private val line: Line) : FcktBlock(if (line.continuing) Indent.getNormalIndent() else Indent.getNoneIndent()) {
        private val blocks = line.items.map { block(it) }
        override val first get() = blocks.first().first
        override val last get() = blocks.last().last
        override fun getSubBlocks() = blocks
        // A line after this one continues the statement: one level in from its first line.
        override fun getChildAttributes(newChildIndex: Int) =
            ChildAttributes(if (line.continuing) Indent.getNoneIndent() else Indent.getNormalIndent(), null)
        override fun isIncomplete() = continues(line.lastCode)
    }

    private companion object {
        val OPENERS = setOf(TokKind.LPAREN, TokKind.LBRACE, TokKind.LBRACKET)
        val CLOSERS = setOf(TokKind.RPAREN, TokKind.RBRACE, TokKind.RBRACKET)
    }
}

/** Editor > Code Style > fckt: two spaces a level by default, as fckt's signatures are short, `f() = {`. */
class FcktCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = FcktLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.WRAPPING_AND_BRACES_SETTINGS) consumer.showStandardOptions("RIGHT_MARGIN")
    }

    override fun getIndentOptionsEditor() = SmartIndentOptionsEditor()

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "fckt") {
            override fun createPanel(settings: CodeStyleSettings) = object : TabbedLanguageCodeStylePanel(FcktLanguage, currentSettings, settings) {}
        }

    override fun getCodeSample(settingsType: SettingsType) = """
        type Shape { Circle(radius: Float), Square(side: Float) }

        area(s: Shape): Float = when (s) {
          Circle(r) -> 3.0 * r * r
          Square(side) -> side * side
        }

        bounded(x: Int, size: Int) =
          if (x > 0) Some(x)
          else None
    """.trimIndent()
}
