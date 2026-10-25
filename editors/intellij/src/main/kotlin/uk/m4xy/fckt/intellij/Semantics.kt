package uk.m4xy.fckt.intellij

import uk.m4xy.fckt.STANDARD_MODULES
import uk.m4xy.fckt.moduleName
import uk.m4xy.fckt.standardLibrary
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.Branch
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.Param
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import java.io.File

/** What a name is, which decides its colour: each matches what Kotlin colours its counterpart. */
enum class Role {
    INTERFACE,      // a spec, or a type with cases: Kotlin's interface and sealed interface
    DATA_CLASS,     // a record, or a case: Kotlin's data class
    CLASS,          // a built-in type, like String or List
    TYPE_ALIAS,     // type Name = Other, or a spec's type
    OBJECT,         // a module: Kotlin's object
    TYPE_PARAMETER, // A in List<A>
    FUNCTION_DECLARATION,
    FUNCTION_CALL,
    PARAMETER,
    LOCAL_VARIABLE, // bound in a block: x = ..., x by ..., (a, b) = ...
    PROPERTY,       // a field: r.n, or where a record declares it
    NAMED_ARGUMENT,
    KEYWORD,        // spec, external, opaque, annotation and by, where they're keywords
    ANNOTATION,     // @Name, and annotation type Name
}

data class Span(val start: Int, val end: Int, val role: Role)

private val BUILTIN = mapOf(
    "Int" to Role.CLASS, "Float" to Role.CLASS, "Double" to Role.CLASS, "String" to Role.CLASS,
    "Boolean" to Role.CLASS, "Char" to Role.CLASS, "Unit" to Role.CLASS, "List" to Role.CLASS, "Lazy" to Role.CLASS,
    // Types with cases, like any other.
    "Option" to Role.INTERFACE, "Result" to Role.INTERFACE,
    "Some" to Role.DATA_CLASS, "None" to Role.DATA_CLASS, "Ok" to Role.DATA_CLASS, "Error" to Role.DATA_CLASS,
)

/** The roles of the names a file declares, at any depth, by simple name. */
private fun declaredRoles(decls: List<Decl>, into: MutableMap<String, Role>) {
    for (d in decls) {
        when (d) {
            is Decl.Spec -> {
                into[d.simpleName] = Role.INTERFACE
                d.types.forEach { into[it.name] = Role.TYPE_ALIAS }
            }
            is Decl.Sealed -> {
                into[d.simpleName] = Role.INTERFACE
                d.variants.forEach { into[it.name] = Role.DATA_CLASS }
            }
            is Decl.Record -> into[d.simpleName] = when {
                d.isAnnotation -> Role.ANNOTATION
                // A type OCaml implements is a class, as Kotlin's Array is.
                d.external != null -> Role.CLASS
                else -> Role.DATA_CLASS
            }
            is Decl.Alias -> into[d.simpleName] = Role.TYPE_ALIAS
            is Decl.Module -> {
                into[d.simpleName] = Role.OBJECT
                d.params.forEach { into[it.name] = Role.OBJECT }
                declaredRoles(d.decls, into)
            }
            is Decl.Function -> {}
        }
    }
}

/** The roles of what [text] declares; if it doesn't parse, what its declarations' first words say. */
private fun rolesOf(text: String): Map<String, Role> {
    val roles = mutableMapOf<String, Role>()
    try {
        declaredRoles(Parser.parse(text).decls, roles)
    } catch (e: Exception) {
        val code = scan(text).filter { it.kind != TokKind.WHITESPACE && it.kind != TokKind.LINE_COMMENT && it.kind != TokKind.BLOCK_COMMENT }
        fun word(i: Int) = code.getOrNull(i)?.let { text.substring(it.start, it.end) }
        for (i in code.indices) {
            val name = word(i + 1)?.takeIf { it[0].isUpperCase() } ?: continue
            when (word(i)) {
                "spec" -> roles[name] = Role.INTERFACE
                "effect" -> roles[name] = if (word(i + 2) == "{" || word(i + 2) == "<") Role.INTERFACE else Role.ANNOTATION
                "module" -> roles[name] = Role.OBJECT
                "type" -> {
                    // Past any type parameters: what follows says what kind of type it is.
                    var j = i + 2
                    if (word(j) == "<") while (j < code.size && word(j) != ">") j++.also { if (word(j) == ">") j++ }
                    roles[name] = when (word(j)) {
                        "{" -> Role.INTERFACE
                        "(", "private" -> Role.DATA_CLASS
                        else -> Role.TYPE_ALIAS
                    }
                }
            }
        }
    }
    return roles
}

