package uk.m4xy.fckt

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.RawPart
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.syntax.Token
import java.io.File
import kotlin.test.assertEquals

/**
 * fckt.parser's Lexer, written in fckt, splits each source as this compiler's own lexer does: the
 * conformance cases, the core, std, the examples, and the fckt libraries here, its own source
 * among them.
 */
class LexerTest {
    private val sources = listOf("conformance", "src/main/resources/std", "std/src", "examples", "symbols/src", "parser/src")
        .flatMap { File(it).walk().filter { f -> f.isFile && f.extension == "fk" }.sortedBy { f -> f.path }.toList() }

    /** What fckt.parser's Lexer makes of each source, by its path: tokens.fk run over all of them at once. */
    private val written: Map<String, String> by lazy {
        val ml = compile(File("src/test/fckt/tokens.fk"), listOf(File("parser/src")), others = listOf(File("std/src")))
        val out = File.createTempFile("tokens", ".txt")
        try {
            Ocaml.run(ml, listOf(out.path) + sources.map { it.path })
            out.readText()
        } finally {
            out.delete()
        }.split(Regex("^# ", RegexOption.MULTILINE)).drop(1)
            .associate { it.substringBefore('\n') to it.substringAfter('\n') }
    }

    @TestFactory
    fun `fckt's lexer, written in fckt, splits each source as this one does`() = sources.map { file ->
        DynamicTest.dynamicTest(file.path) { assertEquals(described(file.readText()), written[file.path]) }
    }

    /** As tokens.fk writes them. */
    private fun described(source: String): String = try {
        Lexer(source).tokenize().joinToString("") { t ->
            "${t.pos.line}:${t.pos.col} ${KINDS.getValue(t.kind)} ${quoted(t.text)}" + parts(t) + "\n"
        }
    } catch (e: CompileError) {
        "error ${e.pos!!.line}:${e.pos!!.col} ${e.message}\n"
    }

    private fun parts(t: Token): String = t.parts.joinToString("") {
        when (it) {
            is RawPart.Text -> " text ${quoted(it.text)}"
            is RawPart.Code -> " code ${quoted(it.source)} ${it.pos.line}:${it.pos.col}"
        }
    }

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""

    private companion object {
        /** This lexer's kinds, by fckt.parser's names for them. */
        val KINDS = mapOf(
            T.IDENT to "Identifier", T.INT to "IntLiteral", T.FLOAT to "FloatLiteral", T.STRING to "StringLiteral",
            T.CHAR to "CharLiteral", T.NEWLINE to "Newline", T.EOF to "End",
            T.LPAREN to "LeftParen", T.RPAREN to "RightParen", T.LBRACE to "LeftBrace", T.RBRACE to "RightBrace",
            T.LBRACKET to "LeftBracket", T.RBRACKET to "RightBracket",
            T.COMMA to "Comma", T.DOT to "Dot", T.COLON to "Colon", T.SEMI to "Semicolon", T.ASSIGN to "Assign",
            T.ARROW to "Arrow", T.PIPE to "Pipe", T.TILDE to "Tilde", T.BANG to "Bang", T.UNDERSCORE to "Underscore",
            T.AT to "At", T.OP_ASSIGN to "OperatorAssign",
            T.PLUS to "Plus", T.MINUS to "Minus", T.STAR to "Star", T.SLASH to "Slash", T.PERCENT to "Percent",
            T.EQ to "Equal", T.NEQ to "NotEqual", T.LT to "Less", T.GT to "Greater", T.LE to "LessOrEqual",
            T.GE to "GreaterOrEqual", T.AND to "And", T.OR to "Or",
            T.TYPE to "TypeKeyword", T.MODULE to "ModuleKeyword", T.PRIVATE to "PrivateKeyword", T.WHEN to "WhenKeyword",
            T.IF to "IfKeyword", T.ELSE to "ElseKeyword", T.IS to "IsKeyword", T.TRUE to "TrueKeyword", T.FALSE to "FalseKeyword",
        )
    }
}
