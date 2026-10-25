package uk.m4xy.fckt.intellij

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import uk.m4xy.fckt.analyse
import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Param
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type
import java.io.File

/**
 * What hovering over each name in [text] shows, by its token's offset: a function's inferred
 * signature and what it does, a local's or field's type, and the effects an annotation passes on.
 * Empty if the file doesn't check.
 */
fun hovers(text: String, entry: File?): Map<Int, String> {
    val checked = try {
        analyse(text, entry, listOfNotNull(entry?.parentFile))
    } catch (e: Exception) {
        return emptyMap()
    } catch (e: StackOverflowError) {
        return emptyMap()
    }
    return Hovers(text, checked.program).apply {
        decls(checked.decls)
        imports()
    }.found
}

private class Hovers(val text: String, val program: Program) {
    val found = mutableMapOf<Int, String>()

    private fun put(pos: Pos, name: String, hover: String) {
        val offset = offsetOf(text, pos, name)
        if (text.regionMatches(offset, name, 0, name.length)) found.putIfAbsent(offset, hover)
    }

    /** [type] as it's written, with its unknowns named as they would be: types A, B, ..., and effects E, E2, .... */
    private fun show(type: Type, names: MutableMap<Type.Var, String> = mutableMapOf()): String {
        name(type, names)
        return Type.render(type, names)
    }

    private fun name(type: Type, names: MutableMap<Type.Var, String>) {
        fun effects(v: Type.Var) {
            if (v in names) return
            val n = names.values.count { it.startsWith("E") }
            names[v] = if (n == 0) "E" else "E${n + 1}"
        }
        when (val t = type.prune()) {
            is Type.Var -> if (t !in names) names[t] = ('A' + names.values.count { !it.startsWith("E") }).toString()
            is Type.Con -> if (t.name == Type.EFFECTS) Type.row(t).second?.let(::effects) else t.args.forEach { name(it, names) }
            is Type.Fn -> {
                t.params.forEach { name(it, names) }
                name(t.result, names)
            }
        }
    }

    private fun effect(name: String) = "@" + Type.displayName(name).substringAfterLast('.')

    /** `Shapes.area(shape: Shape): Float`, then what it does. */
    private fun signature(d: Decl.Function): String {
        val name = if (d.extension) d.methodName else d.simpleName
        val module = d.path.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
        val type = program.functionTypes[d] ?: return module + name
        val names = mutableMapOf<Type.Var, String>()
        if (type !is Type.Fn || d.params == null) return "$module$name: ${show(type, names)}"
        val params = d.params!!.zip(type.params)
        val receiver = params.firstOrNull()?.takeIf { d.extension }
        val shown = (if (receiver != null) params.drop(1) else params).joinToString(", ") { (p, t) -> "${p.name}: ${show(t, names)}" }
        val head = receiver?.let { show(it.second, names) + "." } ?: module
        return "$head$name($shown): ${show(type.result, names)}\n${does(type, params, names)}"
    }

    /** What a function of [type] does: the effects it names, and whatever its function parameters do. */
    private fun does(type: Type.Fn, params: List<Pair<Param, Type>>, names: MutableMap<Type.Var, String>): String {
        val (labels, rest) = Type.row(type.effects)
        val named = labels.map { effect(it.name) }.distinct()
        val through = params.filter { (_, t) -> rest != null && (t.prune() as? Type.Fn)?.let { Type.row(it.effects).second === rest } == true }.map { it.first.name }
        val more = when {
            rest == null -> emptyList()
            through.isNotEmpty() -> listOf("what ${through.joinToString(" and ")} does")
            // What's in a parameter's type, as an effect parameter's tasks: Queue<@E>.
            params.any { (_, t) -> rest in t.freeVars() } -> listOf("@" + show(rest, names))
            // Only room for more, which every function has.
            else -> emptyList()
        }
        return if (named.isEmpty() && more.isEmpty()) "pure" else "does " + (named + more).joinToString(", ")
    }

