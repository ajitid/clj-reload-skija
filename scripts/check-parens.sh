#!/bin/bash
# Quick parenthesis balance checker
# Usage: ./scripts/check-parens.sh <file>

if [ -z "$1" ]; then
    echo "Usage: $0 <file>"
    exit 1
fi

FILE="$1"

if [ ! -f "$FILE" ]; then
    echo "File not found: $FILE"
    exit 1
fi

# Count opening and closing parens
OPENS=$(grep -o '(' "$FILE" | wc -l | tr -d ' ')
CLOSES=$(grep -o ')' "$FILE" | wc -l | tr -d ' ')
DIFF=$((OPENS - CLOSES))

echo "File: $FILE"
echo "Opens:  $OPENS"
echo "Closes: $CLOSES"
echo "Diff:   $DIFF"

if [ $DIFF -eq 0 ]; then
    echo "✅ Balanced"
    # Now do deeper syntax check with babashka
    bb scripts/validate-syntax.clj "$FILE"
    exit $?
else
    echo "❌ IMBALANCED by $DIFF"
    if [ $DIFF -gt 0 ]; then
        echo "   Missing $DIFF closing paren(s)"
    else
        echo "   Extra $((0 - DIFF)) closing paren(s)"
    fi
    exit 1
fi
