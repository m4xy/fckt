package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.FieldDef
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaArg
import uk.m4xy.fckt.syntax.Param
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.types.EFFECT_NAME

/**
 * A library's interface: what each of its files declares for others to use, as fckt without the
 * code, `greet(name: String): Unit`, which a program that uses the library is checked against. Its
 * public functions and values say their types, so nothing has to be inferred from code that isn't
 * there.
 */
internal object Interfaces {
    /**
     * [file]'s interface, with its imports, as its declarations' types name what they import. A spec
     * is as [text] has it, with its defaults' code, as a module that follows it has that code.
     */
    fun write(file: SourceFile, path: String, text: String): String {
        source = text
        val out = StringBuilder("// The interface of $path, which fckt --library wrote: its declarations, without their code.\n")
        val imports = file.imports[path].orEmpty()
        if (imports.isNotEmpty()) out.append('\n')
        for (i in imports) {
            out.append("import ").append(i.path.joinToString("."))
            if (i.wildcard) out.append(".*")
            i.alias?.let { out.append(" as ").append(it) }
            out.append('\n')
        }
        for (d in file.decls.filter { !it.isPrivate }) {
            out.append('\n')
            decl(d, "", out)
        }
        return out.toString()
    }

    /** The text of the file being written, for its specs. */
    private var source = ""

