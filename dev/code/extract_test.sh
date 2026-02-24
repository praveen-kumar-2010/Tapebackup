#!/bin/bash

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <tar_files_directory> <restoration_path>"
  exit 1
fi

tar_files_dir="$1"
restoration_path="$2"

mkdir -p "$restoration_path"

echo "⏳ Starting parallel extraction of tar archives from $tar_files_dir to $restoration_path..."

find "$tar_files_dir" -name "archive_part_*.tar" | \
  xargs -P "$(nproc)" -I{} bash -c '
    tarfile="{}"
    echo "📦 Extracting $tarfile..."
    if tar -xf "$tarfile" -C "'"$restoration_path"'"; then
      echo "✅ $tarfile extracted successfully."
    else
      echo "❌ Extraction failed for $tarfile."
    fi
  '

echo "✅ All archives extracted to $restoration_path."
