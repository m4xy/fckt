package uk.m4xy.fckt

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.SelfHosted.quoted
import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.Branch
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.FieldDef
import uk.m4xy.fckt.syntax.Import
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaArg
import uk.m4xy.fckt.syntax.Param
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import kotlin.test.assertEquals

/**
 * fckt.parser's Parser, written in fckt, reads each source as this compiler's own parser does,
 * declaration for declaration, or finds the same errors, over the sources LexerTest splits.
 */
class ParserTest {
    /** What fckt.parser's Parser makes of each source, by its path. */
    private val written: Map<String, String> by lazy { SelfHosted.written("syntax.fk") }

    @TestFactory
    fun `fckt's parser, written in fckt, reads each source as this one does`() = SelfHosted.sources.map { file ->
        DynamicTest.dynamicTest(file.path) { assertEquals(described(file.readText()), written[file.path]) }
    }

    /** As syntax.fk writes it: each import and declaration, a line each, or each error. */
    private fun described(source: String): String = try {
        val file = Parser.parse(source)
        (file.imports[null].orEmpty().map { import(it) } + file.decls.map { decl(it) }).joinToString("") { "$it\n" }
    } catch (e: CompileError) {
        e.errors.joinToString("") { "error ${at(it.pos!!)} ${it.message}\n" }
    }

    private fun at(p: Pos) = "${p.line}:${p.col}"
    private fun list(items: List<String>) = "[" + items.joinToString(" ") + "]"
    private fun strings(items: List<String>) = list(items.map { quoted(it) })
    private fun optional(s: String?) = s ?: "_"

    private fun import(i: Import) = "(Import ${at(i.pos)} ${strings(i.path)} ${i.wildcard} ${optional(i.alias?.let(::quoted))})"

    private fun decl(d: Decl): String {
        val visibility = if (d.isPrivate) "Private" else if (d.isInternal) "Internal" else "Public"
        val name = if (d is Decl.Function && d.extension) d.methodName else d.name
        return "(Declaration ${at(d.pos)} ${quoted(name)} $visibility ${d.isOpaque} ${list(d.annotations.map { annotation(it) })} ${definition(d)})"
    }

    private fun definition(d: Decl): String = when (d) {
        is Decl.Record -> "(Record ${strings(d.typeParams)} ${list(d.fields.map { field(it) })} ${d.privateConstructor} ${d.isAnnotation} ${optional(d.external?.let(::quoted))})"
        is Decl.Sealed -> "(Cases ${strings(d.typeParams)} ${list(d.variants.map { v -> "(Case ${at(v.pos)} ${quoted(v.name)} ${list(v.fields.map { field(it) })} ${list(v.annotations.map { annotation(it) })})" })} " +
            "${list(d.caseRefs.map { "(CaseRef ${at(it.pos)} ${quoted(it.owner)} ${quoted(it.case)})" })} ${list(d.includes.map { type(it) })})"
        is Decl.Alias -> "(Alias ${strings(d.typeParams)} ${type(d.target)})"
        is Decl.Function -> {
            // An extension's receiver is its first parameter, this; a property has no others.
            val receiver = if (d.extension) d.params!!.first().type else null
            val params = if (!d.extension) d.params else if (d.property) null else d.params!!.drop(1)
            "(Function ${strings(d.typeParams)} ${optional(receiver?.let { type(it) })} ${optional(params?.let { ps -> list(ps.map { param(it) }) })} " +
                "${optional(d.returnType?.let { type(it) })} ${expr(d.body)} ${optional(d.external?.let(::quoted))} ${d.isOperator})"
        }
        is Decl.Module -> "(Module ${list(d.decls.map { decl(it) })} ${optional(d.spec?.let { type(it) })} " +
            "${list(d.params.map { "(ModuleParameter ${at(it.pos)} ${quoted(it.name)} ${type(it.spec)} ${optional(it.default?.let { t -> type(t) })})" })} " +
            "${optional(d.application?.let { a -> "(Application ${type(a.functor)} ${list(a.args.map { "(ModuleArgument ${optional(it.name?.let(::quoted))} ${type(it.module)})" })})" })} ${strings(d.typeParams)})"
        is Decl.Spec -> "(Spec ${list(d.types.map { "(SpecType ${at(it.pos)} ${quoted(it.name)} ${strings(it.typeParams)})" })} " +
            "${list(d.values.map { v -> "(SpecValue ${at(v.pos)} ${quoted(v.name)} ${strings(v.typeParams)} ${optional(v.params?.let { ps -> list(ps.map { param(it) }) })} ${type(v.type)} ${list(v.annotations.map { annotation(it) })} ${optional(v.default?.let { expr(it) })})" })} " +
            "${strings(d.typeParams)} ${optional(d.copyOf?.let { type(it) })} ${list(d.parents.map { type(it) })})"
    }

