#!/bin/sh
# Builds fckt with OCaml alone, from boot/fckt.ml: the compiler written in fckt, as the OCaml it
# wrote of itself. That compiles the compiler's source, so what's built is the source's, not the
# seed's, to build/boot, laid out as kiln install lays it out: bin/fckt, and lib/fckt with the core
# and the standard library it reads. Put build/boot/bin on PATH, then bootstrap kiln.
#
#   ./bootstrap.sh            builds it
#   ./bootstrap.sh --check    then has it compile itself again, which must give the same OCaml
#   ./bootstrap.sh --refresh  then makes that OCaml the seed, once the source needs a newer one
set -e
cd "$(dirname "$0")"
out=build/boot
sources="compiler/src/main.fk -I compiler/src -I lowering/src -I checker/src -I parser/src"
rm -rf "$out"
mkdir -p "$out/bin" "$out/lib/fckt/std" "$out/ocaml"
cp -R src/main/resources/std "$out/lib/fckt/core"
cp -R std/src "$out/lib/fckt/std/src"

# ocamlopt writes what it compiles beside the source, so the source is copied out of boot first.
native() {
  ocamlopt -I +unix unix.cmxa -w -a "$1" -o "$2"
}

echo "fckt: building the seed"
cp boot/fckt.ml "$out/ocaml/seed.ml"
native "$out/ocaml/seed.ml" "$out/bin/fckt"
echo "fckt: compiling the compiler with it"
"$out/bin/fckt" $sources -o "$out/ocaml/fckt.ml"
native "$out/ocaml/fckt.ml" "$out/bin/fckt"

case "$1" in
  --check|--refresh)
    echo "fckt: compiling the compiler with itself"
    "$out/bin/fckt" $sources -o "$out/ocaml/again.ml"
    if ! cmp -s "$out/ocaml/fckt.ml" "$out/ocaml/again.ml"; then
      echo "fckt: the compiler doesn't compile itself to what it was compiled from" >&2
      exit 1
    fi
    ;;
esac
if [ "$1" = --refresh ]; then
  cp "$out/ocaml/fckt.ml" boot/fckt.ml
  echo "fckt: boot/fckt.ml is the compiler as its source is now"
fi
"$out/bin/fckt" --version
