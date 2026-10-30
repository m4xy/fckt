package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.FieldDef
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaArg
import uk.m4xy.fckt.syntax.MetaRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type

/**
 * What a project declares, for programs that read it, as a processor that writes code from a
 * project's types does: fckt --symbols writes it, in a format of lines that fckt's README sets out,
 * which a version on the first line names. Each type is there by its full name, with its fields
 * and cases, their types by the full names of what they refer to, and the annotations on each, with
 * what their arguments resolved to. What's private isn't: nothing outside its module sees it.
 */
internal object Symbols {
    /** The format's version, which changes when what's written does, so a reader never misreads one. */
    const val VERSION = 1

    /** The types [program] declares in [files], each of the project's own, as the format has them. */
    fun write(program: Program, files: Set<String>): String {
        val out = StringBuilder("fckt-symbols $VERSION\n")
        val types = (program.records.filter { !it.isAnnotation && it.external == null } + program.sealeds)
            .filter { it.pos.file in files && !it.isPrivate }
            .sortedWith(compareBy({ it.pos.file }, { it.pos.line }, { it.pos.col }))
        for (d in types) {
            when (d) {
                is Decl.Record -> {
                    out.line(0, "record ${d.name}")
                    header(out, d, d.typeParams)
                    if (d.privateConstructor) out.line(1, "private-constructor")
                    d.fields.forEach { field(out, 1, it, program, d.typeParams) }
                }
                is Decl.Sealed -> {
                    out.line(0, "cases ${d.name}")
                    header(out, d, d.typeParams)
                    for (case in program.cases[d.name].orEmpty()) {
                        out.line(1, "case ${case.name}")
                        at(out, 2, case.variant.pos)
                        if (case.origin.name != d.name) out.line(2, "from ${case.origin.name}")
                        case.variant.annotations.forEach { annotation(out, 2, it) }
                        case.fields.forEach { field(out, 2, it, program, d.typeParams + case.origin.typeParams) }
                    }
                }
                else -> {}
            }
        }
        return out.toString()
    }

    private fun header(out: StringBuilder, d: Decl, typeParams: List<String>) {
        at(out, 1, d.pos)
        if (d.isInternal) out.line(1, "internal")
        if (d.isOpaque) out.line(1, "opaque")
        typeParams.forEach { out.line(1, "parameter $it") }
        d.annotations.forEach { annotation(out, 1, it) }
    }

    private fun at(out: StringBuilder, depth: Int, pos: Pos) = out.line(depth, "at ${pos.line} ${pos.col} ${pos.file}")

    private fun field(out: StringBuilder, depth: Int, f: FieldDef, program: Program, typeParams: List<String>) {
        out.line(depth, "field ${f.name} ${type(f.type, program, typeParams)}")
        if (f.isVar) out.line(depth + 1, "var")
        if (f.default != null) out.line(depth + 1, "default")
        f.annotations.forEach { annotation(out, depth + 1, it) }
    }

    private fun annotation(out: StringBuilder, depth: Int, a: Annotation) {
        val decl = a.decl ?: return
        out.line(depth, "annotation ${decl.name}")
        arguments(a).forEach { (name, meta) -> out.line(depth + 1, "argument $name ${value(meta)}") }
    }

    /** [a]'s arguments, each by the field it's for, in the order the annotation declares them. */
    private fun arguments(a: Annotation): List<Pair<String, Meta>> {
        val decl = a.decl as? Decl.Record ?: return emptyList()
        val fields = decl.fields.map { it.name }
        val vararg = decl.fields.lastOrNull()?.takeIf { it.isVararg }
        val byName = a.args.filter { it.name != null }.associate { it.name!! to it.value }
        val inOrder = a.args.takeWhile { it.name == null }.map { it.value }
        val given = mutableMapOf<String, Meta>()
        inOrder.zip(fields).forEach { (m, name) -> given[name] = m }
        if (vararg != null && byName[vararg.name] == null) {
            val items = inOrder.drop(fields.size - 1)
            given[vararg.name] = Meta.ListOf(a.pos, items).also { it.resolved = null }
        }
        given += byName
        return fields.mapNotNull { name -> given[name]?.let { name to it } }
    }

