# fckt

fckt is a functional language with Kotlin's syntax, minus Kotlin's imperative assumptions. Source
files are `.fk`, and the compiler transpiles them to readable OCaml.

```kotlin
type Money(Int)                        // one unnamed field is called `value`
type Order(id: String, items: List<Money>)

type OrderResult {                     // a type with cases: these are its only ones
  Success(orderId: String, total: Money)
  InvalidOrder(reason: String)
}

calculateTotal(items: List<Money>, code: Option<String>) = {
  subtotal = items.map(m -> m.value).sum()   // no `val`: every binding is immutable
  discount = when (code) {
    Some("X") -> (subtotal * 0.1).toInt()
    else -> 0
  }
  Money(max(0, subtotal - discount))
}
```

See `examples/orders.fk` for a complete program, and `examples/manual` for the OCaml manual's
tour of the core language, translated section by section.

## The language

| fckt | OCaml | Notes |
| --- | --- | --- |
| `type R(a: A, b: B)` | `type r = { a : a; b : b }` | `R(x, y)` or `R(a = x, b = y)` constructs it, and `r.a` reads a field. |
| `type R(A)` | `type r = { value : a }` | The same as `type R(value: A)`. |
| `type S { V(x: X), W }` | `type s = V of { x : x } \| W` | A type with cases, which a value is exactly one of (a sealed sum type). Parentheses make a record, braces make cases. |
| `type Vowel { Alphabet.A, Alphabet.E }` | `type vowel = [ `Alphabet_A \| `Alphabet_E ]` | A type can take in other types' cases, one at a time (`Alphabet.A`) or all of them (`*Vowel`). A `Vowel` can then be used wherever an `Alphabet` is expected, and `is Vowel` narrows an `Alphabet` to a `Vowel`. When branches give different types, the result is the smallest declared type with all their cases. Types that share cases become polymorphic variants; the rest stay ordinary ones. See `examples/shared-cases.fk`. |
| `module Ratios { ... }` | `module Ratios = struct ... end` | Groups declarations. Inside, members use each other directly; outside, they're named through the module: `Ratios.make(1, 2)`, `Shapes.Circle(1.0)`, `Shapes.Shape`. Modules can nest. See `examples/modules.fk`. |
| `geometry/Shapes.fk` | `module Geometry = struct module Shapes = struct ... end end` | A folder is a package, under the program's own folder or one given with `fckt -I dir`. A capitalised file declares one thing, named as the file is: a module, type, spec or effect, as `Shapes.fk` holds `module Shapes { ... }` and `Monad.fk` holds `spec Monad { ... }`, with anything else beside it private. A lower-case file holds its package's own declarations, anything at all, except a program's, which has a `main` and keeps its declarations to itself. A file uses its own package's modules and declarations by name, and other packages' through imports or by their full name, `geometry.Shapes.area(...)`. A private declaration in a lower-case file is private to that file. The program is compiled to one `.ml`. |
| `import geometry.Shapes` | | Brings in the module, and its types and their cases, which then need no prefix: `Shapes.area(Circle(1.0))`. `import geometry.Shapes.area` brings in one declaration, `import geometry.*` all of a package, and `import geometry.Shapes.*` all of a module. `as` renames: `import geometry.Shapes.Shape.Circle as Round`. A name two imports bring in is an error only where it's used, and importing the one meant settles it. Importing a handled effect brings in its operations. |
| `List<A>.second(): Option<A> = ...` | `let list_second (this : 'a list) : 'a option = ...` | An extension function, called as `xs.second()`. It's chosen by the receiver's type, which must be known, so `List<Int>.sum()` and `List<Float>.sum()` can both exist; the most specific one that fits wins. Inside, the receiver is `this`. Bare names in the receiver that are a capital letter, maybe with digits, like `A` or `T1`, are its type parameters; any other name is a type, from this file or another. Without parentheses, `String.size: Int = ...`, it's a property: `s.size`. See `examples/extensions.fk`. |
| `external List<A>.map<B>(f: (A) -> B): List<B> = "List.map f this"` | `List.map f xs` at each call | A function whose body is OCaml: a function applied to the parameters, by name, with `~name` for a labelled argument. It's written out at each use, so it has no OCaml definition of its own, and the compiler trusts its declared types, as OCaml trusts its own `external`. |
| `private gcd(...)` | left out of the module's `sig` | Visible only inside its module. A public member can't expose a private type. |
| `type Ratio private(n: Int, d: Int)` | `type ratio = private { ... }` in the `sig` | Like Kotlin's `private constructor`: readable and matchable anywhere, but only its module can build or `copy` one. |
| `spec Counter { type T; inc(c: T): T }` | `module type Counter = sig type t val inc : t -> t ... end` | What a module must define. Each of its types gets a show function, so values of it work in string templates. Members are one per line. |
| `spec Monad : Applicative { ... map(...): M<B> = bind(m, ...) }` | `module type Monad = sig type 'a m ... val map ... end` | A spec can build on others, and has what they list too; a module that follows it can be given where any of them is wanted. A spec's function can have a default, its body, which a module that follows the spec and doesn't define it has: `Monad`'s `map` and `product` are from `bind` and `pure`, so a monad defines just those, and may still write its own. A library's interface has its specs' defaults, as written. |
| `module Results<E> : Monad { type M<A> = Result<A, E> ... }` | `module Results = struct type ('a, 'e) m = ('a, 'e) result ... end` | A module with type parameters: each of its functions takes them too, and its type aliases mean them as what uses the alias has them, so `Result<_, E>` is a `Monad` for every error type E. It can't yet be given to a functor. |
| `module Tally : Counter { type T = Int ... }` | `module Tally = struct ... end` | The module must define everything the spec lists, with the spec's types; generic members must work for any type. Like a Kotlin interface, the spec hides nothing: what it doesn't list stays visible, and `Tally.T` is an `Int` everywhere unless it's opaque. See `examples/specs.fk`. |
| `opaque type T = Int` | `type t` in the module's `sig`, `type t = int` in its body | Outside its module, a `T` can be held and passed back to the module's functions, but not built, used as what it is, looked into or copied. Inside, it's an `Int`. So only the module's functions make one, and they can keep its invariants. Callers outside see a function's type as written, so `of(n: Int): Option<T>` returns an opaque `T`, while a parameter written as `Int` takes an `Int`. Records and types with cases can be opaque too. |
| `annotation type Route(path: String, handler: (Request) -> Response)` | nothing | An annotation, like Kotlin's `annotation class`. Its fields can be ordinary types, checked as usual, so a function passed for `handler` must take a `Request`; or references to what only the compiler knows: `Type`, `Spec`, `Module` or `Module<Spec>` (a module that follows the spec), `Case<T>` (a case of `T`), `Field<R, T>` (a field of the record `R`, `_` for any type), another annotation, or lists and tuples of these. See `examples/annotations.fk`. |
| `@Route("/users", handler = listUsers)` | nothing | On a declaration, a field, a case or a parameter. Arguments are constants and references, never calculations: `1`, `"text"`, `listUsers`, `Shape.Circle`, `User.email`, `IntOrder`, `List<User>`, `[Ordered]`, `Route("/a", greet)`. The compiler checks each against the annotation's declaration and then leaves it out, so it's there for tools that read the program. |
| `@Console greet(name: String) = println("hello $name")` | `let greet name = print_endline ...` | An effect: what a function may do besides work out its result. A function must declare the effects of what it calls, so one that declares none is pure; `main` may do anything, and a top-level value nothing. Doing less than declared is fine: a function's type records what it actually does. Effects pass through function parameters: `xs.map(shout)` is pure and `xs.forEach(println)` does `@Console`, from one `map` and one `forEach`. A spec's function that declares effects, or is in a spec that does, is a promise its modules must keep; one that declares none takes whatever its module does, which shows wherever that module is used. A functor may do whatever its parameters do. See `examples/effects.fk`. |
| `effect Audit` | nothing | Declares an effect that's a label: what a function does through externals or `var`s. The language's own are `@Mutating`, and `@Pure`, the effect nothing has, in `fckt.lang`. `effect` is a keyword only here, so it can still name a value. It's an `annotation type` marked `@Effect`, which is how other annotations see it, and that can be written out too. |
| `effect Clock { now(): Int }` | `type _ Effect.t += Clock_now : unit -> int Effect.t` | A handled effect: its operations are called by name (`now()`, or `Clock.now()` if two effects share a name), and calling one does `@Clock`. Whatever handles the effect around the call answers it, through OCaml 5's effects. The standard library's `Console` is one, with `print`, `println` and `readLine`. A generic effect has type arguments: `effect Yielding<A> { ... }`, and `@Yielding<Int> countdown(...)`. It's a `spec` marked `@Effect`. See `examples/handlers.fk`. |
| `Clock(now = ~42) { stamped("x") }`, `Terminal { greet() }` | `handle_clock { now = ... } (fun () -> ...)` | Runs a block with an effect handled: by a handler made from a function for each operation, or by a module that follows the effect's spec. Handlers are values, so one can be chosen at runtime. Handling takes the effect away, so a test that answers `Console` itself, with its state in local `var`s, is pure. A handler that does the effect itself reaches the handler around it, for logging or caching around another. |
| `effect OrdersDb = Database` | | A copy of an effect: the same operations, as a separate effect, so two databases can be handled apart, `OrdersDb(query = postgres) { UsersDb(query = dynamo) { ... } }`. Where several are in reach, an operation is called through the one it's on: `OrdersDb.query(...)`, or `Console.print` as a value. A copy doesn't take the original's annotations, such as its `@HandledBy`. |
| `@HandledBy(Terminal) effect Console { ... }` | | The handler `main` uses for an effect it does but doesn't handle; an effect without one must be handled before `main`. `Fail`'s is `Failure.Crash`: where `main` fails, the program stops, with the reason on standard error and exit code 1. A handler's functions run where it's installed, so what they do, such as failing, is handled outside it, not where the operation was called: an operation that can go wrong gives a `Result`, and a function around it fails where it's called, as `fckt.io`'s `readFile` does. |
| `Fail(fail = (reason, resume) -> Error(reason)) { Ok(parse(text)) }` | `{ fail = fun reason resume -> Error reason }` | A function answering an operation can take one more parameter, `resume`: the rest of the handled block, waiting where the operation was called. Calling it with the operation's result carries on, and gives what the block gives, which is also what the handler gives when it doesn't call it. It can call it straight away, later or never: never is an early exit, later is a generator or a scheduler. It can be called at most once (OCaml's continuations are one-shot). See `examples/resume.fk`. |
| `fail(reason: String): Nothing` | `type fk_nothing = \|` | The type with no values: a call that gives `Nothing` never returns, so it fits wherever it is (`if (bad) fail("why") else 1`). An operation giving `Nothing` can't be answered or resumed, so the code after it never runs. |
| `sequence { naturals(0) }`, `retry(3) { fetch() }` | `sequence (fun () -> ...)` | A block after a function, a method (`path.reading { ... }`), or a call missing its last argument, is that argument, as in Kotlin's trailing lambdas. For a declared function, it's the last parameter, so those before it with defaults can be left out: `writing("out.txt") { ... }` with `append: Boolean = false` in between. |
| `type Queue<@E>(var tasks: List<@E () -> Unit>)` | `type queue = { mutable tasks : (unit -> unit) list }` | An effect parameter: as a type parameter stands for any type, `@E` stands for any effects, so a type that holds functions needn't fix what they do. `Queue<@Console @Clock>` gives it effects, `Queue<@Console @E>` those and a parameter's, `Queue<@Pure>` none, and `Queue` alone whatever's put in. A function takes one the same way, `runQueue<@E>(queue: Queue<@E>)`, and does `@E` when it calls a task, besides what it does itself. A row of effects names one parameter at most. OCaml has no such thing, so it's left out there. See `examples/resume.fk`. |
| `attempt<A>(block: @Fail () -> A)` | | A parameter's function type that names effects may do those and more: what else it does is done where it's called, so `attempt { println("x"); fail("y") }` is `@Console`. An unannotated one does what the function it's given to may do. |
| `@Console @Network annotation type Io` | nothing | An annotation's annotations pass on to whatever it's used on, so `@Io` means `@Console @Network`, in a function type too. `@Meta` ones, like the `@Effect` that `effect` adds, describe the annotation itself and stay put. The same annotation reached two ways with different values is an error, unless it's `@Repeatable` or declared directly, which chooses. |
| `@Persistence @Encapsulates annotation type Repository` | | A spec's annotations pass to its functions, as a module's do. With `@Encapsulates`, what a module following the spec does beyond that stays inside it: every `@Repository` is `@Persistence` to its callers, whether it's Postgres or a list in memory underneath. A handled effect can be hidden only if it has a default, which `main` puts around everything. |
| `@Pure module Ordering { ... }`, `@Io module PlaceOrder { ... }` | | A module's effects pass to everything in it, which can do no more: a `@Pure` module's functions do nothing, and an `@Io` module's can't add `@Clock`. A module inside it can only narrow them. See `examples/ordering.fk`: a pure domain, repositories as specs, a use case as a functor of them, and configurations that make it with real or fake ones. |
| `log: @Console (String) -> Unit` | `log : string -> unit` | A function type says what calling it may do; without effects it's pure. A function that does less fits, so a pure function can be a `@Console (String) -> Unit`, but not the other way round. A parameter's function type without effects takes on its function's, which is how `map` passes them through. |
| `type Account(owner: String, var balance: Int)` | `type account = { owner : string; mutable balance : int }` | A `var` field can be changed: `account.balance = 10`, `account.balance += 5`. |
| `type Tree { Leaf, Node(var left: Tree, value: Int, var right: Tree) }` | `\| Node of { mutable left : tree; ... }` | A case's fields can be `var` too, changed once `is` has said which case it is: `when (t) { is Node -> { t.left = Leaf } ... }`. Not a case shared with another type, which is an OCaml polymorphic variant, whose fields can't change. |
| `var n = 0; n += 1` | `let n = ref 0 in n := !n + 1` | A local that can be changed. `n = value` changes a `var` in scope, as in Kotlin. |
| `@Mutating deposit(account: Account, amount: Int) = ...` | | Changing a `var` is the effect `@Mutating`, which a function declares like any other. A value with `var` fields belongs to a region, which the compiler tracks through its type, so changing a value is changing its region, whatever holds it. Changing a value that was made inside a function and never left it is masked: no one else can see it, so the function can still be pure. A value that leaves, given back or stored somewhere the caller can see, takes its changes with it. See `examples/mutation.fk`. |
| `external type Buffer = "Buffer.t"` | `Buffer.t` wherever it's used | A type OCaml implements, made and used through externals. Each type parameter `P` is written `'p`: `external type Table<K, V> = "('k, 'v) Hashtbl.t"`. An external declared `@Mutating` changes what it's given, so a value of one made inside a function and changed only there leaves it pure. |
| `xs[i]`, `xs[i] = v`, `xs[i] += 1` | `Array.get xs i`, `Array.set xs i v` | Calls `xs.get(i)` and `xs.set(i, v)`, so any type with a `get` and a `set` can be indexed. `Array<A>` (`arrayOf(size, value)`, `list.toArray()`, with `import fckt.collections.Array` and `fckt.collections.Arrays.*`) and `Mutable<A>` (`Mutable(0)`, `counter.value += 1`, with `import fckt.values.Mutable`) are in the standard library. See `examples/arrays.fk`. |
| `module F(M: Monad<Option>)` | `module F (M : Monad with type 'a m = 'a option)` | A spec's type arguments say what a functor's parameter's types are, in order, so they aren't abstract in its body. A type that takes parameters takes a type that takes as many more, at the end or where `_` marks them: `Monad<Option>` makes `M<A>` an `Option<A>`, and `Monad<Result<_, String>>` a `Result<A, String>`. A module says what its types are in its body instead, so `module Options : Monad` takes none. |
| `module SortedSet(M: Ordered) { ... }` | `module SortedSet (M : Ordered) = struct ... end` | A functor: a module that takes modules, in parentheses like a constructor's parameters, since `<...>` is for types. Inside, `M` has only what `Ordered` lists, with its types abstract, so the body works for any module that follows it. Members are used through a module made from it. See `examples/functors.fk`. |
| `module IntSet = SortedSet(IntOrder)` | `module IntSet = SortedSet (IntOrder)` | The module a functor makes. The argument needs everything the spec lists, with the right types. If `IntOrder.T` is `Int`, `IntSet` takes `Int`s; if it's opaque, `IntSet` takes only `IntOrder.T`s. |
| `Options { x by half(n); x + 1 }` | `let ( let* ) = Options.bind in let* x = half n in Options.pure (x + 1)` | A module's name before a block runs the block in it, through the module's `bind` and `pure` (as `Monad` has). `x by m` takes the value out of `m`, or stops the block where `bind` would. The last line goes back in with `pure`. `by` takes a name, `_` or a tuple pattern, and goes only directly in the block, not in a lambda inside it. It works with a functor's parameter too: `M { ... }`. See `examples/monads.fk`. |
| `Validated { n by checkName(s); a by checkAge(i); User(n, a) }` | `let+ n = check_name s and+ a = check_age i in User (n, a)` | A module with `map` and `product` but no `bind` (as `Applicative` has) runs its `by` lines independently. Every line runs, and `product` decides what to keep, so a validation can report every error rather than the first. The lines can't use each other's values and go together: other lines go before them or after them, and lines after can use them. See `examples/independent-steps.fk`. |
| `type Names = List<String>` | `type names = string list` | Another name for a type, with type parameters if needed: `type Pair<A> = (A, A)`. |
| `type Box<T>(value: T)` | `type 't box = { value : 't }` | Type parameters work on types and functions. |
| `f(x: Int, y: Int): Int = e` | `let f (x : int) (y : int) : int = e` | Top-level order doesn't matter; mutual recursion becomes `let rec ... and`. |
| `greet(name: String, greeting: String = "hello")` | `let greet_default_greeting () = "hello"` | A default, as in Kotlin, for a declared function's parameter or a record's or case's field: `type Project(name: String, version: String = "0.1.0")`. A call can leave it out, `Project(name = "shop")`, and pass some in order and the rest by name, `Project("shop", version = "2.0")`. A default is worked out where it's written, without the other arguments, and what it does is done by the call that leaves it out. |
| `name = e` | `let name = e` | A top-level value or a binding in a block. |
| `{ a = 1; f(a) }` | `let a = 1 in f a` | A block is an expression; it ends with its value. Unit expressions before that run in order (`e1; e2`). |
| `x -> e`, `(a, b) -> e` | `fun x -> e`, `fun a b -> e` | Lambdas never use braces, so `{` always starts a block. `_` is a parameter that isn't used: `(reason, _) -> Error(reason)`. |
| `~e` | `fun () -> e`, `fun _ _ -> e` | A function that ignores its arguments. It takes as many as the expected type has, so `~true` can be a `(String, Money) -> Boolean`. With no expected function type it's a thunk, `() -> T`. |
| `x \|> f` | `x \|> f` | |
| `x \|> f(a)` | `x \|> f a` | When `f` takes one more argument than it's given, `x` is passed last. |
| `when (x) { P -> e ... }` | `match x with P -> e ...` | Patterns: literals, `_`, names, `V(p, q)`, `is V`, `(p, q)`, `[]`, `[h, *t]`, and `A, B` for either. A guard, as in Kotlin 2.1, adds a condition that may use what the patterns bound: `Some(n) if n > 0 ->` (OCaml's `| Some n when n > 0 ->`), or `else if ready ->`; a guarded branch doesn't count toward covering every value. A `when` must cover every value, as Kotlin's must over a sealed type; otherwise it's an error that says what's missing, like `this when doesn't cover Some(Error(_))`. Numbers and strings need an `else`. |
| `when (a, b) { ... }` | `match (a, b) with ...` | Matches on several values at once. |
| `(a, b)`, `(A, B)` | `(a, b)`, `a * b` | A tuple, and its type. |
| `(x, y) = e` | `let (x, y) = e in` | Destructures a tuple, or a record's fields in declaration order. |
| `[x, *xs]`, `xs + ys` | `x :: xs`, `xs @ ys` | `*` spreads a list into a list literal, like Kotlin's spread operator. |
| `r.copy(a = x)` | `{ r with a = x }` | |
| `'a'`, `'\u00e9'`, `'\u{1F600}'` | `0x61`, an `int` | A `Char` is one Unicode character, a code point, as OCaml's `int`, so `'😀'` is one. `\uXXXX` and `\u{...}` work in strings too. A `String` is UTF-8, and its `length`, `s[i]`, `take`, `drop`, `substring` and `indexOf` count characters, not bytes: `"ü".length` is 1. |
| `"""raw"""` | a string | Raw strings have no escapes, but still interpolate. |
| `65.toByte()` | `Char.unsafe_chr 65` | A `Byte` is 0 to 255, OCaml's `char`: an `Int`'s low eight bits, so `-1.toByte()` is 255; `b.toInt()` reads it. `fckt.text.Bytes` is a run of them. |
| `lazy(~e)`, `l.value` | `Lazy.from_fun`, `Lazy.force` | |
| `is V -> x.field` | `V x__v -> x__v.field` | `is` narrows a variable, so the branch can read the variant's fields. |
| `when { c -> e; else -> e }` | `if c then e else ...` | |
| `if (c) a else b` | `if c then a else b` | Without `else`, `if (c) println("x")` is a statement, and its branch must be `Unit` (or `Nothing`, as `if (bad) fail("why")`). |
| `x.f(a)` | `f x a` | Calls a top-level `f` on `x` when `x` has no member `f`. |
| `main(args: List<String>)` | `let () = main ...` | `main` may take the command-line arguments. |
| `"$x and ${f(y)}"` | `"" ^ show x ^ ...` | Values print like Kotlin's `toString()`, e.g. `Money(value=3)`. |

`Int`, `Float` (or `Double`), `String`, `Boolean`, `Unit`, `Char`, `Byte`, `List<T>`, `Option<T>`,
`Result<A, E>` and `Lazy<T>` are built in. `Result` is OCaml's `('a, 'e) result`, built with `Ok(x)` and
`Error(e)`, with the value first as in OCaml. `assert(condition)` and `assertEquals(expected, actual)` stop the program
when they fail, giving the line and, for `assertEquals`, both values. `[a, b]` is a list literal, `Some(x)` and `None` build options, and `Unit`, as in Kotlin, is also the one value of its type. Arithmetic on an
`Int` and a `Float` gives a `Float`, as in Kotlin. An `Int` is OCaml's `int`, 63 bits on a 64-bit
machine, so it goes as far as Kotlin's `Long`, about ±4.6 × 10¹⁸, not 2³¹; past that it wraps,
as OCaml's does. There's no `Long`: an `Int` is one. Methods such as `map`, `filter`, `fold`, `sum`,
`size`, `getOrElse` and `toInt`, and functions such as `max` and `sqrt`, are externals in the
standard library, which every file imports; only `toString`, `joinToString`, `assert` and
`assertEquals` are built in.

## Standard library

`src/main/resources/std` is written in fckt, in the package `fckt`, laid out as any program's
packages are: each capitalised file declares one module, type, spec or effect. Functions are in
modules, by what they're for. Every file imports what the language relies on, the console, failing,
and the methods on built-in types, as Kotlin imports `kotlin.*`; everything else is imported as any
other package is. Only what a program uses goes into its OCaml, and a program's own declarations
take the place of what it imports.

| | |
| --- | --- |
| `fckt.lang` | Imported into every file: the annotations the language gives meaning to (`Meta`, `Repeatable`, `Encapsulates`, `HandledBy`, `Effect`), the effects it tracks (`Mutating`, `Pure`), `spec Monad`, which builds on `spec Applicative`, and `Functions` (`identity`, `error`). |
| `fckt.io.Console`, `fckt.io.Terminal` | Imported into every file: the effect `Console` (`print`, `println`, `readLine`), and `Terminal`, the module `main` handles it with. |
| `fckt.effects.Fail`, `fckt.effects.Failure` | Imported into every file: `fail(reason)`, `attempt { ... }`, and `Failure.Crash`, which stops `main` with the reason. |
| `fckt.collections.Lists`, `fckt.text.Strings`, `fckt.math.Maths`, `fckt.Options`, `fckt.Results` | Imported into every file: the methods on lists, Kotlin's `map`, `filter`, `fold`, `zip`, `distinct`, `sorted`, `sortedBy`, `take`, `drop`, `lastOrNull`, `getOrNull`, `count` and more; on strings and characters, with `substring`, `indexOf`, `lastIndexOf`, `split`, `contains`, `take`, `drop`, `removePrefix`, `removeSuffix` and `substringAfterLast`, which take text (`split(",")`), and `toList()`, `code`, `toChar()`, `isDigit()`, `isLetter()` and `isWhitespace()`; `toByte()` and `toInt()` for bytes; numbers' `max`, `sqrt`, `sin` and conversions; and on `Option` and `Result`. |
| `fckt.effects.Yielding`, `fckt.effects.Generators` | `Yielding<A>`, and `sequence { ... }`, which makes a lazy `fckt.collections.Stream<A>`, read with `fckt.collections.Streams`' `take`. |
| `fckt.collections.Array`, `fckt.collections.Arrays` | OCaml's arrays: `arrayOf(size, value)`, `xs[i]`, `xs[i] = v`. |
| `fckt.values.Mutable`, `fckt.values.Lazies` | `Mutable<A>(var value: A)`; `lazy(~e)` and `.value`. |
| `fckt.monads` | The monads, each a module to run blocks in: `Options { x by half(n); x + 1 }`, which, as `Lists` does, follows `Monad`; `Results<E>`, a `Monad` for any error type; `Lists`, for every combination, with `guard`; and `Validated<E>`, an `Applicative` with `Validation<A, E> { Valid, Invalid }`, which keeps every error. |
| `fckt.io` | Files, folders and other programs, as the effects `FileSystem` and `Process`, which `main` does for real and a test can answer itself. A `Path` says where: `path("src/main.fk")` or `"src".toPath()`, always tidied, so `a//b/./c/..` is `a/b`. `Paths` takes one apart without looking at the disk: `name`, `extension`, `nameWithoutExtension`, `parent`, `names`, `resolve("x")`, `withExtension("ml")`, `relativeTo(base)`. A path says nothing of whether anything's there; `Files` asks and acts: `path.exists()`, `isDirectory()`, `readText()`, `readBytes()`, `readLines()`, `writeText(text)`, `appendText(text)`, `writeBytes(bytes)`, `listFiles()`, `makeDirs()`, `delete()`, `copyTo(target)` and `copyRecursively(target)`; what can't be done fails with why. `path.reading { ... }`, `writing { ... }` and `appending { ... }` open a file around a block, which reads it with `nextLine()`, `nextBytes(n)`, or `Channels`' `forEachLine { ... }`, `remainingLines()` and `remainingText()`, or writes with `write`, `writeLine` and `writeBytes`, and close it after, even if the block fails; only the block can use it, so nothing uses it once it's closed. `Processes.streaming(command, args) { forEachLine(...) }` runs a program and reads what it prints as it prints it, giving an `Exit(code, value)`; `run(command, args)` gives everything it printed; `env(name)` reads the environment. Each effect's real handler is its module's `Local`, as `Files.Local`. A program using `Process` needs OCaml's `unix` library, which `fckt` says when it writes one. |
| `fckt.time` | Time, as the effect `Clock` (`now()`), which `main` answers with `SystemClock`, the system's, and a test can answer itself: `Instant`, a moment, and `Duration`, in milliseconds; `Times` has `start.until(end)` and `plus`. |
| `fckt.text.Bytes`, `fckt.text.Encodings` | Raw data, as files hold it: OCaml's immutable `string`, which is bytes, kept apart from text. `bytes.size`, `bytes[i]`, a `Byte`, `toList()`, `bytesOf([104.toByte(), 105.toByte()])`; `text.encodeToBytes()`, and `bytes.decodeToString()`, which replaces what isn't UTF-8 with U+FFFD as Kotlin's does, or `decodeUtf8()`, an `Error` if it isn't. |

See `examples/stdlib.fk`.

## How it works

1. `syntax/`: the lexer and recursive-descent parser produce the fckt AST. Newlines end
   statements only inside braces.
2. `types/Checker.kt`: Hindley–Milner inference with annotations as hints. Top-level declarations
   are checked in dependency order, and each strongly connected group is generalized together.
   The checker records types plus the decisions the translation depends on: which `+` to use, what
   `x.f(...)` means, and when a field or constructor name is ambiguous in OCaml.
3. `ocaml/Grammar.kt`: the OCaml AST, written as [Kastor](../kastor) `@Element` interfaces.
   `ocaml/Lowering.kt` builds the output tree with the generated DSL. OCaml computes arguments
   right to left; where more than one runs code, each is bound to a `let` first, so they run left
   to right, as in Kotlin.
4. `ocaml/Printer.kt`: prints the resolved tree as OCaml, adding the parentheses OCaml needs.

## Using it

```sh
./gradlew installDist
build/install/fckt/bin/fckt examples/orders.fk   # writes examples/orders.ml
ocaml examples/orders.ml
```

`-o out.ml` picks the output file, and `-o -` prints the OCaml instead. A program that needs one of
OCaml's other libraries, as one using `fckt.io` needs `unix`, is run with it: `ocaml -I +unix unix.cma out.ml`. `-I dir` adds a directory whose folders are packages, as the program's own directory's are. The generated code needs
OCaml 5.1 or newer. `./gradlew test` compiles each test program and runs the result with `ocaml`
when it's installed.

### Compiling separately

A program's `.ml` normally has in it what it uses of the standard library and of its packages. A
library can instead be compiled once, and the programs that use it checked against its interface
and linked with its OCaml:

```sh
fckt --standard -o fckt_std.ml                    # the whole standard library, as the module Fckt_std
fckt --library units -o uk_m4xy_units.ml -I src uk.m4xy.units
fckt main.fk -L units -o main.ml                  # uk.m4xy.units is Uk_m4xy_units's
ocamlopt -I +unix unix.cmxa fckt_std.cmx uk_m4xy_units.cmx main.ml -o main
```

`--library <folder>` compiles the packages named to the OCaml module `-o` names, and writes the
library's interface to the folder: each file's public declarations without their code, as
`.fki` files, `sum(items: List<Money>, start: Money = compiled): Money`. Its public functions and
values say their types, so a program's types never depend on code that isn't there. `-L <folder>`
uses a library through its interface, and `--separate`, which `-L` implies, uses the standard
library compiled on its own, rather than putting what's used of it in the program; the program and
its libraries then share one standard library, as handling a library's effects needs.
`fckt --version` says which fckt it is, down to its build, as OCaml compiled by one fckt can't be
linked with another's. [kiln](https://github.com/m4xy/kiln) does all of this for a project.

Kastor comes from `../kastor` through a Gradle included build.

### Tests

fckt has no test library of its own, so what only tests use stays out of programs. What tests are
written with and what runs them are apart: [fk-tests](https://github.com/m4xy/fk-tests) has
`@Test`, and fk-tests-runner runs tests marked with it.

```kotlin
import uk.m4xy.fktests.api.Test
import pricing.Prices

@Test sumsItems() = assert(Prices.total([1, 2, 3]) == 6)
@Test emptyIsZero() = assertEquals(0, Prices.total([]))
```

`fckt --test test --marker uk.m4xy.fktests.api.Test --runner uk.m4xy.fktests.runner.Runner -I src`
compiles the functions in a folder's files marked with `--marker`'s annotation to one program,
whose main has `--runner`'s module make a plan of them, with its `test`, `suite` and `plan`, and
run it, with its `main` (see `compileTests`); the plan's types are the runner's own. A test can't use the runner's package, unless it's the runner's own, with
`--runner-tests`. It takes no parameters, and its effects are main's, handled as main's are; the
folder's other files are what the tests share, and a file with a `main` is a program of its own.
fk-tests' kiln plugin does this for a project's tests.

## Building a project

Building projects, their tests and their dependencies, and publishing libraries, is
[kiln](https://github.com/m4xy/kiln)'s job: a build tool written in fckt.

## Editor support

`editors/intellij` is an IntelliJ IDEA plugin: it colours each name by what it is, as IntelliJ does for Kotlin, shows every one of the compiler's errors as you type, goes to declarations and finds usages, shows on hover each name's inferred type and what a function does (`twice(f: (Int) -> Int, x: Int): Int`, does what f does) and the effects an annotation passes on, and formats code as ktlint formats Kotlin, with two-space indents. `editors/vscode` has a TextMate grammar for VS Code. See each one's README for how to install it.
