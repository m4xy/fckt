package uk.m4xy.fckt.intellij

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

object FcktLanguage : Language("fckt") {
    private fun readResolve(): Any = FcktLanguage
}

class FcktFileType private constructor() : LanguageFileType(FcktLanguage) {
    override fun getName() = "fckt"
    override fun getDescription() = "fckt source"
    override fun getDefaultExtension() = "fk"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text

    companion object {
        @JvmField val INSTANCE = FcktFileType()
    }
}

class FcktFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, FcktLanguage) {
    override fun getFileType() = FcktFileType.INSTANCE
}

class FcktToken(debugName: String) : IElementType(debugName, FcktLanguage)

/** An IntelliJ token type for each kind the [scan]ner finds. */
object FcktTokens {
    val TYPES: Map<TokKind, IElementType> = TokKind.entries.associateWith { kind ->
        when (kind) {
            TokKind.WHITESPACE -> TokenType.WHITE_SPACE
            TokKind.BAD -> TokenType.BAD_CHARACTER
            else -> FcktToken(kind.name)
        }
    }
    val COMMENTS = TokenSet.create(TYPES.getValue(TokKind.LINE_COMMENT), TYPES.getValue(TokKind.BLOCK_COMMENT))
    val STRINGS = TokenSet.create(TYPES.getValue(TokKind.STRING), TYPES.getValue(TokKind.CHAR))
    val IDENTIFIERS = TokenSet.create(TYPES.getValue(TokKind.IDENT))

    /** A name, wrapped around its identifier token, so it can refer to a declaration and be found. */
    val NAME = FcktToken("NAME")
}

/**
 * The [scan]ner as an IntelliJ lexer. A token inside a string isn't a place to start lexing again,
 * so its state isn't the initial one; the editor goes back to one that is.
 */
class FcktLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var toks: List<Tok> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        toks = scan(buffer, startOffset, endOffset)
        index = 0
    }

    override fun getState() = if (toks.getOrNull(index)?.nested == true) 1 else 0
    override fun getTokenType(): IElementType? = toks.getOrNull(index)?.let { FcktTokens.TYPES.getValue(it.kind) }
    override fun getTokenStart() = toks.getOrNull(index)?.start ?: end
    override fun getTokenEnd() = toks.getOrNull(index)?.end ?: end
    override fun advance() {
        index++
    }
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end
}

/**
 * A flat tree: the file's tokens, one after another, with each name wrapped in an element that
 * refers to its declaration. Colours, errors and what names refer to come from the compiler.
 */
class FcktParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FcktLexer()
    override fun createParser(project: Project?) = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType == FcktTokens.TYPES.getValue(TokKind.IDENT)) {
                val name = builder.mark()
                builder.advanceLexer()
                name.done(FcktTokens.NAME)
            } else {
                builder.advanceLexer()
            }
        }
        file.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType() = FILE
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun getCommentTokens() = FcktTokens.COMMENTS
    override fun getStringLiteralElements() = FcktTokens.STRINGS
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == FcktTokens.NAME) FcktName(node) else ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = FcktFile(viewProvider)

    companion object {
        val FILE = IFileElementType(FcktLanguage)
    }
}
