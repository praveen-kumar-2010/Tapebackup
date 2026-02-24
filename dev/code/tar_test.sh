#!/bin/bash

# Usage: ./parallel_tar_creator.sh <SOURCE_DIR> <STAGING_DIR> [--force]

SOURCE_DIR="$1"
STAGING_DIR="$2"
FORCE_RECREATE="$3"

# Use half of the available cores to avoid overwhelming the system, with a minimum of 1.
# This leaves resources for the OS and other essential processes.
PARALLEL_JOBS=$(( $(nproc) / 2 ))
[[ $PARALLEL_JOBS -eq 0 ]] && PARALLEL_JOBS=1

echo "⏳ Starting tar creation with parallelism ($PARALLEL_JOBS jobs)..."

MANIFEST="${STAGING_DIR}/tar_creation_manifest.txt"
PROGRESS_DIR="${STAGING_DIR}/.tar_progress"
JSON_FILE="${STAGING_DIR}/chunk_assignments.json"
BIOSAMPLE_ID=$(basename "$STAGING_DIR")
mkdir -p "$PROGRESS_DIR"

# Initialize manifest if it doesn't exist
if [[ ! -f "$MANIFEST" ]] || [[ "$FORCE_RECREATE" == "--force" ]]; then
    > "$MANIFEST"
    echo "Tar Creation Manifest" >> "$MANIFEST"
    echo "Created: $(date)" >> "$MANIFEST"
    echo "Staging Directory: $(basename "$STAGING_DIR")" >> "$MANIFEST"
    echo "" >> "$MANIFEST"
    echo "Tar files created:" >> "$MANIFEST"
    
    # Clear progress tracking if force recreate
    if [[ "$FORCE_RECREATE" == "--force" ]]; then
        rm -f "$PROGRESS_DIR"/*.done "$PROGRESS_DIR"/*.failed
        echo "🔄 Force recreate mode - clearing all existing tar files and progress..."
        rm -f "${STAGING_DIR}"/archive_part_*.tar
    fi
fi

# Temp dir for logs
TMP_LOG_DIR="/tmp/tar_logs_$$"
mkdir -p "$TMP_LOG_DIR"
trap 'rm -rf "$TMP_LOG_DIR"' EXIT

check_existing_tar() {
    local chunk_id="$1"
    local tar_name="archive_part_${chunk_id}.tar"
    local tar_path="${STAGING_DIR}/${tar_name}"
    local progress_file="${PROGRESS_DIR}/${chunk_id}.done"
    local failed_file="${PROGRESS_DIR}/${chunk_id}.failed"
    
    # Skip if already completed successfully
    if [[ -f "$progress_file" ]] && [[ -f "$tar_path" ]]; then
        return 0  # Already done
    fi
    
    # Remove failed marker if exists (for retry)
    [[ -f "$failed_file" ]] && rm -f "$failed_file"
    
    return 1  # Needs to be processed
}

generate_tar() {
    chunk_file_list="$1"
    staging_dir="$2"
    tmp_log="$3"
    progress_dir="$4"

    chunk_id=$(basename "$chunk_file_list" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    tar_path="${staging_dir}/${tar_name}"
    progress_file="${progress_dir}/${chunk_id}.done"
    failed_file="${progress_dir}/${chunk_id}.failed"

    # Check if already completed
    if [[ -f "$progress_file" ]] && [[ -f "$tar_path" ]]; then
        {
            echo "⏭️  Skipping chunk_${chunk_id} - already completed"
            echo "✅ ${tar_name} (existing)"
        } >> "$tmp_log"
        echo "SKIP:${tar_name}" > "${tmp_log}.status"
        return 0
    fi

    {
        echo "📦 Creating tar for chunk_${chunk_id}..."
        echo "Debug: Chunk file list path: $chunk_file_list"
        echo "Debug: Creating tar file at $tar_path"
    } >> "$tmp_log"

    # Remove existing incomplete tar file
    [[ -f "$tar_path" ]] && rm -f "$tar_path"

    TAR_OUTPUT=$(ionice -c2 -n7 nice -n 10 tar -cf "$tar_path" -T "$chunk_file_list" --warning=no-file-changed 2>&1)
    TAR_STATUS=$?

    echo "$TAR_OUTPUT" >> "$tmp_log"

    if [[ $TAR_STATUS -ne 0 ]]; then
        FAIL_LINE=$(echo "$TAR_OUTPUT" | grep -iE 'cannot open|permission denied|cannot stat' | head -1)
        echo "❌ Failed to create ${tar_name}: ${FAIL_LINE}" >> "$tmp_log"
        rm -f "$tar_path"
        touch "$failed_file"
        echo "FAIL:${tar_name}:${FAIL_LINE}" > "${tmp_log}.status"
    else
        # Verify tar file was created and has reasonable size
        if [[ -f "$tar_path" ]] && [[ $(stat -f%z "$tar_path" 2>/dev/null || stat -c%s "$tar_path" 2>/dev/null || echo 0) -gt 0 ]]; then
            touch "$progress_file"
            echo "✅ ${tar_name}" >> "$tmp_log"
            echo "OK:${tar_name}" > "${tmp_log}.status"
        else
            echo "❌ Tar file verification failed for ${tar_name}" >> "$tmp_log"
            rm -f "$tar_path"
            touch "$failed_file"
            echo "FAIL:${tar_name}:Verification failed" > "${tmp_log}.status"
        fi
    fi
}

export -f generate_tar check_existing_tar
export STAGING_DIR PROGRESS_DIR

# Count total chunks and existing completed ones
TOTAL_CHUNKS=$(find "$STAGING_DIR" -name 'chunk_*_files.txt' | wc -l)
COMPLETED_CHUNKS=$(find "$PROGRESS_DIR" -name '*.done' 2>/dev/null | wc -l)
REMAINING_CHUNKS=$((TOTAL_CHUNKS - COMPLETED_CHUNKS))

echo "📊 Progress: $COMPLETED_CHUNKS/$TOTAL_CHUNKS chunks completed, $REMAINING_CHUNKS remaining"

# Create initial JSON with "Running" status
echo "[" > "$JSON_FILE"
first_entry=true
for chunk_file in $(find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort -V); do
    chunk_id=$(basename "$chunk_file" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    if [[ "$first_entry" == true ]]; then
        first_entry=false
    else
        echo "," >> "$JSON_FILE"
    fi
    echo "  {\"chunk\":\"${tar_name}\",\"cartridge\":\"\",\"biosample_id\":\"${BIOSAMPLE_ID}\",\"status\":\"Running\"}" >> "$JSON_FILE"
done
echo "]" >> "$JSON_FILE"
echo "📄 Initial JSON created at $JSON_FILE"

if [[ $REMAINING_CHUNKS -eq 0 ]]; then
    echo "🎉 All chunks already completed! Use --force to recreate all."
    exit 0
fi

# Run only incomplete chunk tar jobs in parallel
find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort | while read chunk_file; do
    chunk_id=$(basename "$chunk_file" | grep -oP 'chunk_\K[0-9]+')
    if ! check_existing_tar "$chunk_id"; then
        echo "$chunk_file"
    fi
done | xargs -I{} -P "$PARALLEL_JOBS" bash -c '
    generate_tar "$0" "$STAGING_DIR" "'"$TMP_LOG_DIR"'/$(basename "$0").log" "$PROGRESS_DIR"
' {}

# Gather logs and update manifest
echo "" >> "$MANIFEST"
echo "--- Session: $(date) ---" >> "$MANIFEST"

NEW_COUNT=0
SKIP_COUNT=0
FAIL_COUNT=0

for status_file in "$TMP_LOG_DIR"/*.status; do
    [ -f "$status_file" ] || continue
    status_line=$(cat "$status_file")
    log_file="${status_file%.status}"

    cat "$log_file"

    if [[ $status_line == OK:* ]]; then
        tar_name=$(echo "$status_line" | cut -d':' -f2)
        echo "✅ $tar_name (new)" >> "$MANIFEST"
        ((NEW_COUNT++))
    elif [[ $status_line == SKIP:* ]]; then
        tar_name=$(echo "$status_line" | cut -d':' -f2)
        echo "⏭️  $tar_name (skipped - existing)" >> "$MANIFEST"
        ((SKIP_COUNT++))
    else
        echo "❌ ${status_line#FAIL:}" >> "$MANIFEST"
        ((FAIL_COUNT++))
    fi
done

# Final summary
FINAL_COMPLETED=$(find "$PROGRESS_DIR" -name '*.done' 2>/dev/null | wc -l)
FINAL_FAILED=$(find "$PROGRESS_DIR" -name '*.failed' 2>/dev/null | wc -l)

echo "" >> "$MANIFEST"
echo "Session Summary:" >> "$MANIFEST"
echo "- New tar files created: $NEW_COUNT" >> "$MANIFEST"
echo "- Existing files skipped: $SKIP_COUNT" >> "$MANIFEST"
echo "- Failed: $FAIL_COUNT" >> "$MANIFEST"
echo "Overall Status: $FINAL_COMPLETED/$TOTAL_CHUNKS completed, $FINAL_FAILED failed" >> "$MANIFEST"

echo ""
echo "✅ Parallel tar creation session finished."
echo "📊 Final Status: $FINAL_COMPLETED/$TOTAL_CHUNKS completed, $FINAL_FAILED failed"
echo "📊 This session: $NEW_COUNT created, $SKIP_COUNT skipped, $FAIL_COUNT failed"
echo "✅ Manifest updated at $MANIFEST"

# Update JSON with final status
echo "[" > "$JSON_FILE"
first_entry=true
for chunk_file in $(find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort -V); do
    chunk_id=$(basename "$chunk_file" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    progress_file="${PROGRESS_DIR}/${chunk_id}.done"
    failed_file="${PROGRESS_DIR}/${chunk_id}.failed"

    if [[ -f "$progress_file" ]]; then
        status="Completed"
    elif [[ -f "$failed_file" ]]; then
        status="Failed"
    else
        status="Running"
    fi

    if [[ "$first_entry" == true ]]; then
        first_entry=false
    else
        echo "," >> "$JSON_FILE"
    fi
    echo "  {\"chunk\":\"${tar_name}\",\"cartridge\":\"\",\"biosample_id\":\"${BIOSAMPLE_ID}\",\"status\":\"${status}\"}" >> "$JSON_FILE"
done
echo "]" >> "$JSON_FILE"
echo "📄 JSON updated at $JSON_FILE"

# Create tar_status.csv with summary
TAR_STATUS_FILE="${STAGING_DIR}/tar_status.csv"
TAR_COUNT=$(find "$STAGING_DIR" -name 'archive_part_*.tar' -type f 2>/dev/null | wc -l)

if [[ $FINAL_FAILED -eq 0 ]] && [[ $FINAL_COMPLETED -eq $TOTAL_CHUNKS ]]; then
    TAR_STATUS="Completed"
else
    TAR_STATUS="Incomplete"
fi

echo "biosample=${BIOSAMPLE_ID}" > "$TAR_STATUS_FILE"
echo "status=${TAR_STATUS}" >> "$TAR_STATUS_FILE"
echo "tar_count=${TAR_COUNT}" >> "$TAR_STATUS_FILE"
echo "tar_completed_date=$(date '+%Y-%m-%d %H:%M:%S')" >> "$TAR_STATUS_FILE"
echo "📄 Tar status CSV created at $TAR_STATUS_FILE"

# Show retry command if there were failures
if [[ $FINAL_FAILED -gt 0 ]]; then
    echo ""
    echo "⚠️  Some chunks failed. Run the script again to retry failed chunks."
    echo "🔄 To start completely fresh: $0 \"$SOURCE_DIR\" \"$STAGING_DIR\" --force"
    exit 1
fi