package uk.m4xy.fckt.intellij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/** fckt's language server, with what it says that LSP has no request for. */
interface FcktLanguageServer : LanguageServer {
    /** Each spec, module, and value of them, in the file, with what implements it or what it implements. */
    @JsonRequest("fckt/specMarkers")
    fun specMarkers(params: SpecMarkersParams): CompletableFuture<List<SpecMarker>>

    /** The colours of a file outside the project, which IntelliJ won't open with the server, from its text. */
    @JsonRequest("fckt/tokens")
    fun tokens(params: TokensParams): CompletableFuture<SemanticTokens>
}

class TokensParams(val textDocument: TextDocumentIdentifier, val text: String)

class SpecMarkersParams(val textDocument: TextDocumentIdentifier)

/** A name, where it is: `implemented` by its targets, a spec's, or `implements` them, a module's. */
class SpecMarker(val range: Range, val kind: String, val targets: List<Location>)

/**
 * Gutter icons on specs and the modules that follow them, as Kotlin has on interfaces and classes:
 * on a spec, and each of its values, the modules that implement it; on a module, and each of its
 * functions a spec has, what it implements. The language server says which.
 */
class FcktSpecLineMarkers : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile as? FcktFile ?: return
        val virtual = file.virtualFile ?: return
        val project = file.project
        val server = LspServerManager.getInstance(project).getServersForProvider(FcktLspServerSupportProvider::class.java)
            .firstOrNull() ?: return
        val markers = try {
            server.sendRequestSync(3000) { (it as FcktLanguageServer).specMarkers(SpecMarkersParams(server.getDocumentIdentifier(virtual))) }
        } catch (e: Exception) {
            null
        } ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val leaves = elements.filter { it.firstChild == null }.associateBy { it.textRange.startOffset }
        for (marker in markers) {
            val line = marker.range.start.line
            if (line >= document.lineCount) continue
            val leaf = leaves[document.getLineStartOffset(line) + marker.range.start.character] ?: continue
            val targets = marker.targets.mapNotNull { target ->
                val found = VirtualFileManager.getInstance().findFileByUrl(target.uri) ?: return@mapNotNull null
                val psi = PsiManager.getInstance(project).findFile(found) ?: return@mapNotNull null
                val text = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@mapNotNull null
                if (target.range.start.line >= text.lineCount) return@mapNotNull null
                psi.findElementAt(text.getLineStartOffset(target.range.start.line) + target.range.start.character)
            }
            if (targets.isEmpty()) continue
            val implemented = marker.kind == "implemented"
            result += NavigationGutterIconBuilder
                .create(if (implemented) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.ImplementingMethod)
                .setTargets(targets)
                .setTooltipText(if (implemented) "Implemented in ${targets.size} module${if (targets.size == 1) "" else "s"}" else "Implements its spec's")
                .setPopupTitle(if (implemented) "Implementations" else "Spec")
                .createLineMarkerInfo(leaf)
        }
    }
}
