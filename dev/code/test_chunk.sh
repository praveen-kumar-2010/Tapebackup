#!/bin/bash

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <root_folder> <staging_folder>"
  exit 1
fi

root_folder="$1"
staging_folder="$2"
top_folder_name="$(basename "$root_folder")"
chunk_size_bytes=$((1024 * 1024 * 1024 * 1024))  # 1 TB
mkdir -p "$staging_folder"  # Ensure staging folder exists

echo "📋 Building file list from $root_folder..."
tmp_filelist="$(mktemp)"
find "$root_folder" -type f -printf "%s\t%p\n" | sort -nr > "$tmp_filelist"

chunk=1
current_size=0
chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
> "$chunk_filelist"

# Step 1: Split files into chunk_N_files.txt (~1TB each) and store absolute paths
echo "⏳ Splitting files into chunks of 1 TB..."

while IFS=$'\t' read -r size path; do
  if (( current_size + size > chunk_size_bytes )); then
    # Move to the next chunk
    chunk=$((chunk + 1))
    current_size=0
    chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
    > "$chunk_filelist"
  fi
  # Add the file path to the current chunk file list
  echo "$path" >> "$chunk_filelist"
  current_size=$((current_size + size))
done < "$tmp_filelist"

rm -f "$tmp_filelist"  # Clean up temporary file list

echo "✅ Split completed. Chunks created at: $staging_folder"
