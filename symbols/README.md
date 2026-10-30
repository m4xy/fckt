# fckt.symbols

What `fckt --symbols` writes, as typed values, for a program that reads a project's types, as a
processor that writes code from them does. fckt's README sets out the format; this reads it, and
writes it back, as a `Format<T = Symbols>`, `SymbolsText`:

```kotlin
import fckt.symbols.*
import fckt.symbols.Declarations.*

serializable(text: String): Result<List<TypeDeclaration>, String> =
  SymbolsText.read(text).map { it.annotatedWith("uk.m4xy.serialization.Serializable") }
```

A `Symbols` is the project's `TypeDeclaration`s, each by its full name, with its `Location`, its
type parameters, its annotations, and its `Body`: a record's `Fields`, or its `Cases`. A field's
type is a `TypeRef`, and an annotation's arguments are `Value`s, constants or references by full
name. `Declarations` finds what a processor looks for: the types annotated with something, an
annotation by name, and what it was given for a field.

The format names its version on its first line, and `SymbolsText` reads only the version it knows,
`fckt-symbols 1`, and only the lines that version has, so a program never works from symbols it half
understood.

## Processors

A processor writes code from a project's symbols: a module that follows `Processor`, from
`fckt.symbols.processing`, whose `process(symbols)` says the `GeneratedFile`s to write, each a path
from the folder they go in and its text, or the `Problem`s that stop it, each with where it is. It
reads and writes nothing itself, so its tests give it `Symbols` and look at what it says.
`Processing.run(processor, args)` is a main that runs it, given the file of symbols and the folder
to write to; [kiln](https://github.com/m4xy/kiln) runs a project's processors so, before compiling
it.

```kotlin
module Describer : Processor {
  process(symbols: Symbols): Result<List<GeneratedFile>, List<Problem>> =
    Ok(symbols.annotatedWith("uk.m4xy.describe.Describe").map { d ->
      GeneratedFile("${d.simpleName}Description.fk", "module ${d.simpleName}Description { ... }\n")
    })
}
```

`kiln build` here builds and tests it, and `kiln publishLocal` publishes it, as `fckt:symbols:0.1.0`.
