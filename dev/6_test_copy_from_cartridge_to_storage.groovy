pipeline {
    agent { label 'PP6_hbp_tapebackup' }
    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory inside staging')
        string(name: 'BATCH_ID', defaultValue: '', description: 'Batch ID to be processed')
        string(name: 'CARTRIDGE_ID', defaultValue: '', description: 'Cartridge ID to process the batch')
        string(name: 'FILES', defaultValue: '', description: 'Comma-separated list of files to copy')
        string(name: 'DRIVE_NUMBER', defaultValue: '', description: 'Drive number (0, 1, or 2)')
    }
    stages {
        stage('Copy Files to NFS') {
            steps {
                script {
                    // Define the NFS target directory where files need to be restored
                    def nfsTargetDir = "/mnt/nfs/restorationPoint/${params.SUBDIRECTORY}"
                    // Create the target directory if it doesn't exist
                    sh "mkdir -p '${nfsTargetDir}'"
                    // Get the list of files to copy from the files parameter (received from 5_testcopyjob)
                    def files = params.FILES.split(',')
                    // Define the source directory for files from the tape
                    def sourceDir = "/mnt/tape/${params.CARTRIDGE_ID}/${params.SUBDIRECTORY}"
                    // Copy each file from the source directory to the NFS restoration point
                    files.each { file ->
                        // Clean the file path and ensure it references the correct location on the tape
                        def cleanFile = file.trim().replaceAll('^"|"$', '') // Remove surrounding quotes

                        // Extract just the filename from the full path
                        def fileName = cleanFile.split('/').last()

                        def sourceFile = "${sourceDir}/${fileName}"
                        echo "Copying ${sourceFile} to ${nfsTargetDir}/..."
                        // Check if the file exists before copying
                        sh """
                            if [ -f '${sourceFile}' ]; then
                                echo '⏳ Copying ${sourceFile} to ${nfsTargetDir}/'
                                cp -v '${sourceFile}' '${nfsTargetDir}/'
                                echo '✅ File copied: ${fileName}'
                            else
                                echo '❌ File not found: ${sourceFile}'
                                echo 'Expected file at: ${sourceFile}'
                                echo 'Available files in ${sourceDir}:'
                                ls -la '${sourceDir}' || echo 'Directory ${sourceDir} not found'
                            fi
                        """
                    }
                    echo "✅ Batch ${params.BATCH_ID} processed successfully on cartridge ${params.CARTRIDGE_ID} at ${nfsTargetDir}"
                }
            }
        }

        stage('Trigger Extraction Job') {
            steps {
                script {
                    // Trigger the next job in the pipeline
                    build job: '7_Untar',
                          parameters: [
                              string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY),
                              string(name: 'BATCH_ID', value: params.BATCH_ID),
                              string(name: 'FILES', value: params.FILES)
                          ],
                          wait: false
                }
            }
        }

        stage('Safely Unmount and Unload Cartridge') {
            steps {
                script {
                    def cartridgeId = params.CARTRIDGE_ID
                    def driveNumber = params.DRIVE_NUMBER

                    // Validate DRIVE_NUMBER is provided
                    if (!driveNumber || driveNumber.trim() == '') {
                        error("❌ DRIVE_NUMBER parameter is required but was not provided")
                    }

                    def mountPath = "/mnt/tape/${cartridgeId}"
                    def tapeDevice = "/dev/fixed_st${driveNumber}"

                    echo "=========================================="
                    echo "🔄 STARTING SAFE UNMOUNT AND UNLOAD PROCESS"
                    echo "=========================================="
                    echo "Cartridge: ${cartridgeId}"
                    echo "Drive: ${driveNumber}"
                    echo "Mount Path: ${mountPath}"
                    echo "Tape Device: ${tapeDevice}"
                    echo "=========================================="

                    // Step 1: Verify no active I/O operations
                    echo "📋 Step 1: Checking for active processes..."
                    def activeProcesses = sh(
                        script: "lsof ${mountPath} 2>/dev/null || true",
                        returnStdout: true
                    ).trim()

                    if (activeProcesses) {
                        echo "⚠️  Active processes found on ${mountPath}:"
                        echo activeProcesses
                        echo "Waiting for processes to complete..."
                        sleep(time: 30, unit: 'SECONDS')
                    } else {
                        echo "✅ No active processes on mount path"
                    }

                    // Step 2: Sync filesystem to ensure all data is written
                    echo "📋 Step 2: Syncing filesystem..."
                    sh(script: "sync", returnStatus: true)
                    echo "✅ Filesystem synced"

                    // Step 3: Unmount the cartridge
                    echo "📋 Step 3: Unmounting cartridge..."
                    def unmountResult = sh(
                        script: "sudo umount ${mountPath}",
                        returnStatus: true
                    )

                    if (unmountResult != 0) {
                        echo "⚠️  Normal unmount failed. Attempting force unmount..."

                        // Try lazy unmount
                        def lazyUnmountResult = sh(
                            script: "sudo umount -l ${mountPath}",
                            returnStatus: true
                        )

                        if (lazyUnmountResult != 0) {
                            echo "⚠️  Lazy unmount failed. Checking for LTFS processes..."

                            // Kill LTFS process if running
                            def ltfsProcess = sh(
                                script: "ps aux | grep 'ltfs ${mountPath}' | grep -v grep | awk '{print \$2}'",
                                returnStdout: true
                            ).trim()

                            if (ltfsProcess) {
                                echo "Found LTFS process: ${ltfsProcess}. Terminating..."
                                sh(script: "sudo kill -9 ${ltfsProcess}", returnStatus: true)
                                sleep(time: 5, unit: 'SECONDS')
                            }

                            // Release device lock
                            echo "Releasing device lock..."
                            sh(script: "sudo sg_prevent -a ${tapeDevice}", returnStatus: true)

                            // Final unmount attempt
                            def finalUnmount = sh(
                                script: "sudo umount -f ${mountPath}",
                                returnStatus: true
                            )

                            if (finalUnmount != 0) {
                                error("❌ Failed to unmount ${mountPath} after all recovery attempts")
                            }
                        }
                    }

                    echo "✅ Cartridge unmounted successfully from ${mountPath}"

                    // Step 4: Wait for tape to be ready
                    echo "📋 Step 4: Waiting for tape drive to be ready..."
                    sleep(time: 10, unit: 'SECONDS')

                    // Step 5: Get storage element for unload
                    echo "📋 Step 5: Finding storage element for cartridge..."
                    def mtxStatus = sh(
                        script: "sudo mtx -f /dev/sg3 status | grep 'Data Transfer Element ${driveNumber}:'",
                        returnStdout: true
                    ).trim()

                    echo "Drive status: ${mtxStatus}"

                    // Parse storage element from status
                    def storageElement = sh(
                        script: "sudo mtx -f /dev/sg3 status | grep 'Data Transfer Element ${driveNumber}:' | grep -oP 'Storage Element \\K[0-9]+'",
                        returnStdout: true
                    ).trim()

                    if (!storageElement) {
                        error("❌ Could not determine storage element for drive ${driveNumber}")
                    }

                    echo "Storage Element: ${storageElement}"

                    // Step 6: Unload cartridge from drive
                    echo "📋 Step 6: Unloading cartridge from drive ${driveNumber} to storage element ${storageElement}..."
                    def unloadResult = sh(
                        script: "sudo mtx -f /dev/sg3 unload ${storageElement} ${driveNumber}",
                        returnStatus: true
                    )

                    if (unloadResult != 0) {
                        echo "⚠️  Unload failed. Attempting recovery..."

                        // Release device lock and retry
                        sh(script: "sudo sg_prevent -a ${tapeDevice}", returnStatus: true)
                        sleep(time: 5, unit: 'SECONDS')

                        def retryUnload = sh(
                            script: "sudo mtx -f /dev/sg3 unload ${storageElement} ${driveNumber}",
                            returnStatus: true
                        )

                        if (retryUnload != 0) {
                            error("❌ Failed to unload cartridge ${cartridgeId} from drive ${driveNumber}")
                        }
                    }

                    echo "✅ Cartridge unloaded successfully to storage element ${storageElement}"

                    // Step 7: Verify drive is empty
                    echo "📋 Step 7: Verifying drive is empty..."
                    def verifyStatus = sh(
                        script: "sudo mtx -f /dev/sg3 status | grep 'Data Transfer Element ${driveNumber}:'",
                        returnStdout: true
                    ).trim()

                    if (verifyStatus.contains('Empty')) {
                        echo "✅ Drive ${driveNumber} is now empty"
                    } else {
                        echo "⚠️  Warning: Drive ${driveNumber} may not be empty: ${verifyStatus}"
                    }
                    echo "=========================================="
                    echo "🎉 UNMOUNT AND UNLOAD COMPLETED SUCCESSFULLY"
                    echo "=========================================="
                    echo "Cartridge: ${cartridgeId}"
                    echo "Storage Element: ${storageElement}"
                    echo "Drive ${driveNumber} is now available"
                    echo "=========================================="
                }
            }
        }
    }

    post {
        success {
            echo "✅ Pipeline completed successfully!"
            echo "Data copied, extracted, and cartridge safely unloaded."
        }
        failure {
            echo "❌ Pipeline failed!"
            echo "Please check logs and manually verify cartridge state."
            echo "Manual recovery commands:"
            echo "  1. Check mount: df -h /mnt/tape/${params.CARTRIDGE_ID}"
            echo "  2. Force unmount: sudo umount -f /mnt/tape/${params.CARTRIDGE_ID}"
            echo "  3. Release lock: sudo sg_prevent -a /dev/fixed_st${params.DRIVE_NUMBER}"
            echo "  4. Check status: sudo mtx -f /dev/sg3 status | grep -i data"
        }
    }
}
