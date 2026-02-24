#!/bin/bash

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <staging_folder> <target_directory>"
  exit 1
fi

staging_folder="$1"
target_directory="$2"
parallel_jobs=50  # Number of parallel jobs for tar creation

# Ensure the target directory exists
mkdir -p "$target_directory"

# Temporary file to store successful tar names
success_list_file="${target_directory}/successful_tars.txt"
> "$success_list_file"

echo "⏳ Starting parallel tar creation..."

find "$staging_folder" -name "chunk_*_files.txt" | \
xargs -P $parallel_jobs -I{} bash -c '
  chunk_filelist="$1"
  target_directory="$2"
  success_list_file="$3"

  chunk_id=$(basename "$chunk_filelist" | grep -oP "\d+")

  echo "📦 Creating tar for chunk_$chunk_id..."
  echo "Debug: Chunk file list path: $chunk_filelist"

  if [ ! -s "$chunk_filelist" ]; then
    echo "⚠️ Chunk file list $chunk_filelist is empty, skipping."
    exit 0
  fi

  tar_file="$target_directory/archive_part_${chunk_id}.tar"
  echo "Debug: Creating tar file at $tar_file"

  if tar -cf "$tar_file" -T "$chunk_filelist"; then
    echo "✅ Successfully created archive_part_${chunk_id}.tar"
    echo "archive_part_${chunk_id}.tar" >> "$success_list_file"
  else
    echo "❌ Failed to create archive_part_${chunk_id}.tar"
    # Do not add failed tar to success list
  fi
' _ {} "$target_directory" "$success_list_file"

echo "✅ All tar creation attempts finished."

# Now create the manifest only from successful tars
manifest_file="${target_directory}/tar_creation_manifest.txt"

{
  echo "Tar Creation Manifest"
  echo "Created: $(date)"
  echo "Staging Directory: $(basename "$staging_folder")"
  echo ""
  echo "Tar files created:"
  sort "$success_list_file"
  echo ""
  echo "Total tar files: $(wc -l < "$success_list_file")"
} > "$manifest_file"

echo "✅ Manifest generated at $manifest_file"
