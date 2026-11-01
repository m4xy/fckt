package uk.m4xy.fckt.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.customization.LspRenameCustomizer
import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.eclipse.lsp4j.services.LanguageServer
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Runs `fckt --language-server` for a project's fckt files: its errors and their quick fixes,
 * completion, hover, going to a declaration, finding usages, renaming, inlay hints, outlines,
 * and each name's colour, by what it is. Formatting, and keywords' colours, are the plugin's own.
 */
class FcktLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (file.extension == "fk") serverStarter.ensureServerStarted(FcktLspServerDescriptor(project))
    }
}

private class FcktLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "fckt") {
    override fun isSupportedFile(file: VirtualFile) = file.extension == "fk"

    override val lsp4jServerClass: Class<out LanguageServer> = FcktLanguageServer::class.java

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(fcktExecutable(), "--language-server").withWorkDirectory(project.basePath)

    override val lspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer = LspFormattingDisabled
        // As with colours, IntelliJ renames and highlights a name's uses through the server only for
        // plain text and TextMate unless told.
        override val renameCustomizer: LspRenameCustomizer = object : LspRenameSupport() {
            override fun shouldRunRename(psiFile: PsiFile) = psiFile is FcktFile
        }
        override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer = object : LspDocumentHighlightsSupport() {
            override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile) = psiFile is FcktFile
        }
        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
            // IntelliJ asks only for plain text's and TextMate's unless told: fckt's have their own
            // language, whose names only the server can colour.
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile) = psiFile is FcktFile

            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? =
                FcktColors.ofToken(tokenType, modifiers)
        }
    }
}

/**
 * The fckt to run: FCKT if it's set, or the one kiln installs, in ~/.kiln/bin, or KILN_HOME's bin,
 * which an IDE started from the dock doesn't have on its PATH; or whatever `fckt` the PATH has.
 */
internal fun fcktExecutable(): String {
    System.getenv("FCKT")?.let { return it }
    val home = System.getenv("KILN_HOME") ?: (System.getProperty("user.home") + "/.kiln")
    val installed = File(home, "bin/fckt")
    return if (installed.canExecute()) installed.path else "fckt"
}
