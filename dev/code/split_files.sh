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
parallel_jobs=10  # Number of parallel jobs for copying files per chunk

mkdir -p "$staging_folder"
tmp_filelist="$(mktemp)"
echo "📋 Building file list from $root_folder..."
find "$root_folder" -type f -printf "%s\t%p\n" | sort -nr > "$tmp_filelist"

chunk=1
current_size=0
chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
> "$chunk_filelist"

# Step 1: Split files into chunk_N_files.txt (~1TB each)
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

echo "✅ Split completed. Chunks created at: $staging_folder"

# Step 2: Parallelize chunk processing (copying files)
echo "⏳ Starting parallel chunk processing..."

export root_folder top_folder_name staging_folder  # Export variables for parallel jobs

# Use find to locate all chunk files and process them in parallel
find "$staging_folder" -name "chunk_*_files.txt" | xargs -P $parallel_jobs -I{} bash -c '
  chunk_filelist="{}"
  chunk_id=$(basename "$chunk_filelist" | grep -oP "\d+")
  chunk_dir="$staging_folder/chunk_$chunk_id"
  mkdir -p "$chunk_dir"  # Ensure the chunk directory exists

  echo "➡️  Copying files for chunk_$chunk_id..."

  # Start timing the chunk creation
  start_time=$(date +%s)

  # Copy files for each chunk
  cat "$chunk_filelist" | while read file; do
    rel_path="${file#$root_folder/}"
    dest_dir="$staging_folder/chunk_$chunk_id/$top_folder_name/$(dirname "$rel_path")"
    mkdir -p "$dest_dir"  # Ensure the parent directories exist inside staging folder
    echo "📂 Copying: $file"
    cp "$file" "$dest_dir/"
  done

  # End timing for chunk creation
  end_time=$(date +%s)
  elapsed_time=$((end_time - start_time))

  echo "📦 Chunk_$chunk_id creation completed in $elapsed_time seconds."
'

echo "✅ All chunks processed."