    private fun annotations(list: List<Annotation>) {
        for (a in list) {
            val decl = a.decl ?: continue
            val effects = program.annotationEffects[decl.name].orEmpty()
            val kind = when {
                decl.name in effects -> "an effect" + ((decl as? Decl.Spec)?.takeIf { it.values.isNotEmpty() }?.let { s -> ", handled: " + s.values.joinToString(", ") { it.name } } ?: "")
                effects.isEmpty() -> "an annotation, compiled away"
                else -> "an annotation, which passes on " + effects.sorted().joinToString(" ") { effect(it) }
            }
            put(a.pos, a.name.substringAfterLast('.'), "${effect(decl.name)}: $kind")
        }
    }

    private fun typeRef(ref: TypeRef?) {
        when (ref) {
            is TypeRef.Function -> {
                annotations(ref.effects)
                (ref.params + ref.result).forEach(::typeRef)
            }
            is TypeRef.Named -> ref.args.forEach(::typeRef)
            is TypeRef.Tuple -> ref.elements.forEach(::typeRef)
            is TypeRef.Effects -> annotations(ref.effects)
            null -> {}
        }
    }

    /** Each name on the import lines: the package, module or declaration that much of the path names. */
    fun imports() {
        val byName = mutableMapOf<String, Decl>()
        fun collect(ds: List<Decl>): Unit = ds.forEach { d ->
            byName[d.name] = d
            if (d is Decl.Module) collect(d.decls)
        }
        collect(program.root)
        val imports = try { uk.m4xy.fckt.syntax.Parser.parse(text).imports[null].orEmpty() } catch (e: Exception) { return }
        val idents = scan(text).filter { it.kind == TokKind.IDENT }
        for (import in imports) {
            val start = offsetOf(text, import.pos, "import")
            val line = idents.filter { it.start > start }.takeWhile { t -> text.substring(start, t.start).none { it == '\n' } }
            var last: String? = null
            for ((i, tok) in line.take(import.path.size).withIndex()) {
                val full = import.path.take(i + 1).joinToString(".")
                val hover = describe(full, byName) ?: continue
                found.putIfAbsent(tok.start, hover)
                last = hover
            }
            if (import.alias != null) line.drop(import.path.size).lastOrNull()?.let { tok -> last?.let { found.putIfAbsent(tok.start, it) } }
        }
    }

    /** What the declaration of full name [full] is, for an import line. */
    private fun describe(full: String, byName: Map<String, Decl>): String? {
        val d = byName[full]
        if (d == null) {
            // A case: the name of a type with cases, then its case's.
            val sealed = byName[full.substringBeforeLast('.', "")] as? Decl.Sealed ?: return null
            return sealed.variants.firstOrNull { it.name == full.substringAfterLast('.') }?.let { "${it.name}: a case of ${sealed.name}" }
        }
        return when (d) {
            is Decl.Function -> signature(d)
            is Decl.Module -> if (d.isPackage) "package ${d.name}" else "module ${d.name}"
            is Decl.Spec -> if (program.annotationEffects[d.name]?.contains(d.name) == true) "effect ${d.name}" else "spec ${d.name}"
            is Decl.Record, is Decl.Sealed, is Decl.Alias -> "type ${d.name}"
        }
    }

    fun decls(decls: List<Decl>) {
        for (d in decls) {
            annotations(d.annotations)
            when (d) {
                is Decl.Function -> {
                    put(d.pos, if (d.extension) d.methodName else d.simpleName, signature(d))
                    val type = program.functionTypes[d] as? Type.Fn
                    d.params.orEmpty().forEachIndexed { i, p ->
                        annotations(p.annotations)
                        typeRef(p.type)
                        p.default?.let(::expr)
                        type?.params?.getOrNull(i)?.let { put(p.pos, p.name, "${p.name}: ${show(it)}") }
                    }
                    typeRef(d.returnType)
                    if (d.external == null) expr(d.body)
                }
                is Decl.Record -> d.fields.forEach {
                    annotations(it.annotations)
                    typeRef(it.type)
                    it.default?.let(::expr)
                }
                is Decl.Sealed -> d.variants.forEach { v ->
                    annotations(v.annotations)
                    v.fields.forEach { annotations(it.annotations); typeRef(it.type) }
                }
                is Decl.Spec -> d.values.forEach { v ->
                    annotations(v.annotations)
                    v.params.orEmpty().forEach { typeRef(it.type) }
                    typeRef(v.type)
                }
                is Decl.Module -> decls(d.decls)
                is Decl.Alias -> typeRef(d.target)
            }
        }
    }

