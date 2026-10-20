package uk.m4xy.fckt.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testFramework.LightVirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Cmd-click, or Go To Declaration: where the name under the caret is declared. */
class FcktGotoDeclaration : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = source?.containingFile as? FcktFile ?: return null
        val text = file.text
        val token = scan(text).firstOrNull { it.kind == TokKind.IDENT && offset >= it.start && offset <= it.end } ?: return null
        val targets = declarationsOf(file)[token.start] ?: return null
        return targets.mapNotNull { element(file, it) }.distinct().toTypedArray().takeIf { it.isNotEmpty() }
    }

    /** The file's declarations, worked out again only when it changes. */
    private fun declarationsOf(file: FcktFile): Map<Int, List<Target>> = CachedValuesManager.getCachedValue(file) {
        val path = file.virtualFile?.path?.let(::File)
        CachedValueProvider.Result.create(declarations(file.text, path), file)
    }

    private fun element(from: FcktFile, target: Target): PsiElement? {
        val file: PsiFile = when {
            target.file == null -> from
            target.file.startsWith("std/") -> StandardFiles.psi(from.project, target.file) ?: return null
            else -> LocalFileSystem.getInstance().findFileByPath(target.file)?.let { PsiManager.getInstance(from.project).findFile(it) } ?: return null
        }
        val offset = offsetOf(file.text, target.pos, target.name)
        return file.findElementAt(offset) ?: file
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
