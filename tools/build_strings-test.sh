#!/usr/bin/env bash
#
# Exercise build_strings.sh on catalogs keyed by a lang.def symbol instead of by
# English text. No shipped catalog uses one yet, so this is the only cover.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/build_strings.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# lang.def pairs a symbol with its English text, symbol first. It keeps the real
# file's shape: the LANGUAGE_ rows a catalog header needs, and two symbols
# sharing "Cancel", which 27 of its texts really do.
mkdir -p "$tmp/lang" "$tmp/res/values"
cat >"$tmp/lang.def" <<'DEF'
Francais
LANGUAGE_1
LANGUAGE_NAME
LANGUAGE_NAME
LANGUAGE_FILE
LANGUAGE_FILE
LANGUAGE_ISO
LANGUAGE_ISO
LANG_OK
OK
LANG_CANCEL
Cancel
LANG_B3
Cancel
LANG_START
Start
LISTDEF_1
One\nTwo
LANG_LITERAL
LANG_OK
LANG_B3
Cancel
LANG_INFO
Info type:
DEF

cat >"$tmp/res/values/strings.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="ok">OK</string>
    <string name="cancel">Cancel</string>
    <string name="start">Start</string>
    <string name="one">One</string>
    <string name="two">Two</string>
    <string name="literal">LANG_OK</string>
    <string name="info">Info type</string>
</resources>
XML

out="$tmp/res/values-fr/string.xml"
err="$tmp/err"

# Write a catalog from stdin, under the 6-line header the script requires, and
# regenerate from it.
run() {
    {
        printf 'LANGUAGE_NAME\nFrancais\nLANGUAGE_FILE\nFrancais\nLANGUAGE_ISO\nfr\n'
        cat
    } >"$tmp/lang/Francais.txt"
    rm -rf "$tmp/res/values-fr"
    bash "$SUT" "$tmp/lang" "$tmp/res" >/dev/null 2>"$err"
}

fail=0
pass() {
    echo "PASS: $1"
}
bad() {
    echo "FAIL: $1"
    fail=1
}

# Checks that the generator filled this string with that value.
has() {
    local label="$1" name="$2" value="$3"
    if grep -qF "<string name=\"$name\">$value</string>" "$out"; then
        pass "$label"
    else
        bad "$label (wanted $name = $value)"
        grep -F "name=\"$name\"" "$out" || true
    fi
}

# Checks that the generator left this string out, writing it back as a comment.
# Asserting the comment, not the absence of a value, fails on an empty file too.
missing() {
    local label="$1" name="$2" text="$3"
    if grep -qF "[string name=\"$name\"]${text}[/string]" "$out"; then
        pass "$label"
    else
        bad "$label ($name should carry no value)"
    fi
}

run <<'CAT'
OK
Valider
LANG_CANCEL
Annuler
LISTDEF_1
Un\nDeux
LANG_NOSUCH
Jamais
LANG_OK
TexteLitteral
LANG_INFO
Type info
One
UnTexte
CAT
has "an English-text key still resolves" ok Valider
has "a LANG_ symbol key resolves" cancel Annuler
has "a LISTDEF_ symbol splits on \\n, first line" one Un
has "a LISTDEF_ symbol splits on \\n, second line" two Deux
# The row keyed One below the LISTDEF_ line must not take the slot its first
# fragment filled, because a fragment belongs to the symbol that carried it.
has "a split fragment keeps its symbol's claim" one Un
missing "a string no catalog line carries stays empty" start Start
# lang.def gives "LANG_OK" as a string's own English text, so a line keyed that
# way is text, not the symbol of the same name.
has "a key lang.def knows as text is not read as a symbol" literal TexteLitteral
# lang.def spells this text with a trailing colon and strings.xml does not, so
# the resolved key matches only when lang.def goes through lang_filter as well.
has "lang.def text is cleaned up like a catalog key" info "Type info"
if grep -q 'LANG_NOSUCH' "$err"; then
    pass "a symbol absent from lang.def is reported"
else
    bad "a symbol absent from lang.def is reported"
fi

# A catalog mid-re-key holds both kinds of line for one string. The symbol must
# win either way round, or the result would depend on row order.
run <<'CAT'
Start
FromText
LANG_START
FromSymbol
CAT
has "symbol beats a text line above it" start FromSymbol

run <<'CAT'
LANG_START
FromSymbol
Start
FromText
CAT
has "symbol beats a text line below it" start FromSymbol

# One symbol twice: the engine keeps its first line, so keep the first.
run <<'CAT'
LANG_START
Premier
LANG_START
Second
CAT
has "the first of two lines for one symbol wins" start Premier

# A catalog converted line by line must generate what it generated before, so
# re-keying it changes no string. LANG_CANCEL and LANG_B3 share one text here,
# which is why the last of several symbols has to win like the last text line.
run <<'CAT'
Start
Demarrer
Cancel
Annuler
Cancel
Abandonner
Info type:
Type info
One\nTwo
Un\nDeux
CAT
cp "$out" "$tmp/text-keyed.xml"
run <<'CAT'
LANG_START
Demarrer
LANG_CANCEL
Annuler
LANG_B3
Abandonner
LANG_INFO
Type info
LISTDEF_1
Un\nDeux
CAT
if diff -q "$tmp/text-keyed.xml" "$out" >/dev/null; then
    pass "a re-keyed catalog generates the same file"
else
    bad "a re-keyed catalog generates the same file"
    diff "$tmp/text-keyed.xml" "$out" || true
fi

# The symbol-to-text table comes from lang.def, so a missing one must stop the
# run rather than quietly drop every symbol-keyed line.
rc=0
bash "$SUT" "$tmp/lang" "$tmp/res" /nonexistent/lang.def >/dev/null 2>&1 || rc=$?
if test "$rc" -eq 1; then
    pass "a missing lang.def fails the run"
else
    bad "a missing lang.def fails the run (exit $rc)"
fi

test "$fail" -eq 0 && echo "build_strings-test: all cases pass"
exit "$fail"
