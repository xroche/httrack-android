#!/usr/bin/env bash
#
# Exercise build_strings.sh on catalogs keyed by a symbol instead of by English
# text. No shipped catalog uses one yet, so this is the only cover.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/build_strings.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# lang-symbols.tsv gives kind, symbol and text, one row each. Two string symbols
# share one English text here, as 27 of the real texts do. The columns are
# tab-separated, so write the file with printf rather than a quoted heredoc.
mkdir -p "$tmp/lang" "$tmp/res/values"
printf '# a comment row, as the real file carries five of\n' >"$tmp/lang-symbols.tsv"
printf '%s\t%s\t%s\n' \
    language LANGUAGE_1 Francais \
    meta LANGUAGE_NAME LANGUAGE_NAME \
    meta LANGUAGE_FILE LANGUAGE_FILE \
    meta LANGUAGE_ISO LANGUAGE_ISO \
    string LANG_OK OK \
    string LANG_CANCEL Cancel \
    string LANG_B3 Cancel \
    string LANG_START Start \
    string LISTDEF_1 'One\nTwo' \
    string LANG_LITERAL LANG_OK \
    string LANG_B3 Cancel \
    string LANG_INFO 'Info type:' >>"$tmp/lang-symbols.tsv"

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
# The table gives "LANG_OK" as a string's own English text, so a line keyed that
# way is text, not the symbol of the same name.
has "a key the table knows as text is not read as a symbol" literal TexteLitteral
# The table spells this text with a trailing colon and strings.xml does not, so
# the resolved key matches only when the text column goes through lang_filter too.
has "table text is cleaned up like a catalog key" info "Type info"
if grep -q 'LANG_NOSUCH' "$err"; then
    pass "a symbol absent from the table is reported"
else
    bad "a symbol absent from the table is reported"
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

# A missing table must stop the run rather than quietly drop every symbol-keyed
# line.
rc=0
bash "$SUT" "$tmp/lang" "$tmp/res" /nonexistent/lang-symbols.tsv >/dev/null 2>"$err" || rc=$?
if test "$rc" -eq 1 && grep -q 'lang-symbols.tsv not found' "$err"; then
    pass "a missing table fails the run and says so"
else
    bad "a missing table fails the run and says so (exit $rc)"
    cat "$err" || true
fi

# Every case above runs on a fixture, so none would notice the pinned engine losing
# lang-symbols.tsv or changing its shape. Resolve one of the real table's own symbols.
engine="$HERE/../app/src/main/jni/httrack"
if ! test -d "$engine/lang"; then
    echo "SKIP: no engine submodule, so the pinned table is unchecked" >&2
elif ! test -f "$engine/lang-symbols.tsv"; then
    bad "the pinned engine carries lang-symbols.tsv"
else
    table="$engine/lang-symbols.tsv"
    sym="$(awk -F'\t' '$1 == "string" { print $2; exit }' "$table")"
    text="$(awk -F'\t' '$1 == "string" { print $3; exit }' "$table")"
    # A glob, not find piped to head, which pipefail turns into a failure.
    catalogs=("$engine"/lang/*.txt)
    catalog="${catalogs[0]}"
    pin="$tmp/pin"
    mkdir -p "$pin/lang" "$pin/res/values"
    printf '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="probe">%s</string>\n</resources>\n' \
        "$text" >"$pin/res/values/strings.xml"
    {
        printf 'LANGUAGE_NAME\nFrancais\nLANGUAGE_FILE\nFrancais\nLANGUAGE_ISO\nfr\n'
        printf '%s\nTraduit\n' "$sym"
    } >"$pin/lang/$(basename "$catalog")"
    bash "$SUT" "$pin/lang" "$pin/res" "$table" >/dev/null 2>&1
    if grep -qF '<string name="probe">Traduit</string>' "$pin/res/values-fr/string.xml"; then
        pass "the pinned table resolves its own first symbol ($sym)"
    else
        bad "the pinned table resolves its own first symbol ($sym)"
        grep -F 'name="probe"' "$pin/res/values-fr/string.xml" || true
    fi
fi

test "$fail" -eq 0 && echo "build_strings-test: all cases pass"
exit "$fail"
