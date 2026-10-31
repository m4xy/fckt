package uk.m4xy.fckt.intellij

import com.intellij.ide.IconProvider
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import javax.swing.Icon

object FcktIcons {
    val FILE = load("fckt")
    val TYPE = load("type")
    val SPEC = load("spec")
    val MODULE = load("module")
    val EFFECT = load("effect")
    val ANNOTATION = load("annotation")

    private fun load(name: String) = IconLoader.getIcon("/icons/$name.svg", FcktIcons::class.java)
}

/**
 * A file that declares a type, spec, module, effect or annotation type of its own name shows what
 * it declares, as a Kotlin file of one class does; any other file shows fckt's.
 */
class FcktIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element as? FcktFile ?: return null
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(declaredIcon(file.text, file.virtualFile?.nameWithoutExtension ?: file.name.substringBeforeLast('.')), file)
        }
    }
}

/** The icon for what [text] declares at its top level with the name [name], or fckt's. */
fun declaredIcon(text: CharSequence, name: String): Icon {
    val words = mutableListOf<String>()
    var depth = 0
    for (tok in scan(text)) {
        when (tok.kind) {
            TokKind.WHITESPACE, TokKind.LINE_COMMENT, TokKind.BLOCK_COMMENT -> continue
            TokKind.LPAREN, TokKind.LBRACE, TokKind.LBRACKET -> depth++
            TokKind.RPAREN, TokKind.RBRACE, TokKind.RBRACKET -> depth--
            else -> {}
        }
        if (depth > 0 || tok.nested) {
            words.clear()
            continue
        }
        val word = text.substring(tok.start, tok.end)
        if (word == name && words.isNotEmpty()) {
            when {
                words.takeLast(2) == listOf("annotation", "type") -> return FcktIcons.ANNOTATION
                words.last() == "type" -> return FcktIcons.TYPE
                words.last() == "spec" -> return FcktIcons.SPEC
                words.last() == "module" -> return FcktIcons.MODULE
                words.last() == "effect" -> return FcktIcons.EFFECT
            }
        }
        if (tok.kind == TokKind.KEYWORD || tok.kind == TokKind.IDENT) words += word else words.clear()
    }
    return FcktIcons.FILE
}
