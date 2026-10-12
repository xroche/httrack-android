#!/usr/bin/env bash
#
# Exercise build_strings.sh on catalogs keyed by a lang.def symbol instead of by
# English text. No shipped catalog uses one yet, so this is the only cover.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/build_strings.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# lang.def pairs a symbol with its English text, symbol first. "Cancel" is there
# twice, as the real file has it, so a repeated text cannot resolve to a symbol
# by accident.
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

# A string the generator filled, with that exact value.
has() {
    if grep -qF "<string name=\"$2\">$3</string>" "$out"; then
        pass "$1"
    else
        bad "$1 (wanted $2 = $3)"
        grep -F "name=\"$2\"" "$out" || true
    fi
}

# A string the generator left out, which it writes back as a comment.
missing() {
    if grep -qF "<string name=\"$2\">" "$out"; then
        bad "$1 ($2 should have no value)"
    else
        pass "$1"
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
CAT
has "an English-text key still resolves" ok Valider
has "a LANG_ symbol key resolves" cancel Annuler
has "a LISTDEF_ symbol splits on \\n, first line" one Un
has "a LISTDEF_ symbol splits on \\n, second line" two Deux
missing "a string no catalog line carries stays empty" start
# lang.def gives "LANG_OK" as a string's own English text, so a line keyed that
# way is text, not the symbol of the same name.
has "a key lang.def knows as text is not read as a symbol" literal TexteLitteral
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

# Two symbol lines for one string: the engine keeps the first, so keep the first.
run <<'CAT'
LANG_START
Premier
LANG_START
Second
CAT
has "the first of two symbol lines wins" start Premier

# The symbol-to-text table comes from lang.def, so a missing one must stop the
# run rather than quietly drop every symbol-keyed line.
run <<'CAT' || true
OK
Valider
CAT
rc=0
bash "$SUT" "$tmp/lang" "$tmp/res" /nonexistent/lang.def >/dev/null 2>&1 || rc=$?
if test "$rc" -eq 1; then
    pass "a missing lang.def fails the run"
else
    bad "a missing lang.def fails the run (exit $rc)"
fi

exit "$fail"