    private fun field(f: FieldDef) =
        "(Field ${at(f.pos)} ${quoted(f.name)} ${type(f.type)} ${list(f.annotations.map { annotation(it) })} ${f.isVar} ${optional(f.default?.let { expr(it) })} ${f.isVararg})"

    private fun param(p: Param) =
        "(Parameter ${at(p.pos)} ${quoted(p.name)} ${optional(p.type?.let { type(it) })} ${list(p.annotations.map { annotation(it) })} ${optional(p.default?.let { expr(it) })})"

    private fun annotation(a: Annotation): String = "(Annotation ${at(a.pos)} ${quoted(a.name)} ${list(a.args.map { metaArg(it) })} ${list(a.typeArgs.map { type(it) })})"

    private fun metaArg(a: MetaArg) = "(AnnotationArgument ${optional(a.name?.let(::quoted))} ${meta(a.value)})"

    private fun meta(m: Meta): String = when (m) {
        is Meta.Literal -> "(Constant ${expr(m.expr)})"
        is Meta.Ref -> "(Reference ${at(m.pos)} ${quoted(m.name)} ${optional(m.typeArgs?.let { ts -> list(ts.map { type(it) }) })} ${optional(m.args?.let { xs -> list(xs.map { metaArg(it) }) })})"
        is Meta.ListOf -> "(ValueList ${at(m.pos)} ${list(m.items.map { meta(it) })})"
        is Meta.TupleOf -> "(ValueTuple ${at(m.pos)} ${list(m.items.map { meta(it) })})"
    }

    private fun type(t: TypeRef): String = when (t) {
        is TypeRef.Named -> "(NamedType ${at(t.pos)} ${quoted(t.name)} ${list(t.args.map { type(it) })})"
        is TypeRef.Function -> "(FunctionType ${at(t.pos)} ${list(t.params.map { type(it) })} ${type(t.result)} ${list(t.effects.map { annotation(it) })})"
        is TypeRef.Tuple -> "(TupleType ${at(t.pos)} ${list(t.elements.map { type(it) })})"
        is TypeRef.Effects -> "(EffectsType ${at(t.pos)} ${list(t.effects.map { annotation(it) })})"
        is TypeRef.Given -> "(GivenType ${at(t.pos)} ${quoted(t.name)} ${type(t.type)})"
    }

