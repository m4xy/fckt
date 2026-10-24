package uk.m4xy.fckt.intellij

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightingColors as K
import uk.m4xy.fckt.compile
import uk.m4xy.fckt.syntax.CompileError
import java.io.File

/**
 * fckt's colours. Each falls back to Kotlin's for its counterpart, so fckt looks like Kotlin in
 * any colour scheme, and each can be changed on its own under Editor > Color Scheme > fckt.
 */
object FcktColors {
    private fun key(name: String, kotlin: TextAttributesKey) = createTextAttributesKey("FCKT_$name", kotlin)

    val KEYWORD = key("KEYWORD", K.KEYWORD)
    val NUMBER = key("NUMBER", K.NUMBER)
    val STRING = key("STRING", K.STRING)
    val ESCAPE = key("STRING_ESCAPE", K.STRING_ESCAPE)
    val LINE_COMMENT = key("LINE_COMMENT", K.LINE_COMMENT)
    val BLOCK_COMMENT = key("BLOCK_COMMENT", K.BLOCK_COMMENT)
    val OPERATOR = key("OPERATOR", K.OPERATOR_SIGN)
    val PARENTHESES = key("PARENTHESES", K.PARENTHESIS)
    val BRACES = key("BRACES", K.BRACES)
    val BRACKETS = key("BRACKETS", K.BRACKETS)
    val COMMA = key("COMMA", K.COMMA)
    val DOT = key("DOT", K.DOT)
    val COLON = key("COLON", K.COLON)
    val SEMICOLON = key("SEMICOLON", K.SEMICOLON)
    val ARROW = key("ARROW", K.ARROW)
    val BAD = key("BAD_CHARACTER", K.BAD_CHARACTER)

    val INTERFACE = key("INTERFACE", K.TRAIT)
    val DATA_CLASS = key("DATA_CLASS", K.DATA_CLASS)
    val CLASS = key("CLASS", K.CLASS)
    val TYPE_ALIAS = key("TYPE_ALIAS", K.TYPE_ALIAS)
    val MODULE = key("MODULE", K.OBJECT)
    val TYPE_PARAMETER = key("TYPE_PARAMETER", K.TYPE_PARAMETER)
    val FUNCTION_DECLARATION = key("FUNCTION_DECLARATION", K.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("FUNCTION_CALL", K.FUNCTION_CALL)
    val PARAMETER = key("PARAMETER", K.PARAMETER)
    val LOCAL_VARIABLE = key("LOCAL_VARIABLE", K.LOCAL_VARIABLE)
    val FIELD = key("FIELD", K.INSTANCE_PROPERTY)
    val NAMED_ARGUMENT = key("NAMED_ARGUMENT", K.NAMED_ARGUMENT)
    val ANNOTATION = key("ANNOTATION", K.ANNOTATION)

    fun of(role: Role): TextAttributesKey = when (role) {
        Role.INTERFACE -> INTERFACE
        Role.DATA_CLASS -> DATA_CLASS
        Role.CLASS -> CLASS
        Role.TYPE_ALIAS -> TYPE_ALIAS
        Role.OBJECT -> MODULE
        Role.TYPE_PARAMETER -> TYPE_PARAMETER
        Role.FUNCTION_DECLARATION -> FUNCTION_DECLARATION
        Role.FUNCTION_CALL -> FUNCTION_CALL
        Role.PARAMETER -> PARAMETER
        Role.LOCAL_VARIABLE -> LOCAL_VARIABLE
        Role.PROPERTY -> FIELD
        Role.NAMED_ARGUMENT -> NAMED_ARGUMENT
        Role.KEYWORD -> KEYWORD
        Role.ANNOTATION -> ANNOTATION
    }
}

/** Colours from the tokens alone: keywords, literals, comments and punctuation. */
class FcktSyntaxHighlighter : SyntaxHighlighterBase() {
    private val keys: Map<IElementType, TextAttributesKey> = mapOf(
        TokKind.KEYWORD to FcktColors.KEYWORD,
        TokKind.NUMBER to FcktColors.NUMBER,
        TokKind.STRING to FcktColors.STRING,
        TokKind.CHAR to FcktColors.STRING,
        TokKind.ESCAPE to FcktColors.ESCAPE,
        TokKind.TEMPLATE to FcktColors.ESCAPE,
        TokKind.LINE_COMMENT to FcktColors.LINE_COMMENT,
        TokKind.BLOCK_COMMENT to FcktColors.BLOCK_COMMENT,
        TokKind.OPERATOR to FcktColors.OPERATOR,
        TokKind.ASSIGN to FcktColors.OPERATOR,
        TokKind.LPAREN to FcktColors.PARENTHESES,
        TokKind.RPAREN to FcktColors.PARENTHESES,
        TokKind.LBRACE to FcktColors.BRACES,
        TokKind.RBRACE to FcktColors.BRACES,
        TokKind.LBRACKET to FcktColors.BRACKETS,
        TokKind.RBRACKET to FcktColors.BRACKETS,
        TokKind.COMMA to FcktColors.COMMA,
        TokKind.DOT to FcktColors.DOT,
        TokKind.COLON to FcktColors.COLON,
        TokKind.SEMI to FcktColors.SEMICOLON,
        TokKind.ARROW to FcktColors.ARROW,
        TokKind.AT to FcktColors.ANNOTATION,
        TokKind.BAD to FcktColors.BAD,
    ).mapKeys { FcktTokens.TYPES.getValue(it.key) }

