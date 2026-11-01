#!/bin/bash
#

# Convert httrack lang/*;txt files into android xml resources

# usage: $0 path-to/lang path-to/res [path-to/lang-symbols.tsv]
# lang-symbols.tsv defaults to the one next to the lang directory
# example:
#	rm -rf res/values-?? res/values-??-r??	# language dirs only: values-* also takes the
#					# hand-kept values-v34, values-night and the rest
#	bash tools/build_strings.sh app/src/main/jni/httrack/lang app/src/main/res/

function norm_key {
	echo "$1" | sed -e 's/[^a-zA-Z0-9]/_/g'
}

# Clean up catalog text. A table text goes through it too, so a symbol resolves
# to the same key as its English text.
function lang_filter {
	tr -d '\r' \
		| sed -e 's/\\r//g' \
		| sed -e 's/:$//' -e 's/\* //g' -e 's/\.\.\.$//g' -e 's/\([[:space:]]\)[[:space:]]*/\1/g' -e 's/[[:space:]]*$//' \
		| sed -e 's/ & / @@amp@@ /g' -e 's/&//g' -e 's/@@amp@@/\&amp;/g' \
		| sed -e 's/WinHTTrack/HTTrack/g' \
		| sed -e "s/1998-2003/1998-$(date +%Y)/g" \
		| sed -e "s/(C)/\&#169;/g"
}

# Store one translation, keyed by the table symbol in $3, or by its text when
# $3 is empty. A symbol line beats a text line, and is skipped only when the same
# symbol already set this key, so several symbols sharing a text keep the last.
function put_str {
	if test -n "$3"; then
		test "${sym_owner[$1]}" = "$3" && return 0
		sym_owner["$1"]="$3"
	elif test -n "${sym_owner[$1]}"; then
		return 0
	fi
	arr["$1"]="$2"
}

test -d "$1" || ! echo "lang directory not found: $1" >&2 || exit 1
test -d "$2" || ! echo "res directory not found: $2" >&2 || exit 1
symtab="${3:-$1/../lang-symbols.tsv}"
test -f "$symtab" || ! echo "lang-symbols.tsv not found: $symtab (give its path as the 3rd argument)" >&2 || exit 1

# The string rows are the translatable ones, in file order, so a text several
# symbols share resolves to the last of them.
# sym_text resolves a symbol, english_text answers "is this key a known text?".
declare -A sym_text
declare -A english_text
while IFS=$'\t' read -r dk dv; do
if test -n "$dk" -a -n "$dv"; then
english_text["$dv"]=1
sym_text["$dk"]="$dv"
fi
done < <(awk -F'\t' '$1 == "string" { print $2 "\t" $3 }' "$symtab" | lang_filter)

for i in $1/*.txt ; do
echo -n "processing $i .. " >&2

if test $(head -5 $i | tail -1 | tr -d '\r') != "LANGUAGE_ISO"; then
echo "bad file $i: LANGUAGE_ISO expected"
exit 1
fi
iso=$(head -6 $i | tail -1 | tr -d '\r' | tr '_' '-' | sed -e 's/-/-r/')
#if test "$iso" = "en"; then
#echo "skipped"
#continue;
#fi
dest=$2/values-${iso}
mkdir -p $dest

# read strings, cleaned up by lang_filter above
cat "$i" | lang_filter | (
unset arr sym_owner
declare -A arr
declare -A sym_owner
while IFS= read -r rk; do
IFS= read -r v

symbol=
case "$rk" in
LANG_*|LISTDEF_*)
if test -z "${english_text[$rk]}"; then
if test -z "${sym_text[$rk]}"; then
# No resource is keyed by a bare symbol, so drop the line rather than store it.
echo "warning: $i: no string row for symbol $rk, line skipped" >&2
continue
fi
symbol="$rk"
rk="${sym_text[$rk]}"
fi
;;
esac

k="$(norm_key "$rk")"
if test -n "$k" -a -n "$v"; then
put_str "$k" "$v" "$symbol"
fi

# hack for multiple \n-separated lines
if echo  "$rk" | grep -q '\\n'; then
n=1
while test -n "$n"; do
sv=$(echo "$v" | sed -e 's/\\n/"/g' | cut -f${n} -d'"' | sed -e 's/\\r//g')
sk=$(echo "$rk" | sed -e 's/\\n/"/g' | cut -f${n} -d'"' | sed -e 's/\\r//g')
sk="$(norm_key "$sk")"
if test -n "$sk" -a -n "$sv"; then
put_str "$sk" "$sv" "$symbol"
n=$[$n+1]
else
n=
fi
done
fi

done

# map xml
( cat $2/values/strings.xml | sed -e "s/\\\\'/'/g" && echo ) | tr -d '\r' | (

# <string name="options">Options</string>
while IFS= read -r l; do
if $(echo "$l" | grep -q "<string"); then
k="$(echo "$l" | cut -f2 -d'>' | cut -f1 -d'<')"
k="$(norm_key "$k")"
v="${arr[$k]}"
# try harder without punct
if ! test -n "$v"; then
k="$(echo "$k" | sed -e 's/[_]*$//')"
if test -n "$k"; then
v="${arr[$k]}"
fi
fi
if test -n "$v"; then
echo -n "$l" | sed -e "s/\(.*\)>.*<\(.*\)/\1>/"
echo -n "$v" | sed -e "s/'/\\\\'/g"
echo -n "$l" | sed -e "s/\(.*\)>.*<\(.*\)/<\2/"
echo
else
echo "    <!-- $(echo "$l" | tr '<>' '[]' | tr -d '\r\n' | sed -e 's/--/- -/g') -->"
fi
else
if $(echo "$l" | grep -q "<resources>"); then
echo "<!-- Generated file ($0), do NOT edit! Edit lang/*.txt files and re-run the script to regenerate XML files. -->"
fi
echo "$l"
fi
done
) > $dest/string.xml

)

echo "done (${iso})" >&2
done


