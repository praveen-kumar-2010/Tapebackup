#!/bin/bash
set -euo pipefail

# Usage: ./parallel_tar_creator.sh <SOURCE_DIR> <STAGING_DIR>

SOURCE_DIR="$1"
STAGING_DIR="$2"

# Configuration
FIXED_PARALLELISM=false   # Set to true to use fixed max parallelism (64)
FREE_CORES_MIN=36         # Number of cores to keep free for system
MAX_ALLOWED=64            # Maximum parallel jobs to run

MANIFEST="${STAGING_DIR}/tar_creation_manifest.txt"
STATUS_FILE="${STAGING_DIR}/tar_status.log"
STATUS_LOCK="${STAGING_DIR}/tar_status.lock"

# Create or clear manifest and status files
{
    echo "Tar Creation Manifest"
    echo "Created: $(date)"
    echo "Staging Directory: $(basename "$STAGING_DIR")"
    echo ""
    echo "Tar files created:"
} > "$MANIFEST"

touch "$STATUS_FILE"

TMP_LOG_DIR="/tmp/tar_logs_$$"
mkdir -p "$TMP_LOG_DIR"
trap 'rm -rf "$TMP_LOG_DIR"' EXIT

# Function to get free cores based on load average
get_free_cores() {
    local total_cores load_avg free_cores
    total_cores=$(nproc)
    load_avg=$(cut -d ' ' -f1 /proc/loadavg)
    free_cores=$(echo "$total_cores - $load_avg" | bc)
    free_cores=${free_cores%.*}  # floor
    if (( free_cores < 0 )); then free_cores=0; fi
    if (( free_cores > total_cores )); then free_cores=$total_cores; fi
    echo "$free_cores"
}

# Calculate MAX_JOBS dynamically or fixed
TOTAL_CORES=$(nproc)

if [[ "$FIXED_PARALLELISM" == true ]]; then
    MAX_JOBS=$MAX_ALLOWED
else
    free_cores=$(get_free_cores)
    available_cores=$(( free_cores - FREE_CORES_MIN ))
    if (( available_cores > MAX_ALLOWED )); then
        MAX_JOBS=$MAX_ALLOWED
    elif (( available_cores > 0 )); then
        MAX_JOBS=$available_cores
    else
        echo "⚠️ Warning: Not enough free cores, defaulting to 1 job."
        MAX_JOBS=1
    fi
fi

echo "ℹ️ Total cores: $TOTAL_CORES, reserving $FREE_CORES_MIN for system."
echo "ℹ️ Using $MAX_JOBS parallel jobs (FIXED_PARALLELISM=$FIXED_PARALLELISM)."

generate_tar() {
    local chunk_file_list="$1"
    local staging_dir="$2"
    local tmp_log="$3"

    local chunk_id tar_name tar_path
    chunk_id=$(basename "$chunk_file_list" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"
    tar_path="${staging_dir}/${tar_name}"

    {
        echo "📦 Creating tar for chunk_${chunk_id}..."
        echo "Debug: Chunk file list path: $chunk_file_list"
        echo "Debug: Creating tar file at $tar_path"
        echo ""
    } >> "$tmp_log"

    local TAR_OUTPUT TAR_STATUS
    TAR_OUTPUT=$(ionice -c2 -n7 nice -n 10 tar -cf "$tar_path" -T "$chunk_file_list" --warning=no-file-changed 2>&1)
    TAR_STATUS=$?

    echo "$TAR_OUTPUT" >> "$tmp_log"

    (
        flock 200
        if [[ $TAR_STATUS -ne 0 ]]; then
            local FAIL_LINE
            FAIL_LINE=$(echo "$TAR_OUTPUT" | grep -iE 'cannot open|permission denied|cannot stat' | head -1 || echo "Unknown error")
            echo "❌ Failed to create ${tar_name}: ${FAIL_LINE}" >> "$tmp_log"
            rm -f "$tar_path"
            echo "${tar_name}: FAIL: ${FAIL_LINE}" >> "$STATUS_FILE"
        else
            echo "✅ ${tar_name}" >> "$tmp_log"
            echo "${tar_name}: OK" >> "$STATUS_FILE"
        fi
    ) 200>"$STATUS_LOCK"
}

export -f generate_tar
export STATUS_FILE
export STATUS_LOCK

# Find all chunk files
mapfile -t chunk_files < <(find "$STAGING_DIR" -name 'chunk_*_files.txt' | sort)

for chunk_file in "${chunk_files[@]}"; do
    chunk_id=$(basename "$chunk_file" | grep -oP 'chunk_\K[0-9]+')
    tar_name="archive_part_${chunk_id}.tar"

    # Skip if already completed successfully
    if grep -q "^${tar_name}: OK$" "$STATUS_FILE" 2>/dev/null; then
        echo "ℹ️ Skipping ${tar_name}, already completed."
        continue
    fi

    # Wait for available job slot
    while : ; do
        running_jobs=$(jobs -rp | wc -l)
        if (( running_jobs < MAX_JOBS )); then
            break
        fi
        sleep 1
    done

    # Launch tar creation job in background
    generate_tar "$chunk_file" "$STAGING_DIR" "$TMP_LOG_DIR/$(basename "$chunk_file").log" &
done

wait

# Summarize results in manifest
echo "" >> "$MANIFEST"
success_count=0
fail_count=0

while IFS= read -r line; do
    tar_file=$(echo "$line" | cut -d':' -f1)
    status=$(echo "$line" | cut -d':' -f2- | xargs)

    if [[ "$status" == "OK" ]]; then
        echo "✅ $tar_file" >> "$MANIFEST"
        ((success_count++))
    else
        echo "❌ $tar_file: $status" >> "$MANIFEST"
        ((fail_count++))
    fi
done < "$STATUS_FILE"

echo "" >> "$MANIFEST"
echo "Total tar files created successfully: $success_count" >> "$MANIFEST"
echo "Total tar files failed: $fail_count" >> "$MANIFEST"

echo "✅ All parallel tar creation attempts finished."
echo "✅ Manifest generated at $MANIFEST"
