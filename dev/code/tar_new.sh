#!/bin/bash

# Usage: ./parallel_tar_creator.sh <SOURCE_DIR> <STAGING_DIR>

SOURCE_DIR="$1"
STAGING_DIR="$2"

# Dynamically choose parallelism based on cores
PARALLEL_JOBS=$(nproc)
[[ $PARALLEL_JOBS -gt 54 ]] && PARALLEL_JOBS=54

echo "⏳ Starting tar creation with parallelism ($PARALLEL_JOBS jobs)..."

MANIFEST="${STAGING_DIR}/tar_creation_manifest.txt"
> "$MANIFEST"

echo "Tar Creation Manifest" >> "$MANIFEST"
echo "Created: $(date)" >> "$MANIFEST"
echo "Staging Directory: $(basename "$STAGING_DIR")" >> "$MANIFEST"
echo "" >> "$MANIFEST"
echo "Tar files created:" >> "$MANIFEST"

# Temp dir for logs
TMP_LOG_DIR="/tmp/tar_logs_$$"
mkdir -p "$TMP_LOG_DIR"
trap 'rm -rf "$TMP_LOG_DIR"' EXIT

generate_tar() {
    chunk_file_list="$1"
    staging_dir="$2"
    tmp_log="$3"

    chunk_id=$(basename "$chunk_file_list" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    tar_path="${staging_dir}/${tar_name}"

    {
        echo "📦 Creating tar for chunk_${chunk_id}..."
        echo "Debug: Chunk file list path: $chunk_file_list"
        echo "Debug: Creating tar file at $tar_path"
    } >> "$tmp_log"

    TAR_OUTPUT=$(ionice -c2 -n7 nice -n 10 tar -cf "$tar_path" -T "$chunk_file_list" --warning=no-file-changed 2>&1)
    TAR_STATUS=$?

    echo "$TAR_OUTPUT" >> "$tmp_log"

    if [[ $TAR_STATUS -ne 0 ]]; then
        FAIL_LINE=$(echo "$TAR_OUTPUT" | grep -iE 'cannot open|permission denied|cannot stat' | head -1)
        echo "❌ Failed to create ${tar_name}: ${FAIL_LINE}" >> "$tmp_log"
        rm -f "$tar_path"
        echo "FAIL:${tar_name}:${FAIL_LINE}" > "${tmp_log}.status"
    else
        echo "✅ ${tar_name}" >> "$tmp_log"
        echo "OK:${tar_name}" > "${tmp_log}.status"
    fi
}

export -f generate_tar
export STAGING_DIR

# Run all chunk tar jobs in parallel
find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort | xargs -I{} -P "$PARALLEL_JOBS" bash -c '
    generate_tar "$0" "$STAGING_DIR" "'"$TMP_LOG_DIR"'/$(basename "$0").log"
' {}

# Gather logs and update manifest
COUNT=0
for status_file in "$TMP_LOG_DIR"/*.status; do
    [ -f "$status_file" ] || continue
    status_line=$(cat "$status_file")
    log_file="${status_file%.status}"

    cat "$log_file"
    echo "" >> "$MANIFEST"

    if [[ $status_line == OK:* ]]; then
        tar_name=$(echo "$status_line" | cut -d':' -f2)
        echo "✅ $tar_name" >> "$MANIFEST"
        ((COUNT++))
    else
        echo "❌ ${status_line#FAIL:}" >> "$MANIFEST"
    fi
done

echo "" >> "$MANIFEST"
echo "Total tar files: $COUNT" >> "$MANIFEST"

echo "✅ All parallel tar creation attempts finished."
echo "✅ Manifest generated at $MANIFEST"
