# fckt.parser

fckt's source read as fckt's compiler reads it, written in fckt: the start of a compiler that
compiles itself. It splits a source into tokens, and reads them as a file's imports and
declarations:

```kotlin
import fckt.parser.*

declared(source: String): Result<List<String>, List<SyntaxError>> =
  Parser.parse(source).map { file -> file.declarations.map { it.name } }
```

A `Token` is its `Kind`, its text, the `Position` it starts at, and, for a string, its `parts`: a
`Text` as it is, or the `Code` of a `$name` or `${expression}`, with where that starts. A line
break is a `Newline` only where it can end a statement, and the last token is the `End`. A source
it can't split is a `SyntaxError`, where and why, as `Rejecting`'s `reject` says it.

A `SourceFile` is its `Import`s and `Declaration`s. A declaration has its name, its `Visibility`,
whether it's opaque, its annotations, and its `Definition`: a record, a type with cases, an alias,
a function or value, a module, or a spec, an effect being one. A function's body is an
`Expression`; a block's lines are `Statement`s, a when's branches match `Pattern`s, and a type as
written is a `TypeRef`. An extension keeps its receiver, and its type parameters begin with those
the receiver names, as `List<A>`'s `A`. It's the tree as written: what names refer to, and what
types things are, is the checker's.

A file is read a declaration at a time, and after one that can't be, from the next line that
starts at the left edge, so `parse` gives every error, not just the first.

fckt's own tests, `LexerTest` and `ParserTest`, hold it to the compiler's lexer and parser, token
for token and declaration for declaration, over every source in the repository. `kiln build` here
builds it, and `kiln publishLocal` publishes it as `fckt:parser`.
