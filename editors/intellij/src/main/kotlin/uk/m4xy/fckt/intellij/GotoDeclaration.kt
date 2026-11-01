package uk.m4xy.fckt.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServerManager
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testFramework.LightVirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cmd-click, or Go To Declaration: where the name under the caret is declared. Names' references
 * do this too; this also handles the caret just past a name.
 */
class FcktGotoDeclaration : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = source?.containingFile as? FcktFile ?: return null
        val token = scan(file.text).firstOrNull { it.kind == TokKind.IDENT && offset >= it.start && offset <= it.end } ?: return null
        val targets = Resolver.declarationsOf(file)[token.start]
        return targets?.mapNotNull { Resolver.element(file, it) }?.distinct()?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: fromLanguageServer(file, token.start)
    }

    /**
     * Where fckt's language server says the name at [offset] is declared, for what the plugin can't
     * tell itself: a name in a project whose packages are in more than one folder, as one with a
     * processor's is, which the plugin's checking reads as a program's own file.
     */
    private fun fromLanguageServer(file: FcktFile, offset: Int): Array<PsiElement>? {
        val virtual = file.virtualFile ?: return null
        val project = file.project
        val server = LspServerManager.getInstance(project).getServersForProvider(FcktLspServerSupportProvider::class.java)
            .firstOrNull() ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val line = document.getLineNumber(offset)
        val params = DefinitionParams(server.getDocumentIdentifier(virtual), Position(line, offset - document.getLineStartOffset(line)))
        val found = try {
            server.sendRequestSync(2000) { it.textDocumentService.definition(params) }
        } catch (e: Exception) {
            null
        } ?: return null
        val places = if (found.isLeft) found.left.map { it.uri to it.range.start } else found.right.map { it.targetUri to it.targetSelectionRange.start }
        return places.mapNotNull { (uri, start) ->
            val target = VirtualFileManager.getInstance().findFileByUrl(uri) ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(target) ?: return@mapNotNull null
            val text = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@mapNotNull null
            if (start.line >= text.lineCount) return@mapNotNull null
            psi.findElementAt(text.getLineStartOffset(start.line) + start.character)
        }.toTypedArray().takeIf { it.isNotEmpty() }
    }
}

/** The standard library's files, which are in the compiler, opened read-only. */
object StandardFiles {
    private val files = ConcurrentHashMap<String, LightVirtualFile>()

    fun psi(project: Project, path: String): PsiFile? {
        val file = files.getOrPut(path) {
            val text = standardSource(path) ?: return null
            LightVirtualFile(path.substringAfterLast('/'), FcktFileType.INSTANCE, text).apply { isWritable = false }
        }
        return PsiManager.getInstance(project).findFile(file)
    }
}