    override fun getHighlightingLexer() = FcktLexer()
    override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> = pack(keys[type])
}

class FcktSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, file: VirtualFile?): SyntaxHighlighter = FcktSyntaxHighlighter()
}

/** Colours each name by what it is: a spec, a type with cases, a type parameter, a module... */
class FcktAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is FcktFile) return
        val path = element.virtualFile?.path?.let(::File)
        val spans = semantics(element.text, neighbourRoles(path?.parentFile, path))
        for (span in spans) {
            holder.newSilentAnnotation(HighlightInfoType.SYMBOL_TYPE_SEVERITY)
                .range(TextRange(span.start, span.end))
                .textAttributes(FcktColors.of(span.role))
                .create()
        }
    }
}

/** The compiler's errors for the file, each where it is. Runs in the background as the file changes. */
class FcktErrorAnnotator : ExternalAnnotator<FcktErrorAnnotator.Input, FcktErrorAnnotator.Found>() {
    class Input(val text: String, val file: File?)
    class Found(val text: String, val error: CompileError)

    override fun collectInformation(file: PsiFile) = Input(file.text, file.virtualFile?.path?.let(::File))

    override fun doAnnotate(input: Input): Found? {
        val entry = input.file ?: return null
        return try {
            compile(input.text, entry, listOfNotNull(entry.parentFile))
            null
        } catch (e: CompileError) {
            Found(input.text, e)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    override fun apply(file: PsiFile, found: Found?, holder: AnnotationHolder) {
        if (found == null || found.text != file.text) return
        for (error in found.error.errors) mark(error, found.text, holder)
    }

    private fun mark(error: CompileError, text: String, holder: AnnotationHolder) {
        val pos = error.pos
        val message = error.message ?: "error"
        if (pos == null || pos.file != null) {
            // In another file this one uses, or nowhere in particular.
            val where = pos?.let { "${File(it.file!!).name}:${it.line}:${it.col}: " }.orEmpty()
            holder.newAnnotation(HighlightSeverity.ERROR, where + message).fileLevel().create()
            return
        }
        val lineStart = text.lineSequence().take(pos.line - 1).sumOf { it.length + 1 }.coerceAtMost(text.length)
        val start = (lineStart + pos.col - 1).coerceIn(0, text.length)
        // The token there, or one character: enough to find it.
        val end = scan(text).firstOrNull { it.start == start && it.kind != TokKind.WHITESPACE }?.end
            ?: (start + 1).coerceAtMost(text.length)
        val range = if (end > start) TextRange(start, end) else TextRange(maxOf(0, start - 1), start)
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
    }
}

class FcktCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class FcktBraceMatcher : PairedBraceMatcher {
    private fun t(kind: TokKind) = FcktTokens.TYPES.getValue(kind)
    private val pairs = arrayOf(
        BracePair(t(TokKind.LPAREN), t(TokKind.RPAREN), false),
        BracePair(t(TokKind.LBRACKET), t(TokKind.RBRACKET), false),
        BracePair(t(TokKind.LBRACE), t(TokKind.RBRACE), true),
    )
    override fun getPairs() = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

/** Editor > Color Scheme > fckt: each colour, shown on a sample. */
class FcktColorSettingsPage : ColorSettingsPage {
    private val tags = mapOf(
        "interface" to FcktColors.INTERFACE,
        "data" to FcktColors.DATA_CLASS,
        "class" to FcktColors.CLASS,
        "alias" to FcktColors.TYPE_ALIAS,
        "module" to FcktColors.MODULE,
        "tp" to FcktColors.TYPE_PARAMETER,
        "fn" to FcktColors.FUNCTION_DECLARATION,
        "call" to FcktColors.FUNCTION_CALL,
        "param" to FcktColors.PARAMETER,
        "local" to FcktColors.LOCAL_VARIABLE,
        "field" to FcktColors.FIELD,
        "named" to FcktColors.NAMED_ARGUMENT,
        "kw" to FcktColors.KEYWORD,
        "ann" to FcktColors.ANNOTATION,
    )

    override fun getIcon() = FcktFileType.INSTANCE.icon
    override fun getHighlighter() = FcktSyntaxHighlighter()
    override fun getDisplayName() = "fckt"
    override fun getAdditionalHighlightingTagToDescriptorMap() = tags
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Keyword", FcktColors.KEYWORD),
        AttributesDescriptor("Number", FcktColors.NUMBER),
        AttributesDescriptor("String", FcktColors.STRING),
        AttributesDescriptor("String escape and template", FcktColors.ESCAPE),
        AttributesDescriptor("Line comment", FcktColors.LINE_COMMENT),
        AttributesDescriptor("Block comment", FcktColors.BLOCK_COMMENT),
        AttributesDescriptor("Operator", FcktColors.OPERATOR),
        AttributesDescriptor("Parentheses", FcktColors.PARENTHESES),
        AttributesDescriptor("Braces", FcktColors.BRACES),
        AttributesDescriptor("Brackets", FcktColors.BRACKETS),
        AttributesDescriptor("Arrow", FcktColors.ARROW),
        AttributesDescriptor("Spec, or type with cases", FcktColors.INTERFACE),
        AttributesDescriptor("Record, or case", FcktColors.DATA_CLASS),
        AttributesDescriptor("Built-in type", FcktColors.CLASS),
        AttributesDescriptor("Type alias, or spec's type", FcktColors.TYPE_ALIAS),
        AttributesDescriptor("Module", FcktColors.MODULE),
        AttributesDescriptor("Type parameter", FcktColors.TYPE_PARAMETER),
        AttributesDescriptor("Function declaration", FcktColors.FUNCTION_DECLARATION),
        AttributesDescriptor("Function call", FcktColors.FUNCTION_CALL),
        AttributesDescriptor("Parameter", FcktColors.PARAMETER),
        AttributesDescriptor("Local variable", FcktColors.LOCAL_VARIABLE),
        AttributesDescriptor("Field, or what a case's pattern takes out", FcktColors.FIELD),
        AttributesDescriptor("Named argument", FcktColors.NAMED_ARGUMENT),
        AttributesDescriptor("Annotation", FcktColors.ANNOTATION),
    )

    override fun getDemoText() = """
        // A spec, and a module that follows it
        <kw>spec</kw> <interface>Monad</interface> {
          <kw>type</kw> <alias>M</alias><<tp>A</tp>>
          <fn>bind</fn><<tp>A</tp>, <tp>B</tp>>(<param>m</param>: <alias>M</alias><<tp>A</tp>>, <param>f</param>: (<tp>A</tp>) -> <alias>M</alias><<tp>B</tp>>): <alias>M</alias><<tp>B</tp>>
        }

        <kw>module</kw> <module>Options</module> : <interface>Monad</interface> {
          <kw>type</kw> <alias>M</alias><<tp>A</tp>> = <interface>Option</interface><<tp>A</tp>>
          <fn>bind</fn>(<param>m</param>, <param>f</param>) = <kw>when</kw> (<param>m</param>) {
            <data>Some</data>(<field>x</field>) -> <call>f</call>(<field>x</field>)
            <data>None</data> -> <data>None</data>
          }
        }

        <kw>type</kw> <interface>Validation</interface><<tp>A</tp>, <tp>E</tp>> { <data>Valid</data>(<field>value</field>: <tp>A</tp>), <data>Invalid</data>(<field>errors</field>: <class>List</class><<tp>E</tp>>) }
        <kw>type</kw> <data>Ratio</data>(<field>n</field>: <class>Int</class>, <field>d</field>: <class>Int</class>)

        <kw>annotation</kw> <kw>type</kw> <ann>Pure</ann>
        <ann>@Pure</ann>
        <fn>half</fn>(<param>n</param>: <class>Int</class>): <interface>Option</interface><<class>Int</class>> = <kw>if</kw> (<param>n</param> % 2 == 0) <data>Some</data>(<param>n</param> / 2) <kw>else</kw> <data>None</data>

        <fn>main</fn>() = {
          <local>r</local> = <data>Ratio</data>(<named>n</named> = 1, <named>d</named> = 2)
          <call>println</call>("${'$'}{<local>r</local>.<field>n</field>} of ${'$'}{<local>r</local>.<field>d</field>}, \n")
          <local>q</local> = <module>Options</module> { <local>x</local> <kw>by</kw> <call>half</call>(8); <local>x</local> + 1 }
        }
    """.trimIndent()
}
