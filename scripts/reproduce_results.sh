#!/usr/bin/env bash
set -eo pipefail
cd "$(dirname "$0")/../results"

rm -rf reproduced
mkdir -p reproduced
python3 analysis/tables.py --out reproduced/tables
python3 analysis/paper_numbers.py --out reproduced/tables
kinds="tables"
if python3 -c "import matplotlib, numpy" 2>/dev/null; then
  python3 analysis/figures.py --out reproduced/figures
  kinds="tables figures"
else
  echo "matplotlib or numpy is not installed: figures skipped (pip install -r results/requirements.txt)"
fi

different=0
for kind in $kinds; do
  for shipped in "$kind"/*; do
    name=$(basename "$shipped")
    if cmp -s "$shipped" "reproduced/$kind/$name"; then
      echo "identical  $kind/$name"
    else
      echo "different  $kind/$name"
      different=$((different + 1))
    fi
  done
done
echo "$different file(s) differ from the shipped ones"