    private fun expr(e: Expr): String = when (e) {
        is Expr.Compiled -> "(Compiled ${at(e.pos)})"
        is Expr.IntLit -> "(Int ${at(e.pos)} ${e.value})"
        is Expr.FloatLit -> "(Float ${at(e.pos)} ${e.value})"
        is Expr.BoolLit -> "(Boolean ${at(e.pos)} ${e.value})"
        is Expr.CharLit -> "(Char ${at(e.pos)} ${e.value})"
        is Expr.StringLit -> "(String ${at(e.pos)} ${list(e.parts.map { part ->
            when (part) {
                is StringPart.Text -> "(Text ${quoted(part.text)})"
                is StringPart.Interpolated -> "(Interpolated ${expr(part.expr)})"
            }
        })})"
        is Expr.Name -> "(Name ${at(e.pos)} ${quoted(e.name)})"
        is Expr.Tuple -> "(Tuple ${at(e.pos)} ${list(e.elements.map { expr(it) })})"
        is Expr.ListLit -> "(List ${at(e.pos)} ${list(e.items.map { expr(it) })})"
        is Expr.Spread -> "(Spread ${at(e.pos)} ${expr(e.list)})"
        is Expr.Call -> "(Call ${at(e.pos)} ${expr(e.callee)} ${list(e.args.map { arg(it) })} ${list(e.typeArgs.map { type(it) })})"
        is Expr.Member -> "(Member ${at(e.pos)} ${expr(e.target)} ${quoted(e.name)})"
        is Expr.Binary -> "(Binary ${at(e.pos)} ${quoted(e.op)} ${expr(e.left)} ${expr(e.right)})"
        is Expr.Unary -> "(Unary ${at(e.pos)} ${quoted(e.op)} ${expr(e.operand)})"
        is Expr.Assign -> "(Assign ${at(e.pos)} ${expr(e.target)} ${expr(e.value)})"
        is Expr.Lambda -> "(Lambda ${at(e.pos)} ${list(e.params.map { param(it) })} ${expr(e.body)} ${e.ignoresArguments})"
        is Expr.Pipe -> "(Pipe ${at(e.pos)} ${expr(e.value)} ${expr(e.function)})"
        is Expr.If -> "(If ${at(e.pos)} ${expr(e.condition)} ${expr(e.then)} ${expr(e.otherwise)} ${e.hasElse})"
        is Expr.When -> "(When ${at(e.pos)} ${optional(e.subject?.let { expr(it) })} ${list(e.branches.map { branch(it) })})"
        is Expr.Block -> "(Block ${at(e.pos)} ${list(e.statements.map { statement(it) })} ${expr(e.result)} ${e.endsWith != null})"
        is Expr.Monadic -> "(Monadic ${at(e.pos)} ${expr(e.module)} ${expr(e.block)})"
        is Expr.Handle -> "(Handle ${at(e.pos)} ${expr(e.handler)} ${expr(e.block)} ${optional(e.params?.let { ps -> list(ps.map { param(it) }) })})"
    }

    private fun arg(a: Arg) = "(Argument ${optional(a.name?.let(::quoted))} ${expr(a.value)})"

    private fun branch(b: Branch) =
        "(Branch ${at(b.pos)} ${list(b.patterns.map { pattern(it) })} ${optional(b.condition?.let { expr(it) })} ${expr(b.body)} ${optional(b.guard?.let { expr(it) })})"

    private fun statement(s: Statement): String = when (s) {
        is Statement.Binding -> "(Binding ${at(s.pos)} ${quoted(s.name)} ${optional(s.params?.let { ps -> list(ps.map { param(it) }) })} ${optional(s.returnType?.let { type(it) })} ${expr(s.value)} ${s.isVar})"
        is Statement.Effect -> "(Evaluation ${at(s.pos)} ${expr(s.expr)})"
        is Statement.Destructure -> "(Destructuring ${at(s.pos)} ${pattern(s.pattern)} ${expr(s.value)})"
        is Statement.Bind -> "(Bind ${at(s.pos)} ${pattern(s.pattern)} ${expr(s.value)})"
    }

    private fun pattern(p: Pattern): String = when (p) {
        is Pattern.Wildcard -> "(Wildcard ${at(p.pos)})"
        is Pattern.Bind -> "(NamePattern ${at(p.pos)} ${quoted(p.name)})"
        is Pattern.Literal -> "(ConstantPattern ${at(p.pos)} ${expr(p.expr)})"
        is Pattern.Tuple -> "(TuplePattern ${at(p.pos)} ${list(p.elements.map { pattern(it) })})"
        is Pattern.ListPattern -> "(ListPattern ${at(p.pos)} ${list(p.elements.map { pattern(it) })} ${optional(p.rest?.let { pattern(it) })})"
        is Pattern.Constructor -> "(CasePattern ${at(p.pos)} ${quoted(p.name)} ${optional(p.args?.let { ps -> list(ps.map { pattern(it) }) })} ${p.isCheck} ${optional(p.qualifier?.let(::quoted))})"
    }
}
