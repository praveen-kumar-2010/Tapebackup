#!/bin/bash

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <root_folder> <staging_folder>"
  exit 1
fi

root_folder="$1"
staging_folder="$2"

# Set chunk size to 1 TB with a strict margin (1TB - 10MB buffer)
chunk_size_bytes=$((1024 * 1024 * 1024 * 1024 - 10 * 1024 * 1024))  # 1 TB - 10MB buffer

mkdir -p "$staging_folder"
tmp_filelist=$(mktemp)

echo "📋 Building file list from $root_folder (excluding ImageQCrejected and processedImage folders)..."
find "$root_folder" -type f ! -path "*/ImageQCrejected/*" ! -path "*/processedImage/*" -printf "%s\t%p\n" | sort -nr > "$tmp_filelist"

declare -A added_files

chunk=1
current_size=0
chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
> "$chunk_filelist"

while IFS=$'\t' read -r size path; do
  if [[ -v added_files["$path"] ]]; then
    echo "⚠️ File $path is a duplicate and will be skipped."
    continue
  fi
  added_files["$path"]=1

  # If the file itself exceeds 1TB, create a new chunk for this file
  if (( size > chunk_size_bytes )); then
    if (( current_size > 0 )); then
      echo "Chunk $chunk size: $current_size bytes"
      chunk=$((chunk + 1))
    fi

    echo "⚠️ File $path size ($size bytes) exceeds chunk size limit (1 TB). It will be alone in a chunk."
    chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
    > "$chunk_filelist"
    echo "$path" >> "$chunk_filelist"
    echo "Chunk $chunk size: $size bytes (single large file)"
    chunk=$((chunk + 1))
    current_size=0
    chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
    > "$chunk_filelist"
    continue
  fi

  # Check if adding this file would exceed the chunk size before adding it
  if (( current_size + size > chunk_size_bytes )); then
    echo "Chunk $chunk size: $current_size bytes (reached limit, creating new chunk)"
    chunk=$((chunk + 1))
    current_size=0
    chunk_filelist="$staging_folder/chunk_${chunk}_files.txt"
    > "$chunk_filelist"
  fi

  # Add the file to the current chunk
  echo "$path" >> "$chunk_filelist"
  current_size=$((current_size + size))
done < "$tmp_filelist"

# Final chunk size printout
if (( current_size > 0 )); then
  echo "Chunk $chunk size: $current_size bytes"
fi

rm -f "$tmp_filelist"

echo "✅ Chunk files created in $staging_folder"

# --- Merge all chunk file paths into a single file ---
merged_filelist="${staging_folder}/all_chunks_merged.txt"
echo "Merging all chunk file lists into ${merged_filelist}..."
cat ${staging_folder}/chunk_*.txt > "${merged_filelist}"
echo "✅ Merged all chunk files into ${merged_filelist}"

# --- Generate ls-based metadata for modification detection ---
ls_metadata_file="${staging_folder}/all_chunks_ls_metadata.txt"
echo "📅 Saving modification timestamps to ${ls_metadata_file} (excluding ImageQCrejected and processedImage folders)..."
find "$root_folder" -type f ! -path "*/ImageQCrejected/*" ! -path "*/processedImage/*" -exec stat --format='%Y %n' {} \; | sort -k 2 > "$ls_metadata_file"
echo "✅ Modification metadata saved."
# === Push to Bitbucket repo ===

# Git repo location
repo_dir="/mnt/remote/tapebackup/git_repo"

# Mark repo as safe (for Jenkins/git >= 2.35)
git config --global --add safe.directory "$repo_dir"

# Clone once if not exists
if [ ! -d "$repo_dir/.git" ]; then
  git clone git@bitbucket.org:hbp_iitm/tape_staging.git "$repo_dir"
fi

# Copy the two files into a subdir inside the repo
subdir_name="$(basename "$staging_folder")"
mkdir -p "$repo_dir/$subdir_name"
cp "$staging_folder"/all_chunks_*.txt "$repo_dir/$subdir_name/"

# Commit and push (only if there are changes)
cd "$repo_dir"
git add "$subdir_name/all_chunks_*.txt"

if git diff --cached --quiet; then
  echo "ℹ️ No changes to commit for $subdir_name"
else
  git commit -m "Add chunk metadata for $subdir_name"
  git push origin main
  echo "✅ Git push completed for $subdir_name"
fi

