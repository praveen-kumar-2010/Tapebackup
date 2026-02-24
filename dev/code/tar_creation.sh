#!/bin/bash

SOURCE_DIR="$1"
STAGING_DIR="$2"

echo "⏳ Starting tar creation..."

MANIFEST="${STAGING_DIR}/tar_creation_manifest.txt"
> "$MANIFEST"

echo "Tar Creation Manifest" >> "$MANIFEST"
echo "Created: $(date)" >> "$MANIFEST"
echo "Staging Directory: $(basename "$STAGING_DIR")" >> "$MANIFEST"
echo "" >> "$MANIFEST"
echo "Tar files created:" >> "$MANIFEST"

COUNT=0

for chunk_file_list in "${STAGING_DIR}"/chunk_*_files.txt; do
    chunk_id=$(basename "$chunk_file_list" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    tar_path="${STAGING_DIR}/${tar_name}"

    echo "📦 Creating tar for chunk_${chunk_id}..."
    echo "Debug: Chunk file list path: $chunk_file_list"
    echo "Debug: Creating tar file at $tar_path"

    TAR_OUTPUT=$(tar -cvf "$tar_path" -T "$chunk_file_list" --warning=no-file-changed 2>&1)
    TAR_STATUS=$?

    echo "$TAR_OUTPUT"

    if [[ $TAR_STATUS -ne 0 ]]; then
        # Grab first meaningful error (preferably permission)
        FAIL_LINE=$(echo "$TAR_OUTPUT" | grep -iE 'cannot open|permission denied|cannot stat' | head -1)
        echo "❌ Failed to create ${tar_name}: ${FAIL_LINE}" | tee -a "$MANIFEST"
        rm -f "$tar_path"
    else
        echo "✅ ${tar_name}" | tee -a "$MANIFEST"
        ((COUNT++))
    fi
done

echo "" >> "$MANIFEST"
echo "Total tar files: $COUNT" >> "$MANIFEST"

echo "✅ All tar creation attempts finished."
echo "✅ Manifest generated at $MANIFEST"
exit 0