    /** An argument, as the format writes it: a constant, a reference by its kind and full name, or a list or tuple of them. */
    private fun value(m: Meta): String = when (val r = m.resolved) {
        null -> when (m) {
            is Meta.ListOf -> m.items.joinToString(", ", "[", "]") { value(it) }
            is Meta.TupleOf -> m.items.joinToString(", ", "(", ")") { value(it) }
            is Meta.Literal -> expression(m.expr)
            is Meta.Ref -> throw CompileError(m.pos, "'${m.name}' wasn't resolved, so it can't be written as a symbol")
        }
        is MetaRef.Value -> expression(r.expr)
        is MetaRef.Type -> "type ${type(r.type)}"
        is MetaRef.Spec -> "spec ${r.spec.name}"
        is MetaRef.Module -> "module ${r.module.name}"
        is MetaRef.Function -> "function ${r.function.name}"
        is MetaRef.Case -> "case ${r.sealed.name}.${r.variant.name}"
        is MetaRef.Field -> "field ${r.record.name}.${r.field.name}"
        is MetaRef.Annotation -> {
            val a = r.annotation
            "@${a.decl?.name ?: a.name}" + arguments(a).joinToString(", ", "(", ")") { (name, v) -> "$name = ${value(v)}" }
        }
    }

    /** A constant, or a reference, an annotation's argument checked as an ordinary value. */
    private fun expression(e: Expr): String = when (e) {
        is Expr.IntLit -> e.value.toString()
        is Expr.FloatLit -> e.value.toString()
        is Expr.BoolLit -> e.value.toString()
        is Expr.CharLit -> "'" + escape(e.text, '\'') + "'"
        is Expr.StringLit -> "\"" + escape(e.parts.joinToString("") { (it as StringPart.Text).text }, '"') + "\""
        is Expr.ListLit -> e.items.joinToString(", ", "[", "]") { expression(it) }
        is Expr.Tuple -> e.elements.joinToString(", ", "(", ")") { expression(it) }
        is Expr.Name -> reference(e.resolved, e)
        is Expr.Member -> when (val r = e.resolved) {
            is MemberRef.Global -> global(r.decl)
            is MemberRef.Case -> constructor(r.ref)
            else -> throw CompileError(e.pos, "this can't be written as a symbol")
        }
        is Expr.Call -> {
            val ref = e.resolved as? CallRef.Construct ?: throw CompileError(e.pos, "only a record or case made of constants can be written as a symbol")
            val (name, fields) = when (val c = ref.ref) {
                is ConstructorRef.Record -> c.decl.name to c.decl.fields
                is ConstructorRef.Variant -> constructor(c).removePrefix("case ") to c.variant!!.fields
            }
            "make $name" + fields.indices.joinToString(", ", "(", ")") { i -> "${fields[i].name} = ${expression(e.args[ref.order[i]].value)}" }
        }
        else -> throw CompileError(e.pos, "this can't be written as a symbol")
    }

    private fun reference(r: NameRef?, e: Expr): String = when (r) {
        is NameRef.Global -> global(r.decl)
        is NameRef.Constructor -> constructor(r.ref)
        else -> throw CompileError(e.pos, "this can't be written as a symbol")
    }

    private fun global(d: Decl.Function) = (if (d.params == null) "value " else "function ") + d.name

    private fun constructor(c: ConstructorRef): String = when (c) {
        is ConstructorRef.Record -> "make ${c.decl.name}()"
        is ConstructorRef.Variant -> "case " + (c.sealed?.let { "${it.name}.${c.variant!!.name}" } ?: c.name)
    }

    /** A written type, each name by the full name of what it refers to; a type parameter by its own. */
    private fun type(t: TypeRef, program: Program, typeParams: List<String>): String = when (t) {
        is TypeRef.Named -> {
            val name = if (t.name in typeParams) t.name else program.typeNames[t] ?: t.name.let { if (it == "Double") "Float" else it }
            name + if (t.args.isEmpty()) "" else t.args.joinToString(", ", "<", ">") { type(it, program, typeParams) }
        }
        is TypeRef.Tuple -> t.elements.joinToString(", ", "(", ")") { type(it, program, typeParams) }
        is TypeRef.Function -> t.params.joinToString(", ", "(", ")") { type(it, program, typeParams) } + " -> " + type(t.result, program, typeParams)
        is TypeRef.Effects -> throw CompileError(t.pos, "effects can't be written as a symbol's type")
        is TypeRef.Given -> t.name + " = " + type(t.type, program, typeParams)
    }

    /** A type the checker resolved, as `List<User>` is in `@Of(List<User>)`. */
    private fun type(t: Type): String = when (val p = t.prune()) {
        is Type.Var -> "_"
        is Type.Con -> if (p.name == Type.TUPLE) p.args.joinToString(", ", "(", ")") { type(it) }
        else p.name + if (p.args.isEmpty()) "" else p.args.joinToString(", ", "<", ">") { type(it) }
        is Type.Fn -> p.params.joinToString(", ", "(", ")") { type(it) } + " -> " + type(p.result)
    }

    private fun escape(text: String, quote: Char): String = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            quote -> append('\\').append(c)
            else -> append(c)
        }
    }

    private fun StringBuilder.line(depth: Int, text: String) {
        repeat(depth) { append("  ") }
        append(text).append('\n')
    }
}
