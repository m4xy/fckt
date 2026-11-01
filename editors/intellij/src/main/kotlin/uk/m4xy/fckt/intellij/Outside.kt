package uk.m4xy.fckt.intellij

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.TextDocumentIdentifier

/** The kinds of name the server colours, by their place in its legend, as it says them. */
private val tokenTypes = listOf(
    "namespace", "type", "class", "interface", "struct", "typeParameter", "parameter", "variable",
    "property", "enumMember", "function", "decorator",
)

/**
 * Colours for a fckt file outside the project, as a library's interface or the core kiln keeps:
 * IntelliJ gives the language server only the project's files, so the plugin asks it for these.
 */
class FcktOutsideAnnotator : ExternalAnnotator<FcktOutsideAnnotator.Input, List<Pair<TextRange, TextAttributesKey>>>() {
    class Input(val file: PsiFile, val text: String, val uri: String)

    override fun collectInformation(file: PsiFile): Input? {
        val virtual = file.virtualFile ?: return null
        if (file !is FcktFile || ProjectFileIndex.getInstance(file.project).isInContent(virtual)) return null
        return Input(file, file.text, virtual.url)
    }

    override fun doAnnotate(input: Input): List<Pair<TextRange, TextAttributesKey>> {
        val server = LspServerManager.getInstance(input.file.project)
            .getServersForProvider(FcktLspServerSupportProvider::class.java).firstOrNull() ?: return emptyList()
        val data = try {
            server.sendRequestSync(5000) { (it as FcktLanguageServer).tokens(TokensParams(TextDocumentIdentifier(input.uri), input.text)) }?.data
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val lineStarts = mutableListOf(0).apply { input.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        var line = 0
        var column = 0
        return data.chunked(5).mapNotNull { (deltaLine, deltaStart, length, type, modifiers) ->
            line += deltaLine
            column = if (deltaLine == 0) column + deltaStart else deltaStart
            val start = (lineStarts.getOrNull(line) ?: return@mapNotNull null) + column
            val key = FcktColors.ofToken(tokenTypes.getOrElse(type) { "" }, if (modifiers and 1 != 0) listOf("declaration") else emptyList())
            if (key == null || start + length > input.text.length) null else TextRange(start, start + length) to key
        }
    }

    override fun apply(file: PsiFile, found: List<Pair<TextRange, TextAttributesKey>>, holder: AnnotationHolder) {
        for ((range, key) in found) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