    private fun expr(e: Expr) {
        when (e) {
            is Expr.Name -> when (val r = e.resolved) {
                is NameRef.Global -> put(e.pos, e.name, signature(r.decl))
                is NameRef.Local -> e.type?.let { put(e.pos, e.name, "${e.name}: ${show(it)}") }
                is NameRef.Operation -> e.type?.let { put(e.pos, e.name, "${e.name}: ${show(it)}\nan operation of ${effect(r.spec.name)}") }
                else -> {}
            }
            is Expr.Member -> {
                when (val r = e.resolved) {
                    is MemberRef.Global -> put(e.pos, e.name, signature(r.decl))
                    is MemberRef.Extension -> put(e.pos, e.name, signature(r.decl))
                    is MemberRef.Field -> e.type?.let { put(e.pos, e.name, "${e.name}: ${show(it)}") }
                    else -> {}
                }
                expr(e.target)
            }
            is Expr.Call -> {
                val callee = e.callee
                (e.resolved as? uk.m4xy.fckt.syntax.CallRef.Extension)?.let { r ->
                    if (callee is Expr.Member) put(callee.pos, callee.name, signature(r.decl))
                }
                expr(callee)
                e.args.forEach { expr(it.value) }
            }
            is Expr.Lambda -> {
                val type = e.type?.prune() as? Type.Fn
                e.params.forEachIndexed { i, p ->
                    typeRef(p.type)
                    type?.params?.getOrNull(i)?.let { put(p.pos, p.name, "${p.name}: ${show(it)}") }
                }
                expr(e.body)
            }
            is Expr.Binary -> { expr(e.left); expr(e.right) }
            is Expr.Unary -> expr(e.operand)
            is Expr.Assign -> { expr(e.target); expr(e.value) }
            is Expr.Handle -> { expr(e.handler); expr(e.block) }
            is Expr.Pipe -> { expr(e.value); expr(e.function) }
            is Expr.If -> { expr(e.condition); expr(e.then); expr(e.otherwise) }
            is Expr.When -> {
                e.subject?.let(::expr)
                e.branches.forEach { b ->
                    b.condition?.let(::expr)
                    b.guard?.let(::expr)
                    expr(b.body)
                }
            }
            is Expr.Block -> {
                for (s in e.statements) when (s) {
                    is Statement.Binding -> {
                        typeRef(s.returnType)
                        if (s.params == null) s.value.type?.let { put(s.pos, s.name, "${if (s.isVar) "var " else ""}${s.name}: ${show(it)}") }
                        expr(s.value)
                    }
                    is Statement.Effect -> expr(s.expr)
                    is Statement.Destructure -> expr(s.value)
                    is Statement.Bind -> expr(s.value)
                }
                expr(e.result)
            }
            is Expr.Monadic -> expr(e.block)
            is Expr.StringLit -> e.parts.forEach { if (it is StringPart.Interpolated) expr(it.expr) }
            is Expr.Tuple -> e.elements.forEach(::expr)
            is Expr.ListLit -> e.items.forEach(::expr)
            is Expr.Spread -> expr(e.list)
            is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit -> {}
        }
    }
}

/** Hovering, or Quick Documentation: what [hovers] says about the name there. */
class FcktDocumentationProvider : AbstractDocumentationProvider() {
    // The name itself, rather than what its reference resolves to, as its type is where it's used.
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        contextElement?.takeIf { file is FcktFile }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val leaf = originalElement ?: element ?: return null
        val file = leaf.containingFile as? FcktFile ?: return null
        val hover = Resolver.hoversOf(file)[leaf.textRange.startOffset] ?: return null
        val (signature, rest) = hover.split('\n', limit = 2).let { it[0] to it.getOrNull(1) }
        return "<div class='definition'><pre>${StringUtil.escapeXmlEntities(signature)}</pre></div>" +
            (rest?.let { "<div class='content'>${StringUtil.escapeXmlEntities(it)}</div>" } ?: "")
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = generateDoc(element, originalElement)
}