/** The standard library's names: what each of its files declares, a module or what's in it, or a type, spec or effect. */
private val STANDARD_ROLES: Map<String, Role> by lazy {
    val roles = mutableMapOf<String, Role>()
    for (name in STANDARD_MODULES) {
        val text = standardLibrary(name) ?: continue
        val simple = name.substringAfterLast('/')
        if (Regex("\\bmodule $simple\\b").containsMatchIn(text)) roles[simple] = Role.OBJECT
        roles += rolesOf(text)
    }
    roles
}

/** The roles of the names [dir]'s other files declare: each file is a module. */
fun neighbourRoles(dir: File?, self: File?): Map<String, Role> {
    val roles = mutableMapOf<String, Role>()
    val files = dir?.listFiles { f -> f.isFile && f.extension == "fk" && f.canonicalFile != self?.canonicalFile } ?: return roles
    for (file in files.sortedBy { it.name }) {
        roles[moduleName(file)] = Role.OBJECT
        roles += NeighbourCache.roles(file)
    }
    return roles
}

/** Each neighbouring file's roles, read again only when it changes. */
private object NeighbourCache {
    private val cache = mutableMapOf<String, Pair<Long, Map<String, Role>>>()

    @Synchronized
    fun roles(file: File): Map<String, Role> {
        val stamp = file.lastModified()
        cache[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
        val roles = try { rolesOf(file.readText()) } catch (e: Exception) { emptyMap() }
        cache[file.path] = stamp to roles
        return roles
    }
}

/**
 * What each lower-case name in [decls]' bodies refers to, by where it is: a parameter, a local, or
 * a field a case's pattern took out. Each name's scope is where the compiler's is: a function's
 * or lambda's parameters cover its body, a block's bindings the lines after them, and a branch's
 * pattern its body. An inner one hides an outer one of the same name. Names the walk doesn't find
 * in scope are global, and keep the colours their tokens give them.
 */
internal class Scopes {
    /** What each name, where it's written, refers to. */
    val found = mutableMapOf<Pos, Role>()

    /** Where each use of a local or parameter was bound, by the use's position. */
    val bindings = mutableMapOf<Pos, Pos>()

    private class Scope(val parent: Scope?) {
        val names = mutableMapOf<String, Pair<Role, Pos>>()
        fun lookup(name: String): Pair<Role, Pos>? = names[name] ?: parent?.lookup(name)

        /** Names bound with `var`, which `name = value` changes rather than binds again. */
        val vars = mutableSetOf<String>()
        fun isVar(name: String): Boolean = if (name in names) name in vars else parent?.isVar(name) == true
    }

    private fun bind(scope: Scope, pos: Pos, name: String, role: Role) {
        if (name == "_" || name == "this") return
        scope.names[name] = role to pos
        found[pos] = role
    }

    fun decls(decls: List<Decl>) {
        for (d in decls) when (d) {
            is Decl.Function -> if (d.external == null) {
                val scope = Scope(null)
                d.params.orEmpty().forEach { bind(scope, it.pos, it.name, Role.PARAMETER) }
                expr(d.body, scope)
            }
            is Decl.Module -> decls(d.decls)
            else -> {}
        }
    }

