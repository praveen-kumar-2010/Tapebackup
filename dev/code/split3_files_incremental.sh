#!/bin/bash
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "Usage: $0 <root_folder> <staging_folder> <prev_metadata_file> <output_metadata_file>"
  exit 1
fi

root_folder="$1"
staging_folder="$2"
prev_metadata_file="$3"
output_metadata_file="$4"

chunk_size_bytes=$((1024 * 1024 * 1024 * 1024 - 10 * 1024 * 1024))  # 1 TB - 10MB buffer
mkdir -p "$staging_folder"

# Generate new metadata
tmp_current=$(mktemp)
echo "📅 Generating current file metadata..."
find "$root_folder" -type f -exec stat --format='%Y %n' {} \; | sort -k 2 > "$tmp_current"

# Load previous metadata
tmp_old=$(mktemp)
if [ -f "$prev_metadata_file" ]; then
    echo "📄 Reading previous metadata: $prev_metadata_file"
    sort -k 2 "$prev_metadata_file" > "$tmp_old"
else
    echo "⚠️ No previous metadata found. All files considered new."
    > "$tmp_old"
fi

# Compare to find new/modified files
changed_filelist=$(mktemp)
awk '
BEGIN { FS=OFS=" " }
NR==FNR { old[$2]=$1; next }
{
    newtime = $1; path = $2;
    if (!(path in old) || newtime > old[path]) {
        print path
    }
}' "$tmp_old" "$tmp_current" > "$changed_filelist"

total_changes=$(wc -l < "$changed_filelist")
echo "📦 Files to process: $total_changes"

if [ "$total_changes" -eq 0 ]; then
    echo "✅ No changes detected."
    cp "$tmp_current" "$output_metadata_file"
    rm -f "$tmp_current" "$tmp_old" "$changed_filelist"
    exit 0
fi

# Chunk the changed files
declare -A added_files
chunk=1
current_size=0
chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
> "$chunk_filelist"

while IFS= read -r path; do
    [ ! -f "$path" ] && continue
    size=$(stat -c%s "$path")
    [[ -v added_files["$path"] ]] && continue
    added_files["$path"]=1

    if (( size > chunk_size_bytes )); then
        (( current_size > 0 )) && echo "Chunk $chunk size: $current_size bytes" && chunk=$((chunk + 1))
        chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
        > "$chunk_filelist"
        echo "$path" >> "$chunk_filelist"
        echo "Chunk $chunk size: $size bytes (large file)"
        chunk=$((chunk + 1))
        current_size=0
        chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
        > "$chunk_filelist"
        continue
    fi

    if (( current_size + size > chunk_size_bytes )); then
        echo "Chunk $chunk size: $current_size bytes"
        chunk=$((chunk + 1))
        current_size=0
        chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
        > "$chunk_filelist"
    fi

    echo "$path" >> "$chunk_filelist"
    current_size=$((current_size + size))
done < "$changed_filelist"

(( current_size > 0 )) && echo "Chunk $chunk size: $current_size bytes"

cat ${staging_folder}/chunk_*.txt > "${staging_folder}/incremental_all_chunks_merged.txt"
cp "$tmp_current" "$output_metadata_file"

rm -f "$tmp_current" "$tmp_old" "$changed_filelist"

echo "✅ Incremental chunking complete using mtime metadata."
