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
| `ratios.fk` | `module Ratios = struct ... end` | Each file is a module named after it (`shared-cases.fk` is `SharedCases`), with no imports: a name a file uses but doesn't declare brings in the file of that name, from the program's directory or a directory given with `fckt -I dir`. The program is compiled to one `.ml`. |
| `List<A>.second(): Option<A> = ...` | `let list_second (this : 'a list) : 'a option = ...` | An extension function, called as `xs.second()`. It's chosen by the receiver's type, which must be known, so `List<Int>.sum()` and `List<Float>.sum()` can both exist; the most specific one that fits wins. Inside, the receiver is `this`. Bare names in the receiver, like `A`, are its type parameters. Without parentheses, `String.size: Int = ...`, it's a property: `s.size`. See `examples/extensions.fk`. |
| `external List<A>.map<B>(f: (A) -> B): List<B> = "List.map f this"` | `List.map f xs` at each call | A function whose body is OCaml: a function applied to the parameters, by name, with `~name` for a labelled argument. It's written out at each use, so it has no OCaml definition of its own, and the compiler trusts its declared types, as OCaml trusts its own `external`. |
| `private gcd(...)` | left out of the module's `sig` | Visible only inside its module. A public member can't expose a private type. |
| `type Ratio private(n: Int, d: Int)` | `type ratio = private { ... }` in the `sig` | Like Kotlin's `private constructor`: readable and matchable anywhere, but only its module can build or `copy` one. |
| `spec Counter { type T; inc(c: T): T }` | `module type Counter = sig type t val inc : t -> t ... end` | What a module must define. Each of its types gets a show function, so values of it work in string templates. Members are one per line. |
| `module Tally : Counter { type T = Int ... }` | `module Tally = struct ... end` | The module must define everything the spec lists, with the spec's types; generic members must work for any type. Like a Kotlin interface, the spec hides nothing: what it doesn't list stays visible, and `Tally.T` is an `Int` everywhere unless it's opaque. See `examples/specs.fk`. |
| `opaque type T = Int` | `type t` in the module's `sig`, `type t = int` in its body | Outside its module, a `T` can be held and passed back to the module's functions, but not built, used as what it is, looked into or copied. Inside, it's an `Int`. So only the module's functions make one, and they can keep its invariants. Callers outside see a function's type as written, so `of(n: Int): Option<T>` returns an opaque `T`, while a parameter written as `Int` takes an `Int`. Records and types with cases can be opaque too. |
| `annotation type Route(path: String, handler: (Request) -> Response)` | nothing | An annotation, like Kotlin's `annotation class`. Its fields can be ordinary types, checked as usual, so a function passed for `handler` must take a `Request`; or references to what only the compiler knows: `Type`, `Spec`, `Module` or `Module<Spec>` (a module that follows the spec), `Case<T>` (a case of `T`), `Field<R, T>` (a field of the record `R`, `_` for any type), another annotation, or lists and tuples of these. See `examples/annotations.fk`. |
| `@Route("/users", handler = listUsers)` | nothing | On a declaration, a field, a case or a parameter. Arguments are constants and references, never calculations: `1`, `"text"`, `listUsers`, `Shape.Circle`, `User.email`, `IntOrder`, `List<User>`, `[Ordered]`, `Route("/a", greet)`. The compiler checks each against the annotation's declaration and then leaves it out, so it's there for tools that read the program. |
| `@Console greet(name: String) = println("hello $name")` | `let greet name = print_endline ...` | An effect: what a function may do besides work out its result. A function must declare the effects of what it calls, so one that declares none is pure; `main` may do anything, and a top-level value nothing. Doing less than declared is fine: a function's type records what it actually does. Effects pass through function parameters: `xs.map(shout)` is pure and `xs.forEach(println)` does `@Console`, from one `map` and one `forEach`. A spec's function that declares effects, or is in a spec that does, is a promise its modules must keep; one that declares none takes whatever its module does, which shows wherever that module is used. A functor may do whatever its parameters do. See `examples/effects.fk`. |
| `effect Audit` | nothing | Declares an effect that's a label: what a function does through externals or `var`s. The prelude declares `@Mutating`, and `@Pure`, the effect nothing has. `effect` is a keyword only here, so it can still name a value. It's an `annotation type` marked `@Effect`, which is how other annotations see it, and that can be written out too. |
| `effect Clock { now(): Int }` | `type _ Effect.t += Clock_now : unit -> int Effect.t` | A handled effect: its operations are called by name (`now()`, or `Clock.now()` if two effects share a name), and calling one does `@Clock`. Whatever handles the effect around the call answers it, through OCaml 5's effects. The prelude's `Console` is one, with `print`, `println` and `readLine`. A generic effect has type arguments: `effect Yielding<A> { ... }`, and `@Yielding<Int> countdown(...)`. It's a `spec` marked `@Effect`. See `examples/handlers.fk`. |
| `Clock(now = ~42) { stamped("x") }`, `Terminal { greet() }` | `handle_clock { now = ... } (fun () -> ...)` | Runs a block with an effect handled: by a handler made from a function for each operation, or by a module that follows the effect's spec. Handlers are values, so one can be chosen at runtime. Handling takes the effect away, so a test that answers `Console` itself, with its state in local `var`s, is pure. A handler that does the effect itself reaches the handler around it, for logging or caching around another. |
| `@HandledBy(Terminal) effect Console { ... }` | | The handler `main` uses for an effect it does but doesn't handle; an effect without one must be handled before `main`. |
| `Fail(fail = (reason, resume) -> Error(reason)) { Ok(parse(text)) }` | `{ fail = fun reason resume -> Error reason }` | A function answering an operation can take one more parameter, `resume`: the rest of the handled block, waiting where the operation was called. Calling it with the operation's result carries on, and gives what the block gives, which is also what the handler gives when it doesn't call it. It can call it straight away, later or never: never is an early exit, later is a generator or a scheduler. It can be called at most once (OCaml's continuations are one-shot). See `examples/resume.fk`. |
| `fail(reason: String): Nothing` | `type fk_nothing = \|` | The type with no values: a call that gives `Nothing` never returns, so it fits wherever it is (`if (bad) fail("why") else 1`). An operation giving `Nothing` can't be answered or resumed, so the code after it never runs. |
| `sequence { naturals(0) }`, `retry(3) { fetch() }` | `sequence (fun () -> ...)` | A block after a function, or after a call missing its last argument, is that argument, as in Kotlin's trailing lambdas. |
| `type Queue<@E>(var tasks: List<@E () -> Unit>)` | `type queue = { mutable tasks : (unit -> unit) list }` | An effect parameter: as a type parameter stands for any type, `@E` stands for any effects, so a type that holds functions needn't fix what they do. `Queue<@Console @Clock>` gives it effects, `Queue<@Console @E>` those and a parameter's, `Queue<@Pure>` none, and `Queue` alone whatever's put in. A function takes one the same way, `runQueue<@E>(queue: Queue<@E>)`, and does `@E` when it calls a task, besides what it does itself. A row of effects names one parameter at most. OCaml has no such thing, so it's left out there. See `examples/resume.fk`. |
| `attempt<A>(block: @Fail () -> A)` | | A parameter's function type that names effects may do those and more: what else it does is done where it's called, so `attempt { println("x"); fail("y") }` is `@Console`. An unannotated one does what the function it's given to may do. |
| `@Console @Network annotation type Io` | nothing | An annotation's annotations pass on to whatever it's used on, so `@Io` means `@Console @Network`, in a function type too. `@Meta` ones, like the `@Effect` that `effect` adds, describe the annotation itself and stay put. The same annotation reached two ways with different values is an error, unless it's `@Repeatable` or declared directly, which chooses. |
| `@Persistence @Encapsulates annotation type Repository` | | A spec's annotations pass to its functions, as a module's do. With `@Encapsulates`, what a module following the spec does beyond that stays inside it: every `@Repository` is `@Persistence` to its callers, whether it's Postgres or a list in memory underneath. A handled effect can be hidden only if it has a default, which `main` puts around everything. |
| `@Pure module Ordering { ... }`, `@Io module PlaceOrder { ... }` | | A module's effects pass to everything in it, which can do no more: a `@Pure` module's functions do nothing, and an `@Io` module's can't add `@Clock`. A module inside it can only narrow them. See `examples/ordering.fk`: a pure domain, repositories as specs, a use case as a functor of them, and configurations that make it with real or fake ones. |
| `log: @Console (String) -> Unit` | `log : string -> unit` | A function type says what calling it may do; without effects it's pure. A function that does less fits, so a pure function can be a `@Console (String) -> Unit`, but not the other way round. A parameter's function type without effects takes on its function's, which is how `map` passes them through. |
| `type Account(owner: String, var balance: Int)` | `type account = { owner : string; mutable balance : int }` | A `var` field can be changed: `account.balance = 10`, `account.balance += 5`. Only a record's fields can be `var`. |
| `var n = 0; n += 1` | `let n = ref 0 in n := !n + 1` | A local that can be changed. `n = value` changes a `var` in scope, as in Kotlin. |
| `@Mutating deposit(account: Account, amount: Int) = ...` | | Changing a `var` is the effect `@Mutating`, which a function declares like any other. A value with `var` fields belongs to a region, which the compiler tracks through its type, so changing a value is changing its region, whatever holds it. Changing a value that was made inside a function and never left it is masked: no one else can see it, so the function can still be pure. A value that leaves, given back or stored somewhere the caller can see, takes its changes with it. See `examples/mutation.fk`. |
| `external type Buffer = "Buffer.t"` | `Buffer.t` wherever it's used | A type OCaml implements, made and used through externals. Each type parameter `P` is written `'p`: `external type Table<K, V> = "('k, 'v) Hashtbl.t"`. An external declared `@Mutating` changes what it's given, so a value of one made inside a function and changed only there leaves it pure. |
| `xs[i]`, `xs[i] = v`, `xs[i] += 1` | `Array.get xs i`, `Array.set xs i v` | Calls `xs.get(i)` and `xs.set(i, v)`, so any type with a `get` and a `set` can be indexed. `Array<A>` (`arrayOf(size, value)`, `list.toArray()`) and `Mutable<A>` (`Mutable(0)`, `counter.value += 1`) are in the prelude. See `examples/arrays.fk`. |
| `module F(M: Monad<Option>)` | `module F (M : Monad with type 'a m = 'a option)` | A spec's type arguments say what a functor's parameter's types are, in order, so they aren't abstract in its body. A type that takes parameters takes a type that takes as many more, at the end or where `_` marks them: `Monad<Option>` makes `M<A>` an `Option<A>`, and `Monad<Result<_, String>>` a `Result<A, String>`. A module says what its types are in its body instead, so `module Options : Monad` takes none. |
| `module SortedSet(M: Ordered) { ... }` | `module SortedSet (M : Ordered) = struct ... end` | A functor: a module that takes modules, in parentheses like a constructor's parameters, since `<...>` is for types. Inside, `M` has only what `Ordered` lists, with its types abstract, so the body works for any module that follows it. Members are used through a module made from it. See `examples/functors.fk`. |
| `module IntSet = SortedSet(IntOrder)` | `module IntSet = SortedSet (IntOrder)` | The module a functor makes. The argument needs everything the spec lists, with the right types. If `IntOrder.T` is `Int`, `IntSet` takes `Int`s; if it's opaque, `IntSet` takes only `IntOrder.T`s. |
| `Options { x by half(n); x + 1 }` | `let ( let* ) = Options.bind in let* x = half n in Options.pure (x + 1)` | A module's name before a block runs the block in it, through the module's `bind` and `pure` (as `Monad` has). `x by m` takes the value out of `m`, or stops the block where `bind` would. The last line goes back in with `pure`. `by` takes a name, `_` or a tuple pattern, and goes only directly in the block, not in a lambda inside it. It works with a functor's parameter too: `M { ... }`. See `examples/monads.fk`. |
| `Validated { n by checkName(s); a by checkAge(i); User(n, a) }` | `let+ n = check_name s and+ a = check_age i in User (n, a)` | A module with `map` and `product` but no `bind` (as `Applicative` has) runs its `by` lines independently. Every line runs, and `product` decides what to keep, so a validation can report every error rather than the first. The lines can't use each other's values and go together: other lines go before them or after them, and lines after can use them. See `examples/independent-steps.fk`. |
| `type Names = List<String>` | `type names = string list` | Another name for a type, with type parameters if needed: `type Pair<A> = (A, A)`. |
| `type Box<T>(value: T)` | `type 't box = { value : 't }` | Type parameters work on types and functions. |
| `f(x: Int, y: Int): Int = e` | `let f (x : int) (y : int) : int = e` | Top-level order doesn't matter; mutual recursion becomes `let rec ... and`. |
| `name = e` | `let name = e` | A top-level value or a binding in a block. |
| `{ a = 1; f(a) }` | `let a = 1 in f a` | A block is an expression; it ends with its value. Unit expressions before that run in order (`e1; e2`). |
| `x -> e`, `(a, b) -> e` | `fun x -> e`, `fun a b -> e` | Lambdas never use braces, so `{` always starts a block. `_` is a parameter that isn't used: `(reason, _) -> Error(reason)`. |
| `~e` | `fun () -> e`, `fun _ _ -> e` | A function that ignores its arguments. It takes as many as the expected type has, so `~true` can be a `(String, Money) -> Boolean`. With no expected function type it's a thunk, `() -> T`. |
| `x \|> f` | `x \|> f` | |
| `x \|> f(a)` | `x \|> f a` | When `f` takes one more argument than it's given, `x` is passed last. |
| `when (x) { P -> e ... }` | `match x with P -> e ...` | Patterns: literals, `_`, names, `V(p, q)`, `is V`, `(p, q)`, `[]`, `[h, *t]`, and `A, B` for either. A `when` must cover every value, as Kotlin's must over a sealed type; otherwise it's an error that says what's missing, like `this when doesn't cover Some(Error(_))`. Numbers and strings need an `else`. |
| `when (a, b) { ... }` | `match (a, b) with ...` | Matches on several values at once. |
| `(a, b)`, `(A, B)` | `(a, b)`, `a * b` | A tuple, and its type. |
| `(x, y) = e` | `let (x, y) = e in` | Destructures a tuple, or a record's fields in declaration order. |
| `[x, *xs]`, `xs + ys` | `x :: xs`, `xs @ ys` | `*` spreads a list into a list literal, like Kotlin's spread operator. |
| `r.copy(a = x)` | `{ r with a = x }` | |
| `'a'`, `"""raw"""` | `'a'`, a string | Raw strings have no escapes, but still interpolate. |
| `lazy(~e)`, `l.value` | `Lazy.from_fun`, `Lazy.force` | |
| `is V -> x.field` | `V x__v -> x__v.field` | `is` narrows a variable, so the branch can read the variant's fields. |
| `when { c -> e; else -> e }` | `if c then e else ...` | |
| `if (c) a else b` | `if c then a else b` | Without `else`, `if (c) println("x")` is a statement, and its branch must be `Unit` (or `Nothing`, as `if (bad) fail("why")`). |
| `x.f(a)` | `f x a` | Calls a top-level `f` on `x` when `x` has no member `f`. |
| `main(args: List<String>)` | `let () = main ...` | `main` may take the command-line arguments. |
| `"$x and ${f(y)}"` | `"" ^ show x ^ ...` | Values print like Kotlin's `toString()`, e.g. `Money(value=3)`. |

`Int`, `Float` (or `Double`), `String`, `Boolean`, `Unit`, `Char`, `List<T>`, `Option<T>`,
`Result<A, E>` and `Lazy<T>` are built in. `Result` is OCaml's `('a, 'e) result`, built with `Ok(x)` and
`Error(e)`, with the value first as in OCaml. `assert(condition)` and `assertEquals(expected, actual)` stop the program
when they fail, giving the line and, for `assertEquals`, both values. `[a, b]` is a list literal, `Some(x)` and `None` build options, and `Unit`, as in Kotlin, is also the one value of its type. Arithmetic on an
`Int` and a `Float` gives a `Float`, as in Kotlin. Methods such as `map`, `filter`, `fold`, `sum`,
`size`, `getOrElse` and `toInt`, and functions such as `max` and `sqrt`, are externals in
`std/prelude.fk`; only `toString`, `joinToString`, `assert` and `assertEquals` are built in.

## Standard library

`src/main/resources/std` is written in fckt, and comes into a program as it's used, like any
other file. Only what a program mentions goes into its OCaml, and a program's own declaration or
file of the same name takes its place.

| | |
| --- | --- |
| `prelude.fk` | Needs no module's name: `spec Monad`, `spec Applicative`, `type Validation<A, E> { Valid(value: A), Invalid(errors: List<E>) }`, and the methods and functions on built-in types: lists have Kotlin's `map`, `filter`, `fold`, `zip`, `distinct`, `sorted`, `sortedBy`, `take`, `drop`, `lastOrNull`, `getOrNull`, `count` and more. The effects `Console` (with `Terminal`), `Mutating`, `Fail` with `attempt { ... }`, and `Yielding<A>` with `sequence { ... }`, which makes a lazy `Stream<A>`. A program's own effect, or operation, of the same name takes the place of the prelude's. |
| `Options` | Option as a monad: `Options { x by half(n); x + 1 }`. |
| `Results` | Result as a monad, for any error type, with `mapError` and `toOption`. |
| `Validated` | Validation without bind, so `Validated { ... }` keeps every error; `fromResult` and `toResult`. |
| `Lists` | List as a monad, for every combination: `Lists { x by xs; y by ys; (x, y) }`, with `guard`. |

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

`-o out.ml` picks the output file, and `-o -` prints the OCaml instead. `-I dir` adds a directory to look in for the files a program uses. The generated code needs
OCaml 5.1 or newer. `./gradlew test` compiles each test program and runs the result with `ocaml`
when it's installed.

Kastor comes from `../kastor` through a Gradle included build.

## Editor support

`editors/intellij` is an IntelliJ IDEA plugin: it colours each name by what it is, as IntelliJ does for Kotlin, shows every one of the compiler's errors as you type, goes to declarations and finds usages, shows on hover each name's inferred type and what a function does (`twice(f: (Int) -> Int, x: Int): Int`, does what f does) and the effects an annotation passes on, and formats code as ktlint formats Kotlin, with two-space indents. `editors/vscode` has a TextMate grammar for VS Code. See each one's README for how to install it.
