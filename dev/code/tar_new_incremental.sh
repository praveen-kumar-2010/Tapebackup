#!/bin/bash

SOURCE_DIR="$1"
STAGING_DIR="$2"

PARALLEL_JOBS=$(nproc)
[[ $PARALLEL_JOBS -gt 64 ]] && PARALLEL_JOBS=64

MANIFEST="${STAGING_DIR}/tar_creation_manifest.txt"
> "$MANIFEST"

echo "Starting incremental tar creation with $PARALLEL_JOBS parallel jobs..."

TMP_LOG_DIR="/tmp/tar_logs_$$"
mkdir -p "$TMP_LOG_DIR"
trap 'rm -rf "$TMP_LOG_DIR"' EXIT

generate_tar() {
    chunk_file_list="$1"
    staging_dir="$2"
    tmp_log="$3"

    chunk_id=$(basename "$chunk_file_list" | grep -oP 'chunk_\K[0-9]+')
    tar_name="incremental_archive_part_${chunk_id}.tar"
    tar_path="${staging_dir}/${tar_name}"

    {
        echo "Creating incremental tar for chunk_${chunk_id}..."
        echo "Chunk file list: $chunk_file_list"
        echo "Output tar: $tar_path"
    } >> "$tmp_log"

    TAR_OUTPUT=$(ionice -c2 -n7 nice -n 10 tar -cf "$tar_path" -T "$chunk_file_list" --warning=no-file-changed 2>&1)
    TAR_STATUS=$?

    echo "$TAR_OUTPUT" >> "$tmp_log"

    if [[ $TAR_STATUS -ne 0 ]]; then
        FAIL_LINE=$(echo "$TAR_OUTPUT" | grep -iE 'cannot open|permission denied|cannot stat' | head -1)
        echo "Failed to create ${tar_name}: ${FAIL_LINE}" >> "$tmp_log"
        rm -f "$tar_path"
        echo "FAIL:${tar_name}:${FAIL_LINE}" > "${tmp_log}.status"
    else
        echo "Successfully created ${tar_name}" >> "$tmp_log"
        echo "OK:${tar_name}" > "${tmp_log}.status"
    fi
}

export -f generate_tar
export STAGING_DIR

find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort | xargs -I{} -P "$PARALLEL_JOBS" bash -c '
    generate_tar "$0" "$STAGING_DIR" "'"$TMP_LOG_DIR"'/$(basename "$0").log"
' {}

COUNT=0
for status_file in "$TMP_LOG_DIR"/*.status; do
    [ -f "$status_file" ] || continue
    status_line=$(cat "$status_file")
    log_file="${status_file%.status}.log"

    cat "$log_file"
    echo "" >> "$MANIFEST"

    if [[ $status_line == OK:* ]]; then
        tar_name=$(echo "$status_line" | cut -d':' -f2)
        echo "OK: $tar_name" >> "$MANIFEST"
        ((COUNT++))
    else
        echo "FAIL: ${status_line#FAIL:}" >> "$MANIFEST"
    fi
done

echo "" >> "$MANIFEST"
echo "Total incremental tar files created: $COUNT" >> "$MANIFEST"

echo "Incremental tar creation finished."
