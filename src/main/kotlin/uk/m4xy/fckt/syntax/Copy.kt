package uk.m4xy.fckt.syntax

/**
 * Fresh copies of declarations, with nothing resolved yet: a functor's body is checked again for
 * each module made from it. [rename] gives each declaration's full name; [onCopy] sees each
 * declaration with its copy.
 */
class Copier(private val rename: (String) -> String, private val onCopy: (Decl, Decl) -> Unit = { _, _ -> }) {
    fun decl(d: Decl): Decl = when (d) {
        is Decl.Record -> d.copy(name = rename(d.name), fields = d.fields.map(::field))
        is Decl.Sealed -> d.copy(
            name = rename(d.name),
            variants = d.variants.map { it.copy(fields = it.fields.map(::field)) },
            includes = d.includes.map(::named),
        )
        is Decl.Alias -> d.copy(name = rename(d.name), target = type(d.target))
        is Decl.Function -> d.copy(
            name = rename(d.name),
            params = d.params?.map(::param),
            returnType = d.returnType?.let(::type),
            body = expr(d.body),
        )
        is Decl.Module -> d.copy(
            name = rename(d.name),
            decls = d.decls.map(::decl),
            spec = d.spec?.let(::named),
            params = d.params.map { it.copy(spec = named(it.spec)) },
            application = d.application?.let(::named),
        )
        is Decl.Spec -> d.copy(name = rename(d.name), values = d.values.map { v -> v.copy(params = v.params?.map(::param), type = type(v.type)) })
    }.also { onCopy(d, it) }

    // TypeRefs are told apart by identity once resolved, so each copy gets its own.
    private fun type(t: TypeRef): TypeRef = when (t) {
        is TypeRef.Named -> named(t)
        is TypeRef.Function -> t.copy(params = t.params.map(::type), result = type(t.result))
        is TypeRef.Tuple -> t.copy(elements = t.elements.map(::type))
    }

    private fun named(t: TypeRef.Named) = t.copy(args = t.args.map(::type))
    private fun field(f: FieldDef) = f.copy(type = type(f.type))
    private fun param(p: Param) = p.copy(type = p.type?.let(::type))

    private fun expr(e: Expr): Expr = when (e) {
        is Expr.IntLit -> Expr.IntLit(e.pos, e.value)
        is Expr.FloatLit -> Expr.FloatLit(e.pos, e.value)
        is Expr.BoolLit -> Expr.BoolLit(e.pos, e.value)
        is Expr.CharLit -> Expr.CharLit(e.pos, e.value)
        is Expr.StringLit -> Expr.StringLit(e.pos, e.parts.map { if (it is StringPart.Interpolated) StringPart.Interpolated(expr(it.expr)) else it })
        is Expr.Tuple -> Expr.Tuple(e.pos, e.elements.map(::expr))
        is Expr.Spread -> Expr.Spread(e.pos, expr(e.list))
        is Expr.Name -> Expr.Name(e.pos, e.name)
        is Expr.ListLit -> Expr.ListLit(e.pos, e.items.map(::expr))
        is Expr.Call -> Expr.Call(e.pos, expr(e.callee), e.args.map { Arg(it.name, expr(it.value)) })
        is Expr.Member -> Expr.Member(e.pos, expr(e.target), e.name)
        is Expr.Binary -> Expr.Binary(e.pos, e.op, expr(e.left), expr(e.right))
        is Expr.Unary -> Expr.Unary(e.pos, e.op, expr(e.operand))
        is Expr.Lambda -> Expr.Lambda(e.pos, e.params.map(::param), expr(e.body), e.ignoresArguments)
        is Expr.Pipe -> Expr.Pipe(e.pos, expr(e.value), expr(e.function))
        is Expr.If -> Expr.If(e.pos, expr(e.condition), expr(e.then), expr(e.otherwise))
        is Expr.When -> Expr.When(
            e.pos,
            e.subject?.let(::expr),
            e.branches.map { Branch(it.pos, it.patterns.map(::pattern), it.condition?.let(::expr), expr(it.body)) },
        )
        is Expr.Block -> Expr.Block(e.pos, e.statements.map(::statement), expr(e.result))
    }

    private fun statement(s: Statement): Statement = when (s) {
        is Statement.Binding -> s.copy(params = s.params?.map(::param), returnType = s.returnType?.let(::type), value = expr(s.value))
        is Statement.Effect -> Statement.Effect(s.pos, expr(s.expr))
        is Statement.Destructure -> Statement.Destructure(s.pos, pattern(s.pattern), expr(s.value))
    }

    private fun pattern(p: Pattern): Pattern = when (p) {
        is Pattern.Wildcard -> Pattern.Wildcard(p.pos)
        is Pattern.Bind -> Pattern.Bind(p.pos, p.name)
        is Pattern.Literal -> Pattern.Literal(p.pos, expr(p.expr))
        is Pattern.Tuple -> Pattern.Tuple(p.pos, p.elements.map(::pattern))
        is Pattern.ListPattern -> Pattern.ListPattern(p.pos, p.elements.map(::pattern), p.rest?.let(::pattern))
        is Pattern.Constructor -> Pattern.Constructor(p.pos, p.name, p.args?.map(::pattern), p.isCheck, p.qualifier)
    }
}
