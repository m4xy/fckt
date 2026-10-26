package uk.m4xy.fckt.intellij

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.IncorrectOperationException
import java.io.File

/**
 * A name in a fckt file. Where it's used, its reference resolves to where it's declared; where
 * it's declared, it's what Find Usages looks for.
 */
class FcktName(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getName(): String = text
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("fckt names can't be renamed yet")
    override fun getNameIdentifier(): PsiElement? = firstChild
    override fun getTextOffset() = textRange.startOffset
    override fun getReference(): PsiReference = FcktReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)

    // A declaration in the standard library is used across the project.
    override fun getUseScope(): SearchScope = GlobalSearchScope.allScope(project)
}

/** Where a name is declared: one place, or several when it can't tell which. */
class FcktReference(name: FcktName) : PsiPolyVariantReferenceBase<FcktName>(name, TextRange(0, name.textLength), false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile as? FcktFile ?: return ResolveResult.EMPTY_ARRAY
        return Resolver.declarationsOf(file)[element.textRange.startOffset].orEmpty()
            .mapNotNull { Resolver.element(file, it) }
            .distinct()
            .map(::PsiElementResolveResult)
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/** What names in a file refer to, worked out from the compiler and kept until the file changes. */
object Resolver {
    fun declarationsOf(file: FcktFile): Map<Int, List<Target>> = CachedValuesManager.getCachedValue(file) {
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path?.let(::File)
        // A bug here would otherwise stop a whole search; the file's names just don't resolve.
        val found = try {
            declarations(file.text, path)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("couldn't resolve the names in ${file.name}", e)
            emptyMap()
        }
        CachedValueProvider.Result.create(found, file)
    }

    /** What hovering over each name in [file] shows, by its offset. */
    fun hoversOf(file: FcktFile): Map<Int, String> = CachedValuesManager.getCachedValue(file) {
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path?.let(::File)
        val found = try {
            hovers(file.text, path)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("couldn't work out the types in ${file.name}", e)
            emptyMap()
        }
        CachedValueProvider.Result.create(found, file)
    }

    private val LOG = Logger.getInstance(Resolver::class.java)

    /** The name element [target] is at: in [from], another file, or the core. */
    fun element(from: FcktFile, target: Target): PsiElement? {
        // A package: its folder.
        if (target.file != null && !target.file.startsWith(uk.m4xy.fckt.CORE_PATH) && File(target.file).isDirectory) {
            return LocalFileSystem.getInstance().findFileByPath(target.file)?.let { PsiManager.getInstance(from.project).findDirectory(it) }
        }
        val file: PsiFile = when {
            target.file == null -> from
            target.file.startsWith(uk.m4xy.fckt.CORE_PATH) -> StandardFiles.psi(from.project, target.file) ?: return null
            else -> LocalFileSystem.getInstance().findFileByPath(target.file)?.let { PsiManager.getInstance(from.project).findFile(it) } ?: return null
        }
        if (target.name.isEmpty()) return file
        val leaf = file.findElementAt(offsetOf(file.text, target.pos, target.name)) ?: return file
        return leaf.parent as? FcktName ?: leaf
    }
}

/** Find Usages: a name's uses are the names whose references resolve to it. */
class FcktFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(FcktLexer(), FcktTokens.IDENTIFIERS, FcktTokens.COMMENTS, FcktTokens.STRINGS)

    override fun canFindUsagesFor(element: PsiElement) = element is FcktName
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement) = if ((element as? FcktName)?.name?.firstOrNull()?.isUpperCase() == true) "type" else "name"
    override fun getDescriptiveName(element: PsiElement) = (element as? FcktName)?.name ?: ""
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
