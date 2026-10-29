# fckt.checker

What fckt's names refer to and what type everything is, worked out as fckt's own compiler works
them out, written in fckt: the next part, after `fckt.parser`, of a compiler that compiles itself.

It's meant to be read. Each package is one stage or one idea, with its own types for what it
works out, and each file starts by saying what it's for, so a package can be understood without
those that come after it.

## Packages

Each stage in the order it runs, then what any stage may use:

| Package | What it's for |
|---|---|
| `fckt.checker.sources` | Reading the files a program uses: `Loader.load` takes its own file and a `SourceTree`, every package it could use, and reads its own package and each it imports, and so each those import, whole. A `LoadedFile` is a file read, with its package, its `FileRole` and its `Project`. |
| `fckt.checker.expansion` | Filling in what declarations say, before anything is checked: a copy of an effect has the original's operations, a spec has those it builds on's types and values, a module has its spec's defaults it doesn't define, and a module's type parameters are its functions' too. |
| `fckt.checker.symbols` | Naming every declaration by its full name, `geometry.Shapes.area`, as a `Symbol`, in a `SymbolTable` of each kind: modules, packages among them, records, types with cases, aliases, annotation types, specs, functions and cases. A name declared twice gives up. |
| `fckt.checker.resolution` | What names mean. `Imports` works out what each file's imports bring in; `Visibility` says who can see a declaration; `Names.find` looks a name up from a `Place`, out to its package and then through its file's imports, and `Lookups` does so for each kind; `TypeNames` says what a type's name means; `WrittenTypes` finds each type's name written in a file. |
| `fckt.checker.annotations` | What annotations mean: which annotation type or effect each names, and what each passes on, as `@Io` may mean `@Console @Network`. |
| `fckt.checker.definitions` | What type declarations define: each type with cases' `CaseInfo`s, own, named or included; the types whose values can change, which have regions; and `Conversion`, which makes a written type a `Type`, its aliases expanded and its rows of effects made. |
| `fckt.checker.inference` | The type of every expression and every function. `Groups` orders functions so each is checked after what it uses, those that use each other together; `functions.fk` checks a group and makes each function's type generic; the other lower-case files check each kind of expression: `calls`, `constructors`, `members`, `operators`, `lambdas`, `handlers`, `patterns`, `whens`, `blocks`. They call one another, so they're the package's own, a file for each, not modules, which can't. What code does is the `Doing` effect, handled around each function's and lambda's body. |
| `fckt.checker` | `Checker`, which runs the stages in turn. |
| `fckt.checker.types` | The `Type` model, `Scheme`s, and working types out: the `Solving` effect, whose handler, `Solutions.solving`, keeps what's been learned; `Unification`, which makes two types the same, rows of effects included; `Types`, the language's own types and what a type is once solutions are followed; `TypeText`, types as messages show them. |
| `fckt.checker.coverage` | Whether a `when`'s patterns cover every value, by Maranget's usefulness check. It knows nothing of types: it's told the `Alternative` shapes a value can have. |
| `fckt.checker.diagnostics` | Errors, as data: a `Diagnostic` says where and why, worded as the compiler words it. `Reporting` reports one and goes on; `Abandoning` gives up on what's being checked, and `Diagnostics.recovering` carries on after it. |

Still to come: functors, their parameters and the modules they make; what a module that follows a
spec must define; opaque types a spec's type arguments fix; and checking annotations' arguments.

## How it's written

What a stage learns as it goes is an effect, handled once around it, rather than a parameter
passed everywhere: a function that works types out is `@Solving`, and says so. What's learned is
in the handler, in `var`s that nothing else can see, so the stage as a whole is pure.

A type in the compiler that's told apart by a marker in its name, such as `abstract ` or `module `,
is a case of its own here.

## Tests

`test/` holds unit tests for each package, which `kiln build` runs. fckt's own tests hold the
checker to the compiler over every source in the repository, as they do the lexer and parser, with
`src/test/fckt/checker.fk` running it: `NamesTest` checks each type's name means the same
declaration, and `TypesTest` that each function and value has the same type, in each conformance
case and each package, fckt.checker's own among them; `CheckerErrorsTest` checks each conformance
case the compiler rejects gives the same errors, each where it is and worded the same.
