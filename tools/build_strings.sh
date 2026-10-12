#!/bin/bash
#

# Convert httrack lang/*;txt files into android xml resources

# usage: $0 path-to/lang path-to/res [path-to/lang.def]
# lang.def defaults to the one next to the lang directory
# example:
#	rm -rf res/values-?? res/values-??-r??	# language dirs only: values-* also takes the
#					# hand-kept values-v34, values-night and the rest
#	bash tools/build_strings.sh /temp/httrack-3.47.21/lang res/

function norm_key {
	echo "$1" | sed -e 's/[^a-zA-Z0-9]/_/g'
}

# Clean up catalog text: strip CR, drop a trailing colon, replace a real & by
# &amp; and strip the others, and say HTTrack rather than WinHTTrack. lang.def
# goes through it too, so a symbol resolves to the same key as its English text.
function lang_filter {
	tr -d '\r' \
		| sed -e 's/\\r//g' \
		| sed -e 's/:$//' -e 's/\* //g' -e 's/\.\.\.$//g' -e 's/\([[:space:]]\)[[:space:]]*/\1/g' -e 's/[[:space:]]*$//' \
		| sed -e 's/ & / @@amp@@ /g' -e 's/&//g' -e 's/@@amp@@/\&amp;/g' \
		| sed -e 's/WinHTTrack/HTTrack/g' \
		| sed -e "s/1998-2003/1998-$(date +%Y)/g" \
		| sed -e "s/(C)/\&#169;/g"
}

# Store one translation. A symbol-keyed line beats a text-keyed one for the same
# string, and the first symbol line wins, so a catalog holding both kinds gives
# the same result whatever order its lines are in.
function put_str {
	if test -n "$from_symbol"; then
		test -n "${symdone[$1]}" && return 0
		symdone["$1"]=1
	elif test -n "${symdone[$1]}"; then
		return 0
	fi
	arr["$1"]="$2"
}

test -d "$1" || ! echo "lang directory not found: $1" || exit 1
test -d "$2" || ! echo "res directory not found: $2" || exit 1
langdef="${3:-$1/../lang.def}"
test -f "$langdef" || ! echo "lang.def not found: $langdef (give its path as the 3rd argument)" || exit 1

# lang.def pairs each string's LANG_ symbol with its English text, symbol first.
# A catalog line can be keyed either way, so read both directions.
declare -A symtext
declare -A langtext
while IFS= read -r dk && IFS= read -r dv; do
if test -n "$dk" -a -n "$dv"; then
langtext["$dv"]=1
case "$dk" in
LANG_*|LISTDEF_*) symtext["$dk"]="$dv" ;;
esac
fi
done < <(lang_filter < "$langdef")

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
unset arr symdone
declare -A arr
declare -A symdone
while IFS= read -r rk; do
IFS= read -r v

# A catalog can key a line by its lang.def symbol instead of its English text,
# which is what lets a string be reworded without orphaning its translations.
from_symbol=
case "$rk" in
LANG_*|LISTDEF_*)
if test -z "${langtext[$rk]}"; then
if test -z "${symtext[$rk]}"; then
echo "warning: $i: no lang.def entry for key $rk, line skipped" >&2
continue
fi
rk="${symtext[$rk]}"
from_symbol=1
fi
;;
esac

k="$(norm_key "$rk")"
if test -n "$k" -a -n "$v"; then
put_str "$k" "$v"
fi

# hack for multiple \n-separated lines
if echo  "$rk" | grep -q '\\n'; then
n=1
while test -n "$n"; do
sv=$(echo "$v" | sed -e 's/\\n/"/g' | cut -f${n} -d'"' | sed -e 's/\\r//g')
sk=$(echo "$rk" | sed -e 's/\\n/"/g' | cut -f${n} -d'"' | sed -e 's/\\r//g')
sk="$(norm_key "$sk")"
if test -n "$sk" -a -n "$sv"; then
put_str "$sk" "$sv"
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


