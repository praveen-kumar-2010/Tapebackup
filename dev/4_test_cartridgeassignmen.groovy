pipeline {
    agent none

    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Unique subdirectory containing the tar files')
        string(name: 'BATCH_FILE', defaultValue: '', description: 'Path to the batch assignment file (JSON)')
        string(name: 'REQUIRED_CARTRIDGES', defaultValue: '', description: 'Cartridge Volume Tag (e.g., UZ4478L9)')
        string(name: 'DRIVE_NUMBERS', defaultValue: '', description: 'Drive number (0, 1, or 2)')
    }

    stages {
        stage('Initialize') {
            agent none
            steps {
                script {
                    // Set description immediately when pipeline starts
                    currentBuild.description = "Biosample: ${params.SUBDIRECTORY}"
                    echo "Pipeline initialized for subdirectory: ${params.SUBDIRECTORY}"
                }
            }
        }
        
        stage('Load Batch Assignments') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    def batchAssignments = readJSON(file: params.BATCH_FILE)
                    def batches = batchAssignments.collect { it.batch }
                    echo "Loaded batches: ${batches}"
                    env.BATCHES = batches.join(',')
                }
            }
        }

        stage('Progressive Cartridge Assignment') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    def batchAssignments = readJSON(file: params.BATCH_FILE)
                    def totalBatches = batchAssignments.size()
                    
                    echo "=== DRIVE MANAGEMENT MODE ==="
                    echo "Total batches to process: ${totalBatches}"
                    echo "Jobs will be triggered immediately as cartridges are provided"
                    echo "This allows optimal tape drive utilization"
                    
                    int processedBatches = 0
                    List allAssignedCartridges = []
                    
                    // Function to load a cartridge into a drive
                    def loadCartridge = { cartridgeName, driveNumber ->
                        echo "🔄 Loading cartridge ${cartridgeName} into drive ${driveNumber}..."

                        // Check which user Jenkins is running as
                        def currentUser = sh(script: 'whoami', returnStdout: true).trim()
                        echo "🔍 Running as user: ${currentUser}"

                        // Validate drive number
                        if (!(driveNumber in ['0', '1', '2'])) {
                            error("Invalid drive number: ${driveNumber}. Must be 0, 1, or 2.")
                        }

                        // Check drive status
                        echo "🔍 Checking drive ${driveNumber} status..."
                        def driveStatusOutput = sh(
                            script: "sudo mtx -f /dev/sg3 status | grep -i 'Data Transfer Element ${driveNumber}'",
                            returnStdout: true
                        ).trim()

                        
                        // Check if drive is Full or Empty
                        if (driveStatusOutput.contains('Full')) {
                            error("❌ Selected drive ${driveNumber} is full. Please unload the drive first or select a different drive.")
                        }

                        if (!driveStatusOutput.contains('Empty')) {
                            error("❌ Unable to determine drive ${driveNumber} status. Output: ${driveStatusOutput}")
                        }

                        echo "✅ Drive ${driveNumber} is empty, proceeding with load..."

                        // Find storage element for the given Volume Tag
                        echo "🔍 Searching for cartridge ${cartridgeName} in library..."
                        def mtxStatusOutput = sh(
                            script: "sudo mtx -f /dev/sg3 status",
                            returnStdout: true
                        ).trim()

                        // Parse storage element number for the Volume Tag
                        def storageElementMatch = sh(
                            script: "sudo mtx -f /dev/sg3 status | grep -i '${cartridgeName}' | grep -oP 'Storage Element \\K[0-9]+' || true",
                            returnStdout: true
                        ).trim()

                        if (!storageElementMatch) {
                            // Try alternative parsing
                            def findResult = sh(
                                script: """
                                    sudo mtx -f /dev/sg3 status | grep -i '${cartridgeName}' | head -1 | sed -n 's/.*Storage Element \\([0-9]*\\).*/\\1/p'
                                """,
                                returnStdout: true
                            ).trim()

                            if (!findResult) {
                                error("❌ Cartridge ${cartridgeName} not found in library. Please verify the Volume Tag is correct.")
                            }
                            storageElementMatch = findResult
                        }

                        echo "✅ Found cartridge ${cartridgeName} in Storage Element ${storageElementMatch}"

                        // Execute load command
                        echo "📥 Loading cartridge from Storage Element ${storageElementMatch} to Drive ${driveNumber}..."
                        def loadResult = sh(
                            script: "sudo mtx -f /dev/sg3 load ${storageElementMatch} ${driveNumber}",
                            returnStatus: true
                        )

                        if (loadResult != 0) {
                            error("❌ Failed to load cartridge ${cartridgeName} from Storage Element ${storageElementMatch} to Drive ${driveNumber}. Load command returned error code: ${loadResult}")
                        }

                        echo "✅ Cartridge ${cartridgeName} successfully loaded into drive ${driveNumber}"

                        // Wait for drive to be ready after load
                        echo "⏳ Waiting for drive to be ready..."
                        sleep(time: 15, unit: 'SECONDS')

                        // Step 2: Format the tape using mkltfs
                        echo "📀 Formatting tape in drive ${driveNumber}..."
                        def formatResult = sh(
                            script: "sudo mkltfs -d /dev/fixed_st${driveNumber}",
                            returnStatus: true
                        )

                        if (formatResult != 0) {
                            echo "⚠️  Normal format failed (tape may already be formatted). Trying force format..."

                            def forceFormatResult = sh(
                                script: "sudo mkltfs -d /dev/fixed_st${driveNumber} -f",
                                returnStatus: true
                            )

                            if (forceFormatResult != 0) {
                                error("❌ Failed to format tape in drive ${driveNumber}. mkltfs returned error code: ${forceFormatResult}")
                            }

                            echo "✅ Tape force formatted successfully in drive ${driveNumber}"
                        } else {
                            echo "✅ Tape formatted successfully in drive ${driveNumber}"
                        }

                        // Step 3: Create mount directory
                        def mountPath = "/mnt/tape/${cartridgeName}"
                        echo "📁 Creating mount directory: ${mountPath}"

                        // Check if directory already exists
                        def dirExists = sh(
                            script: "test -d ${mountPath}",
                            returnStatus: true
                        ) == 0

                        if (dirExists) {
                            echo "⚠️  Mount directory ${mountPath} already exists, skipping creation"
                        } else {
                            def mkdirResult = sh(
                                script: "sudo mkdir -p ${mountPath}",
                                returnStatus: true
                            )

                            if (mkdirResult != 0) {
                                error("❌ Failed to create mount directory: ${mountPath}")
                            }

                            echo "✅ Mount directory created: ${mountPath}"
                        }

                        // Step 4: Mount LTFS tape
                        echo "🔗 Mounting LTFS tape at ${mountPath}..."
                        def mountResult = sh(
                            script: "sudo ltfs ${mountPath}/ -o devname=/dev/fixed_st${driveNumber}",
                            returnStatus: true
                        )

                        if (mountResult != 0) {
                            error("❌ Failed to mount LTFS tape. ltfs returned error code: ${mountResult}")
                        }

                        echo "✅ LTFS tape mounted successfully at ${mountPath}"

                        // Verify mount
                        def verifyMount = sh(
                            script: "df -h ${mountPath}",
                            returnStdout: true
                        ).trim()

                        echo "📊 Mount verification:\n${verifyMount}"

                        echo "🎉 Cartridge ${cartridgeName} loaded, formatted, and mounted successfully!"
                        return true
                    }

                    // Function to verify cartridge is mounted as tape
                    def verifyCartridgeMount = { cartridgeName ->
                        def mountPath = "/mnt/tape/${cartridgeName}"
                        
                        echo "🔍 Verifying cartridge mount for: ${cartridgeName}"
                        echo "Expected mount path: ${mountPath}"
                        
                        // Check if mount point exists
                        def mountPointExists = sh(
                            script: "test -d ${mountPath}",
                            returnStatus: true
                        ) == 0
                        
                        if (!mountPointExists) {
                            echo "❌ Mount point ${mountPath} does not exist"
                            return false
                        }
                        
                        // Get filesystem info for the mount point
                        def dfOutput = sh(
                            script: "df -h ${mountPath} 2>/dev/null || echo 'MOUNT_CHECK_FAILED'",
                            returnStdout: true
                        ).trim()
                        
                        echo "Mount check output:"
                        echo dfOutput
                        
                        if (dfOutput.contains('MOUNT_CHECK_FAILED')) {
                            echo "❌ Failed to get mount information for ${mountPath}"
                            return false
                        }
                        
                        // Check if it's mounted as LTFS (tape filesystem)
                        def isLtfsMount = dfOutput.contains('ltfs:') || dfOutput.contains('/dev/st') || dfOutput.contains('/dev/fixed_st')
                        
                        if (!isLtfsMount) {
                            echo "❌ ${mountPath} is NOT mounted as a tape device"
                            echo "Current filesystem appears to be local storage, not tape"
                            echo "Expected: ltfs:/dev/st* or similar tape device"
                            return false
                        }
                        
                        // Additional check: verify it's not the root filesystem
                        def isRootFilesystem = dfOutput.contains('/dev/sda') || dfOutput.contains('Use% Mounted on /')
                        
                        if (isRootFilesystem) {
                            echo "❌ ${mountPath} appears to be on root filesystem (/dev/sda*)"
                            echo "This indicates the tape is NOT properly mounted"
                            return false
                        }
                        
                        echo "✅ Cartridge ${cartridgeName} is properly mounted as tape device"
                        echo "Mount verification successful!"
                        return true
                    }
                    
                    // Function to update chunk_assignments.json with cartridge ID
                    def updateChunkAssignment = { batchInfo, cartridgeName ->
                        def chunkAssignmentPath = "/mnt/remote/tapebackup/staging/${params.SUBDIRECTORY}/chunk_assignments.json"

                        echo "📝 Updating chunk_assignments.json with cartridge: ${cartridgeName}"
                        echo "📁 File path: ${chunkAssignmentPath}"

                        // Get the list of filenames from batch (extract filename from full path)
                        def batchFileNames = batchInfo.files.collect { filePath ->
                            def path = filePath.toString()
                            return path.substring(path.lastIndexOf('/') + 1)
                        }

                        def fileNamesJson = batchFileNames.collect { "\"${it}\"" }.join(',')
                        echo "🔍 Batch files to match: ${batchFileNames}"

                        // Update JSON using Python
                        def updateResult = sh(
                            script: """#!/bin/bash
echo "Running as user: \$(whoami)"
echo "File permissions:"
ls -la '${chunkAssignmentPath}' || echo "File not found"

python3 << 'PYTHON_SCRIPT'
import json
import sys
import os

chunk_file = '${chunkAssignmentPath}'
cartridge_id = '${cartridgeName}'
batch_files = [${fileNamesJson}]

print(f"Reading file: {chunk_file}")
print(f"Cartridge ID: {cartridge_id}")
print(f"Files to match: {batch_files}")

if not os.path.exists(chunk_file):
    print(f"ERROR: File does not exist: {chunk_file}")
    sys.exit(1)

try:
    with open(chunk_file, 'r') as f:
        data = json.load(f)

    print(f"Loaded {len(data)} entries from JSON")

    updated_count = 0
    for entry in data:
        if entry.get('chunk') in batch_files:
            entry['cartridge'] = cartridge_id
            updated_count += 1
            print(f"Updated: {entry.get('chunk')} -> {cartridge_id}")

    # Write JSON with each entry on a single line
    with open(chunk_file, 'w') as f:
        f.write('[\\n')
        for i, entry in enumerate(data):
            line = json.dumps(entry, separators=(',', ':'))
            if i < len(data) - 1:
                f.write(f'  {line},\\n')
            else:
                f.write(f'  {line}\\n')
        f.write(']\\n')

    print(f"Successfully updated {updated_count} entries")
    print("File write completed successfully")
    sys.exit(0)
except PermissionError as e:
    print(f"PERMISSION ERROR: Cannot write to {chunk_file}")
    print(f"Details: {e}")
    sys.exit(1)
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
PYTHON_SCRIPT
""",
                            returnStatus: true
                        )

                        if (updateResult == 0) {
                            echo "✅ chunk_assignments.json updated successfully"
                        } else {
                            echo "❌ Failed to update chunk_assignments.json"
                        }

                        // Show the updated file content
                        def content = sh(script: "cat '${chunkAssignmentPath}'", returnStdout: true).trim()
                        echo "📄 Updated file content:\n${content}"

                        return updateResult == 0
                    }

                    // Function to create JSON file with batch info and copy to cartridge
                    def createAndCopyBatchJSON = { batchInfo, cartridgeName ->
                        def jsonData = [
                            batchId: batchInfo.batch,
                            cartridgeName: cartridgeName,
                            files: batchInfo.files
                        ]
                        
                        // Create JSON file in staging directory
                        def jsonFileName = "${batchInfo.batch}_${cartridgeName}.json"
                        def stagingJsonPath = "/mnt/remote/tapebackup/staging/${jsonFileName}"
                        def cartridgeJsonPath = "/mnt/tape/${cartridgeName}/${jsonFileName}"
                        
                        echo "📝 Creating JSON file for batch ${batchInfo.batch} and cartridge ${cartridgeName}"
                        
                        // Write JSON to staging directory
                        writeJSON file: stagingJsonPath, json: jsonData, pretty: 2
                        
                        echo "✅ JSON file created: ${stagingJsonPath}"
                        echo "JSON content: ${jsonData}"
                        
                        // Copy JSON file to cartridge
                        echo "📋 Copying JSON file to cartridge ${cartridgeName}..."
                        
                        def copyResult = sh(
                            script: "cp '${stagingJsonPath}' '${cartridgeJsonPath}'",
                            returnStatus: true
                        )
                        
                        if (copyResult == 0) {
                            echo "✅ JSON file successfully copied to cartridge: ${cartridgeJsonPath}"
                        } else {
                            echo "❌ Failed to copy JSON file to cartridge ${cartridgeName}"
                            error("JSON file copy failed for batch ${batchInfo.batch}")
                        }
                        
                        return jsonFileName
                    }
                    
                    // Check if cartridges and drives are provided via pipeline parameters
                    def paramCartridges = params.REQUIRED_CARTRIDGES?.trim() ? params.REQUIRED_CARTRIDGES.split(',').collect { it.trim() }.findAll { it } : []
                    def paramDrives = params.DRIVE_NUMBERS?.trim() ? params.DRIVE_NUMBERS.split(',').collect { it.trim() }.findAll { it } : []
                    def useParamCartridges = !paramCartridges.isEmpty() && !paramDrives.isEmpty() && paramCartridges.size() == paramDrives.size()

                    if (useParamCartridges) {
                        echo "📋 Using cartridges and drives from pipeline parameters"
                        echo "Cartridges: ${paramCartridges}"
                        echo "Drives: ${paramDrives}"
                    }

                    int paramIndex = 0  // Track which parameter cartridge we're on

                    // Process all batches progressively
                    while (processedBatches < totalBatches) {
                        // Get remaining batches
                        def remainingBatches = batchAssignments[processedBatches..<totalBatches]
                        def remainingBatchNames = remainingBatches*.batch
                        def cartridgesNeeded = remainingBatches.size()

                        echo "--- Current Status ---"
                        echo "Processed: ${processedBatches}/${totalBatches} batches"
                        echo "Remaining batches: ${remainingBatchNames}"
                        echo "Cartridges needed: ${cartridgesNeeded}"

                        def providedCartridges = []
                        def cartridgeDriveMap = [:]

                        // Use pipeline parameters if available and not exhausted
                        if (useParamCartridges && paramIndex < paramCartridges.size()) {
                            // Get remaining cartridges from parameters
                            def remainingParamCartridges = paramCartridges[paramIndex..<paramCartridges.size()]
                            def remainingParamDrives = paramDrives[paramIndex..<paramDrives.size()]
                            def cartridgesToUse = Math.min(remainingParamCartridges.size(), cartridgesNeeded)

                            echo "📋 Using ${cartridgesToUse} cartridge(s) from pipeline parameters"

                            for (int i = 0; i < cartridgesToUse; i++) {
                                def cartridgeName = remainingParamCartridges[i]
                                def driveNumber = remainingParamDrives[i]

                                if (!(driveNumber in ['0', '1', '2'])) {
                                    echo "⚠️  Invalid drive number '${driveNumber}' for cartridge '${cartridgeName}'. Must be 0, 1, or 2."
                                    continue
                                }

                                providedCartridges.add(cartridgeName)
                                cartridgeDriveMap[cartridgeName] = driveNumber
                            }

                            paramIndex += cartridgesToUse
                        } else {
                            // Prompt user for cartridge(s) and drive numbers
                            def userInput = input(
                                message: "Provide cartridge name(s) and drive number(s) for remaining batches: ${remainingBatchNames}\nYou need ${cartridgesNeeded} more cartridge(s).\n\nFormat: CARTRIDGE:DRIVE (e.g., UZ001:0 or UZ001:0,UZ002:1,UZ003:2)\nAvailable drives: 0, 1, 2",
                                parameters: [
                                    string(name: 'CARTRIDGE_DRIVE_PAIRS', defaultValue: '', description: "Enter cartridge:drive pairs (comma-separated). Example: UZ001:0 or UZ001:0,UZ002:1,UZ003:2")
                                ]
                            )

                            // Parse and validate input - expecting format: CARTRIDGE:DRIVE
                            def inputPairs = userInput.split(',').collect { it.trim() }.findAll { it }

                            for (pair in inputPairs) {
                                if (!pair.contains(':')) {
                                    echo "⚠️  Invalid format for '${pair}'. Expected format: CARTRIDGE:DRIVE (e.g., UZ001:0)"
                                    continue
                                }
                                def parts = pair.split(':')
                                if (parts.size() != 2) {
                                    echo "⚠️  Invalid format for '${pair}'. Expected format: CARTRIDGE:DRIVE"
                                    continue
                                }
                                def cartridgeName = parts[0].trim()
                                def driveNumber = parts[1].trim()

                                if (!(driveNumber in ['0', '1', '2'])) {
                                    echo "⚠️  Invalid drive number '${driveNumber}' for cartridge '${cartridgeName}'. Must be 0, 1, or 2."
                                    continue
                                }

                                providedCartridges.add(cartridgeName)
                                cartridgeDriveMap[cartridgeName] = driveNumber
                            }
                        }

                        echo "Parsed cartridge-drive assignments: ${cartridgeDriveMap}"
                        
                        if (providedCartridges.isEmpty()) {
                            echo "No cartridges provided. Please try again."
                            continue
                        }

                        // LOAD CARTRIDGES INTO DRIVES
                        echo "📥 LOADING CARTRIDGES INTO DRIVES..."
                        def loadedCartridges = []
                        def failedToLoadCartridges = []

                        for (cartridge in providedCartridges) {
                            def driveNumber = cartridgeDriveMap[cartridge]
                            echo "Processing cartridge ${cartridge} for drive ${driveNumber}..."

                            try {
                                loadCartridge(cartridge, driveNumber)
                                loadedCartridges.add(cartridge)
                                echo "✅ Successfully loaded ${cartridge} into drive ${driveNumber}"
                            } catch (Exception e) {
                                echo "❌ Failed to load ${cartridge}: ${e.message}"
                                failedToLoadCartridges.add(cartridge)
                            }
                        }

                        // Report load/mount results
                        if (loadedCartridges.size() > 0) {
                            echo "✅ Successfully loaded cartridges: ${loadedCartridges}"
                        }

                        if (failedToLoadCartridges.size() > 0) {
                            echo "❌ Failed to load cartridges: ${failedToLoadCartridges}"

                            def loadChoice = input(
                                message: "⚠️  Some cartridges failed to load!\n\nFailed: ${failedToLoadCartridges}\nSuccessful: ${loadedCartridges}\n\nWhat would you like to do?",
                                parameters: [
                                    choice(
                                        name: 'LOAD_ACTION',
                                        choices: [
                                            'retry_with_different_cartridges',
                                            'continue_with_loaded_only'
                                        ],
                                        description: 'Choose your action:\n- retry_with_different_cartridges: Re-enter cartridge and drive information\n- continue_with_loaded_only: Proceed with only the successfully loaded cartridges'
                                    )
                                ]
                            )

                            if (loadChoice == 'retry_with_different_cartridges') {
                                echo "Please check the cartridges and drives, then try again."
                                continue  // Go back to ask for cartridges again
                            }
                            // If continuing with loaded only, update providedCartridges
                            providedCartridges = loadedCartridges
                        }

                        if (providedCartridges.isEmpty()) {
                            echo "No cartridges were successfully loaded. Please try again."
                            continue
                        }

                        // VERIFY ALL CARTRIDGES ARE PROPERLY MOUNTED BEFORE PROCEEDING
                        echo "🔍 VERIFYING CARTRIDGE MOUNTS..."
                        def validCartridges = []
                        def invalidCartridges = []

                        for (cartridge in providedCartridges) {
                            if (verifyCartridgeMount(cartridge)) {
                                validCartridges.add(cartridge)
                            } else {
                                invalidCartridges.add(cartridge)
                            }
                        }
                        
                        // Report verification results
                        if (validCartridges.size() > 0) {
                            echo "✅ Valid cartridges (properly mounted): ${validCartridges}"
                        }
                        
                        if (invalidCartridges.size() > 0) {
                            echo "❌ Invalid cartridges (NOT properly mounted): ${invalidCartridges}"
                            
                            def userChoice = input(
                                message: "⚠️  Cartridge mount verification failed!\n\nInvalid cartridges: ${invalidCartridges}\nValid cartridges: ${validCartridges}\n\nWhat would you like to do?",
                                parameters: [
                                    choice(
                                        name: 'ACTION',
                                        choices: [
                                            'give_cartridge_again',
                                            'proceed_to_local_storage'
                                        ],
                                        description: 'Choose your action:\n- give_cartridge_again: Mount cartridges properly and re-enter cartridge names\n- proceed_to_local_storage: Continue with backup to local storage (NOT recommended!)'
                                    )
                                ]
                            )
                            
                            if (userChoice == 'give_cartridge_again') {
                                echo "Please mount the cartridge(s) properly and provide cartridge names again."
                                echo "The pipeline will re-check the mount status when you re-enter cartridge names."
                                continue  // Go back to ask for cartridges again
                            } else if (userChoice == 'proceed_to_local_storage') {
                                echo "⚠️  WARNING: Proceeding to backup to LOCAL STORAGE instead of tape!"
                                echo "⚠️  This means your backup will be saved to /mnt/tape/${invalidCartridges[0]} on local disk"
                                echo "⚠️  This is NOT the intended tape backup behavior!"
                                validCartridges = invalidCartridges  // Use invalid cartridges (will copy to local)
                            }
                        }
                        
                        if (validCartridges.isEmpty()) {
                            echo "No cartridges to process. Please try again."
                            continue
                        }
                        
                        // Process only as many cartridges as we have batches
                        def cartridgesToProcess = Math.min(validCartridges.size(), cartridgesNeeded)
                        
                        echo ">>> TRIGGERING JOBS IMMEDIATELY <<<"
                        echo "Valid cartridges: ${validCartridges.size()}"
                        echo "Will process: ${cartridgesToProcess} cartridge(s)"
                        
                        // Trigger jobs IMMEDIATELY for validated cartridges
                        for (int i = 0; i < cartridgesToProcess; i++) {
                            def batch = remainingBatches[i]
                            def cartridgeName = validCartridges[i]
                            def driveNumber = cartridgeDriveMap[cartridgeName]

                            echo "🚀 TRIGGERING: Batch ${batch.batch} → Cartridge ${cartridgeName} on Drive ${driveNumber} (VERIFIED MOUNTED)"
                            
                            // Create and copy JSON file to cartridge BEFORE triggering the job
                            def jsonFileName = createAndCopyBatchJSON(batch, cartridgeName)
                            echo "📋 JSON file created and copied: ${jsonFileName}"

                            // Update chunk_assignment.json with the cartridge ID
                            updateChunkAssignment(batch, cartridgeName)

                            // Start the backup job immediately (non-blocking for parallel execution)
                            echo "🚀 Starting backup job for batch ${batch.batch}..."
                            build job: '5_Copy_from_storage_to_cartridge',
                                  wait: false,  // DON'T WAIT - allows parallel execution on multiple drives
                                  parameters: [
                                string(name: 'BATCH_ID', value: batch.batch),
                                string(name: 'CARTRIDGE_ID', value: cartridgeName),
                                string(name: 'FILES', value: batch.files.join(',')),
                                string(name: 'STAGING_DIR', value: '/mnt/remote/tapebackup/staging'),
                                string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY),
                                string(name: 'CARTRIDGE_NAMES', value: cartridgeName),
                                string(name: 'BATCH_FILE', value: params.BATCH_FILE),
                                string(name: 'DRIVE_NUMBER', value: driveNumber)
                            ]
                            
                            allAssignedCartridges.add(cartridgeName)
                            echo "✅ Job triggered for batch ${batch.batch} with cartridge ${cartridgeName}"
                        }
                        
                        // Update progress
                        processedBatches += cartridgesToProcess
                        
                        echo "--- Progress Update ---"
                        echo "Jobs triggered this round: ${cartridgesToProcess}"
                        echo "Total completed: ${processedBatches}/${totalBatches}"
                        echo "Assigned cartridges so far: ${allAssignedCartridges}"
                        
                        // Show remaining work
                        if (processedBatches < totalBatches) {
                            def remaining = totalBatches - processedBatches
                            echo "⏳ Still need ${remaining} more cartridge(s) for remaining batches"
                            echo "Jobs are running in parallel on available drives..."
                        }
                    }
                    
                    echo "🎉 ALL BATCHES PROCESSED!"
                    echo "Total batches: ${totalBatches}"
                    echo "Total cartridges assigned: ${allAssignedCartridges.size()}"
                    echo "Final assignments: ${allAssignedCartridges}"
                    echo "All backup jobs have been triggered and are running on available tape drives."
                }
            }
        }
    }
}