    /** The names [p] binds, as [role], or as fields inside a case's arguments. */
    private fun pattern(p: Pattern, scope: Scope, role: Role) {
        when (p) {
            is Pattern.Bind -> bind(scope, p.pos, p.name, role)
            is Pattern.Tuple -> p.elements.forEach { pattern(it, scope, role) }
            is Pattern.ListPattern -> {
                p.elements.forEach { pattern(it, scope, role) }
                p.rest?.let { pattern(it, scope, role) }
            }
            is Pattern.Constructor -> p.args?.forEach { pattern(it, scope, Role.PROPERTY) }
            is Pattern.Wildcard -> {}
            is Pattern.Literal -> expr(p.expr, scope)
        }
    }

    private fun params(params: List<Param>, scope: Scope) = params.forEach { bind(scope, it.pos, it.name, Role.PARAMETER) }

    private fun expr(e: Expr, scope: Scope) {
        when (e) {
            is Expr.Name -> if (e.name[0].isLowerCase()) scope.lookup(e.name)?.let { (role, at) ->
                found[e.pos] = role
                bindings[e.pos] = at
            }
            is Expr.Call -> {
                // A called name keeps its call colour, but still refers to where it was bound.
                val callee = e.callee
                if (callee is Expr.Name) scope.lookup(callee.name)?.let { bindings[callee.pos] = it.second } else expr(callee, scope)
                e.args.forEach { arg: Arg -> expr(arg.value, scope) }
            }
            is Expr.Member -> expr(e.target, scope)
            is Expr.Binary -> {
                expr(e.left, scope)
                expr(e.right, scope)
            }
            is Expr.Unary -> expr(e.operand, scope)
            is Expr.Assign -> {
                expr(e.target, scope)
                expr(e.value, scope)
            }
            is Expr.Handle -> {
                expr(e.handler, scope)
                expr(e.block, scope)
            }
            is Expr.Lambda -> {
                val inner = Scope(scope)
                params(e.params, inner)
                expr(e.body, inner)
            }
            is Expr.Pipe -> {
                expr(e.value, scope)
                val function = e.function
                if (function is Expr.Name) scope.lookup(function.name)?.let { bindings[function.pos] = it.second } else expr(function, scope)
            }
            is Expr.If -> {
                expr(e.condition, scope)
                expr(e.then, scope)
                expr(e.otherwise, scope)
            }
            is Expr.When -> {
                e.subject?.let { expr(it, scope) }
                e.branches.forEach { b: Branch ->
                    val inner = Scope(scope)
                    b.patterns.forEach { pattern(it, inner, Role.LOCAL_VARIABLE) }
                    b.condition?.let { expr(it, inner) }
                    b.guard?.let { expr(it, inner) }
                    expr(b.body, inner)
                }
            }
            is Expr.Block -> block(e, scope)
            is Expr.Monadic -> block(e.block, scope)
            is Expr.StringLit -> e.parts.forEach { if (it is StringPart.Interpolated) expr(it.expr, scope) }
            is Expr.Tuple -> e.elements.forEach { expr(it, scope) }
            is Expr.ListLit -> e.items.forEach { expr(it, scope) }
            is Expr.Spread -> expr(e.list, scope)
            is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit -> {}
        }
    }

