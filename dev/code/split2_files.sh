#!/bin/bash

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <root_folder> <staging_folder>"
  exit 1
fi

root_folder="$1"
staging_folder="$2"
chunk_size_bytes=$((1024**4))  # 1 TB

mkdir -p "$staging_folder"
tmp_filelist=$(mktemp)

echo "📋 Building file list from $root_folder..."
find "$root_folder" -type f -printf "%s\t%p\n" | sort -nr > "$tmp_filelist"

chunk=1
current_size=0
chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
> "$chunk_filelist"

while IFS=$'\t' read -r size path; do
  if (( current_size + size > chunk_size_bytes )); then
    chunk=$((chunk + 1))
    current_size=0
    chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
    > "$chunk_filelist"
  fi
  echo "$path" >> "$chunk_filelist"
  current_size=$((current_size + size))
done < "$tmp_filelist"

rm -f "$tmp_filelist"

echo "✅ Chunk files created in $staging_folder"