    /** [d] as the source has it: from where it starts to the brace that closes it. */
    private fun verbatim(d: Decl): String {
        val lineStarts = mutableListOf(0).apply { source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun offset(pos: Pos) = lineStarts[pos.line - 1] + pos.col - 1
        val tokens = Lexer(source, Pos(1, 1, null)).tokenize().dropWhile { it.pos.line < d.pos.line || (it.pos.line == d.pos.line && it.pos.col < d.pos.col) }
        var depth = 0
        for (t in tokens.dropWhile { it.kind != T.LBRACE }) {
            if (t.kind == T.LBRACE) depth++
            if (t.kind == T.RBRACE && --depth == 0) return source.substring(offset(d.pos), offset(t.pos) + 1)
        }
        error("a spec's braces don't close")
    }

    /**
     * What's wrong with [file]'s declarations as a library's: each public function or value that
     * doesn't say its type, which then would have to be worked out from its code.
     */
    fun untyped(file: SourceFile): List<CompileError> {
        val errors = mutableListOf<CompileError>()
        fun walk(ds: List<Decl>) {
            for (d in ds.filter { !it.isPrivate }) when (d) {
                is Decl.Module -> walk(d.decls)
                is Decl.Function -> {
                    val untyped = d.params.orEmpty().filter { it.type == null }.map { it.name }
                    val name = d.methodName
                    when {
                        untyped.isNotEmpty() -> errors += CompileError(d.pos, "'$name' is in a library's interface, which programs are checked against, so its parameters need types: ${untyped.joinToString(", ")}")
                        d.returnType == null && d.params == null -> errors += CompileError(d.pos, "'$name' is in a library's interface, which programs are checked against, so it needs a type: $name: Type = ...")
                        d.returnType == null -> errors += CompileError(d.pos, "'$name' is in a library's interface, which programs are checked against, so it needs a result type: $name(...): Type = ...")
                    }
                }
                else -> {}
            }
        }
        walk(file.decls)
        return errors
    }

    private fun decl(d: Decl, indent: String, out: StringBuilder) {
        out.append(indent)
        // A spec as written, with its annotations, what it builds on and its defaults' code.
        if (d is Decl.Spec && d.copyOf == null) {
            out.append(verbatim(d)).append('\n')
            return
        }
        val effect = d.annotations.indexOfFirst { it.name == EFFECT_NAME }.takeIf { it >= 0 && (d is Decl.Spec || (d is Decl.Record && d.isAnnotation && d.fields.isEmpty())) }
        annotations(d.annotations.filterIndexed { i, _ -> i != effect }, out)
        if (d.isOpaque) out.append("opaque ")
        when (d) {
            is Decl.Record -> when {
                effect != null -> out.append("effect ").append(d.name)
                d.isAnnotation -> {
                    out.append("annotation type ").append(d.name)
                    if (d.fields.isNotEmpty()) out.append(fields(d.fields))
                }
                d.external != null -> out.append("external type ").append(d.name).append(typeParams(d.typeParams)).append(" = ").append(string(d.external))
                else -> {
                    out.append("type ").append(d.name).append(typeParams(d.typeParams))
                    if (d.privateConstructor) out.append(" private")
                    out.append(fields(d.fields))
                }
            }
            is Decl.Sealed -> {
                out.append("type ").append(d.name).append(typeParams(d.typeParams)).append(" {\n")
                for (v in d.variants) {
                    out.append("$indent  ")
                    annotations(v.annotations, out)
                    out.append(v.name)
                    if (v.fields.isNotEmpty()) out.append(fields(v.fields))
                    out.append(",\n")
                }
                for (c in d.caseRefs) out.append("$indent  ${c.owner}.${c.case},\n")
                for (i in d.includes) out.append("$indent  *${type(i)},\n")
                out.append("$indent}")
            }
            is Decl.Alias -> out.append("type ").append(d.name).append(typeParams(d.typeParams)).append(" = ").append(type(d.target))
            is Decl.Function -> {
                if (d.external != null) out.append("external ")
                val params = if (d.extension) {
                    out.append(receiver(d.params!!.first().type!!)).append('.')
                    d.params.drop(1).takeIf { !d.property }
                } else {
                    d.params
                }
                out.append(d.methodName).append(typeParams(d.typeParams))
                params?.let { ps -> out.append(ps.joinToString(", ", "(", ")") { param(it) }) }
                d.returnType?.let { out.append(": ").append(type(it)) }
                if (d.external != null) out.append(" = ").append(string(d.external))
            }
            is Decl.Module -> {
                out.append("module ").append(d.name).append(typeParams(d.typeParams))
                d.application?.let { app ->
                    out.append(" = ").append(app.name).append(app.args.joinToString(", ", "(", ")") { type(it) })
                    out.append('\n')
                    return
                }
                if (d.params.isNotEmpty()) out.append(d.params.joinToString(", ", "(", ")") { "${it.name}: ${type(it.spec)}" })
                d.spec?.let { out.append(" : ").append(type(it)) }
                out.append(" {\n")
                for (inner in d.decls.filter { !it.isPrivate }) decl(inner, "$indent  ", out)
                out.append("$indent}")
            }
            // A copy of an effect; any other spec is as written, above.
            is Decl.Spec -> out.append(if (effect != null) "effect " else "spec ").append(d.name).append(" = ").append(type(d.copyOf!!))
        }
        out.append('\n')
    }

    /** An extension's receiver, which a function type or tuple is in parentheses for. */
    private fun receiver(t: TypeRef) = if (t is TypeRef.Function) "(${type(t)})" else type(t)

    private fun fields(fields: List<FieldDef>) = fields.joinToString(", ", "(", ")") { f ->
        buildString {
            annotations(f.annotations, this)
            if (f.isVar) append("var ")
            append(f.name).append(": ").append(type(f.type))
            if (f.default != null) append(" = compiled")
        }
    }

    private fun param(p: Param) = buildString {
        annotations(p.annotations, this)
        append(p.name)
        p.type?.let { append(": ").append(type(it)) }
        if (p.default != null) append(" = compiled")
    }

    private fun typeParams(names: List<String>) = if (names.isEmpty()) "" else names.joinToString(", ", "<", ">")

    private fun type(t: TypeRef): String = when (t) {
        is TypeRef.Named -> t.name + if (t.args.isEmpty()) "" else t.args.joinToString(", ", "<", ">") { type(it) }
        is TypeRef.Function -> t.effects.joinToString("") { effect(it) + " " } + t.params.joinToString(", ", "(", ")") { type(it) } + " -> " + type(t.result)
        is TypeRef.Tuple -> t.elements.joinToString(", ", "(", ")") { type(it) }
        is TypeRef.Effects -> t.effects.joinToString(" ") { effect(it) }
    }

    private fun effect(a: Annotation) = "@" + a.name + if (a.typeArgs.isEmpty()) "" else a.typeArgs.joinToString(", ", "<", ">") { type(it) }

    private fun annotations(annotations: List<Annotation>, out: StringBuilder) {
        for (a in annotations) {
            out.append(effect(a))
            if (a.args.isNotEmpty()) out.append(a.args.joinToString(", ", "(", ")") { arg(it) })
            out.append(' ')
        }
    }

    private fun arg(a: MetaArg) = (a.name?.let { "$it = " } ?: "") + meta(a.value)

    private fun meta(m: Meta): String = when (m) {
        is Meta.Literal -> literal(m.expr)
        is Meta.Ref -> m.name + (m.typeArgs?.joinToString(", ", "<", ">") { type(it) } ?: "") + (m.args?.joinToString(", ", "(", ")") { arg(it) } ?: "")
        is Meta.ListOf -> m.items.joinToString(", ", "[", "]") { meta(it) }
        is Meta.TupleOf -> m.items.joinToString(", ", "(", ")") { meta(it) }
    }

    private fun literal(e: Expr): String = when (e) {
        is Expr.IntLit -> e.value.toString()
        is Expr.FloatLit -> e.value.toString()
        is Expr.BoolLit -> e.value.toString()
        is Expr.CharLit -> "'" + escape(e.text, '\'') + "'"
        is Expr.StringLit -> string(e.parts.joinToString("") { (it as StringPart.Text).text })
        is Expr.Unary -> e.op + literal(e.operand)
        else -> error("an annotation's argument is a constant")
    }

    /** [text] as a fckt string, with what would end it or start a template escaped. */
    private fun string(text: String) = "\"" + escape(text, '"') + "\""

    private fun escape(text: String, quote: Char) = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            quote -> append('\\').append(c)
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }
}