    /** Each binding covers the lines after it; the value it's bound to sees only the ones before. */
    private fun block(b: Expr.Block, outer: Scope) {
        val scope = Scope(outer)
        for (st in b.statements) when (st) {
            is Statement.Binding -> if (st.params != null) {
                // A local function: its name is in scope in its own body, for recursion.
                scope.names[st.name] = Role.LOCAL_VARIABLE to st.pos
                val inner = Scope(scope)
                params(st.params!!, inner)
                expr(st.value, inner)
            } else {
                expr(st.value, scope)
                if (!st.isVar && scope.isVar(st.name)) {
                    // Changing a var: a use of it, not a new name.
                    scope.lookup(st.name)?.let { (role, at) ->
                        found[st.pos] = role
                        bindings[st.pos] = at
                    }
                } else {
                    bind(scope, st.pos, st.name, Role.LOCAL_VARIABLE)
                    if (st.isVar) scope.vars += st.name
                }
            }
            is Statement.Effect -> expr(st.expr, scope)
            is Statement.Destructure -> {
                expr(st.value, scope)
                pattern(st.pattern, scope, Role.LOCAL_VARIABLE)
            }
            is Statement.Bind -> {
                expr(st.value, scope)
                pattern(st.pattern, scope, Role.LOCAL_VARIABLE)
            }
        }
        expr(b.result, scope)
    }
}

/**
 * Names a `when` branch's pattern takes out of a case, `x` in `Some(x) -> f(x)`, where they're
 * bound and where the branch uses them: they're the case's fields, so they're coloured as fields.
 * A branch starts its line and runs to the next one at the same depth, or the `}` that ends them.
 */
private fun caseFields(text: String, code: List<Tok>): Map<Int, Role> {
    fun word(t: Tok) = text.substring(t.start, t.end)
    fun startsLine(i: Int) = i == 0 || text.substring(code[i - 1].end, code[i].start).contains('\n')
    // The token after [i] if it's on the same line: a `(` on the next line starts a new statement, not a call.
    fun after(i: Int) = code.getOrNull(i + 1)?.takeIf { !startsLine(i + 1) }?.kind
    val depth = IntArray(code.size)
    var d = 0
    for ((i, t) in code.withIndex()) {
        if (t.kind == TokKind.RBRACE || t.kind == TokKind.RPAREN || t.kind == TokKind.RBRACKET) d--
        depth[i] = d
        if (t.kind == TokKind.LBRACE || t.kind == TokKind.LPAREN || t.kind == TokKind.LBRACKET) d++
    }
    val fields = mutableMapOf<Int, Role>()
    for (start in code.indices) {
        if (!startsLine(start)) continue
        // The pattern: up to an `->` on the same depth, with a case applied to something in it.
        val arrow = (start until code.size).firstOrNull { j ->
            code[j].kind == TokKind.ARROW && depth[j] == depth[start] || (j > start && startsLine(j))
        }?.takeIf { code[it].kind == TokKind.ARROW } ?: continue
        val pattern = start until arrow
        if (pattern.none { j -> code[j].kind == TokKind.IDENT && word(code[j])[0].isUpperCase() && code.getOrNull(j + 1)?.kind == TokKind.LPAREN }) continue
        val bound = pattern.filter { j ->
            val t = code[j]
            t.kind == TokKind.IDENT && word(t)[0].isLowerCase() && depth[j] > depth[start] &&
                code.getOrNull(j - 1)?.kind != TokKind.DOT && after(j) !in setOf(TokKind.LPAREN, TokKind.ASSIGN, TokKind.DOT)
        }
        if (bound.isEmpty()) continue
        val names = bound.map { word(code[it]) }.toSet()
        bound.forEach { fields[code[it].start] = Role.PROPERTY }
        // The body: to the next line at the pattern's depth, or out of it.
        var j = arrow + 1
        while (j < code.size && depth[j] >= depth[start] && !(startsLine(j) && depth[j] == depth[start])) {
            val t = code[j]
            if (t.kind == TokKind.IDENT && word(t) in names && code.getOrNull(j - 1)?.kind != TokKind.DOT &&
                after(j) != TokKind.LPAREN) fields[t.start] = Role.PROPERTY
            j++
        }
    }
    return fields
}

/**
 * The spans of [text] to colour by what their names are. A name's role comes from where it's
 * declared: in this file, its directory's other files (each a module), or the standard library.
 * A type parameter is one only inside the declaration that has it.
 */
fun semantics(text: String, neighbours: Map<String, Role> = emptyMap()): List<Span> {
    val toks = scan(text)
    val code = toks.filter { it.kind != TokKind.WHITESPACE && it.kind != TokKind.LINE_COMMENT && it.kind != TokKind.BLOCK_COMMENT }
    fun word(t: Tok?) = t?.let { text.substring(it.start, it.end) }
    val lineStarts = mutableListOf(0).apply { text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun offset(pos: Pos) = (lineStarts.getOrNull(pos.line - 1) ?: text.length) + pos.col - 1
    val byStart = code.withIndex().associate { (i, t) -> t.start to i }

    val roles = mutableMapOf<String, Role>()
    roles += BUILTIN
    roles += STANDARD_ROLES
    roles += neighbours
    val parsed = try { Parser.parse(text).decls } catch (e: Exception) { null }
    roles += parsed?.let { mutableMapOf<String, Role>().also { m -> declaredRoles(it, m) } } ?: rolesOf(text)

    // Where names are declared, by the offset of their first character.
    val fixed = mutableMapOf<Int, Role>()
    // Type parameters, each with the range of the declaration that has it.
    val typeParams = mutableListOf<Triple<IntRange, Set<String>, Unit>>()
    fun nameAt(pos: Pos, name: String, role: Role) {
        // The name may come after modifiers or a receiver: the first token with its text, from pos on its line.
        val from = byStart.entries.filter { it.key >= offset(pos) }.minByOrNull { it.key }?.value ?: return
        for (i in from until code.size) {
            val t = code[i]
            if (text.substring(lineStarts.last { it <= t.start }, t.start).count { it == '\n' } > 0) break
            if (word(t) == name) {
                fixed[t.start] = role
                return
            }
            if (offset(Pos(pos.line + 1, 1)) <= t.start) return
        }
    }
    fun walk(decls: List<Decl>, end: Int) {
        decls.forEachIndexed { i, d ->
            val start = offset(d.pos)
            val until = decls.getOrNull(i + 1)?.let { offset(it.pos) } ?: end
            when (d) {
                is Decl.Function -> {
                    if (d.typeParams.isNotEmpty()) typeParams += Triple(start until until, d.typeParams.toSet(), Unit)
                    nameAt(d.pos, d.methodName, Role.FUNCTION_DECLARATION)
                    d.params.orEmpty().filter { it.name != "this" }.forEach { nameAt(it.pos, it.name, Role.PARAMETER) }
                }
                is Decl.Record -> {
                    if (d.typeParams.isNotEmpty()) typeParams += Triple(start until until, d.typeParams.toSet(), Unit)
                    d.fields.forEach { nameAt(it.pos, it.name, Role.PROPERTY) }
                }
                is Decl.Sealed -> {
                    if (d.typeParams.isNotEmpty()) typeParams += Triple(start until until, d.typeParams.toSet(), Unit)
                    d.variants.forEach { v -> v.fields.forEach { nameAt(it.pos, it.name, Role.PROPERTY) } }
                }
                is Decl.Alias -> if (d.typeParams.isNotEmpty()) typeParams += Triple(start until until, d.typeParams.toSet(), Unit)
                is Decl.Spec -> {
                    val members = d.types.map { it.pos to it.typeParams } + d.values.map { it.pos to it.typeParams }
                    val sorted = members.sortedBy { offset(it.first) }
                    sorted.forEachIndexed { j, (pos, params) ->
                        val memberEnd = sorted.getOrNull(j + 1)?.let { offset(it.first) } ?: until
                        if (params.isNotEmpty()) typeParams += Triple(offset(pos) until memberEnd, params.toSet(), Unit)
                    }
                    d.values.forEach { v ->
                        nameAt(v.pos, v.name, Role.FUNCTION_DECLARATION)
                        v.params.orEmpty().forEach { nameAt(it.pos, it.name, Role.PARAMETER) }
                    }
                }
                is Decl.Module -> walk(d.decls, until)
            }
        }
    }
    parsed?.let { walk(it, text.length) }

    // Lower-case names by scope, when the file parses; otherwise, a branch's names by its lines.
    if (parsed != null) {
        val scopes = Scopes()
        scopes.decls(parsed)
        for ((pos, role) in scopes.found) {
            val start = offset(pos)
            val t = code.getOrNull(byStart[start] ?: continue) ?: continue
            if (t.kind != TokKind.IDENT) continue
            // A called name keeps its call colour, and a declaration its own.
            if (start in fixed && fixed[start] != Role.PARAMETER) continue
            fixed[start] = role
        }
    } else {
        fixed += caseFields(text, code).filterKeys { it !in fixed }
    }

    val spans = mutableListOf<Span>()
    // What's open around each token: named arguments are only directly in a call's parentheses.
    val open = ArrayDeque<TokKind>()
    for ((i, t) in code.withIndex()) {
        when (t.kind) {
            TokKind.LPAREN, TokKind.LBRACE, TokKind.LBRACKET -> open.addLast(t.kind)
            TokKind.RPAREN, TokKind.RBRACE, TokKind.RBRACKET -> open.removeLastOrNull()
            else -> {}
        }
        if (t.kind != TokKind.IDENT) continue
        val name = word(t)!!
        val prev = code.getOrNull(i - 1)
        val next = code.getOrNull(i + 1)
        fun sameLine(a: Tok, b: Tok) = text.substring(a.end, b.start).none { it == '\n' }
        // Whether [t] is on a line that starts with `import`.
        fun importLine(t: Tok): Boolean {
            val lineStart = text.lastIndexOf('\n', t.start - 1) + 1
            return text.substring(lineStart, t.start).trimStart().startsWith("import ")
        }
        val role: Role? = fixed[t.start] ?: when {
            prev?.kind == TokKind.AT -> Role.ANNOTATION
            name[0].isUpperCase() -> {
                if (typeParams.any { (range, names, _) -> t.start in range && name in names } && prev?.kind != TokKind.DOT) Role.TYPE_PARAMETER
                else {
                    val role = roles[name]
                    // Where it is says it's a module, whatever else has the name: before a member
                    // (M.M<A>, unless it's a type with cases, whose cases come after a dot too),
                    // or before a block run in it (M { ... }), which a type's declaration isn't.
                    val declares = prev != null && (word(prev) == "type" || word(prev) == "spec" || word(prev) == "effect" || prev.kind == TokKind.COLON)
                    when {
                        next?.kind == TokKind.DOT && role != Role.INTERFACE -> Role.OBJECT
                        next?.kind == TokKind.LBRACE && sameLine(t, next) && !declares -> Role.OBJECT
                        else -> role ?: Role.CLASS
                    }
                }
            }
            // Keywords only in place.
            (name == "spec" || name == "effect") && word(next)?.firstOrNull()?.isUpperCase() == true -> Role.KEYWORD
            // `import a.b.C as D`, at a line's start.
            name == "import" && next?.kind == TokKind.IDENT && (prev == null || !sameLine(prev, t)) -> Role.KEYWORD
            name == "as" && next?.kind == TokKind.IDENT && importLine(t) -> Role.KEYWORD
            (name == "opaque" || name == "annotation") && word(next) == "type" -> Role.KEYWORD
            name == "var" && next?.kind == TokKind.IDENT && sameLine(t, next) -> Role.KEYWORD
            name == "external" && next != null && (next.kind == TokKind.IDENT || next.kind == TokKind.LPAREN || word(next) == "type") &&
                (prev == null || !sameLine(prev, t) || word(prev) == "private") -> Role.KEYWORD
            name == "by" && prev != null && sameLine(prev, t) && (prev.kind == TokKind.IDENT || prev.kind == TokKind.RPAREN) &&
                next != null && sameLine(t, next) && next.kind !in setOf(TokKind.ASSIGN, TokKind.DOT, TokKind.COMMA, TokKind.RPAREN, TokKind.LPAREN, TokKind.COLON) -> Role.KEYWORD
            name == "_" -> null
            next?.kind == TokKind.LPAREN && sameLine(t, next) -> Role.FUNCTION_CALL
            prev?.kind == TokKind.DOT -> Role.PROPERTY
            next?.kind == TokKind.ASSIGN && open.lastOrNull() == TokKind.LPAREN && (prev?.kind == TokKind.LPAREN || prev?.kind == TokKind.COMMA) -> Role.NAMED_ARGUMENT
            else -> null
        }
        if (role != null) spans += Span(t.start, t.end, role)
    }
    return spans
}
