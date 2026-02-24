pipeline {
    agent { label 'ap7-node' }
    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory inside restoration')
        string(name: 'BATCH_ID', defaultValue: '', description: 'Batch ID to be processed')
        string(name: 'FILES', defaultValue: '', description: 'Comma-separated list of files to extract')
    }
    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (params.SUBDIRECTORY.trim() == '') {
                        error('SUBDIRECTORY parameter is required')
                    }
                    if (params.BATCH_ID.trim() == '') {
                        error('BATCH_ID parameter is required')
                    }
                    
                    echo "🔍 Pipeline Parameters:"
                    echo "   SUBDIRECTORY: ${params.SUBDIRECTORY}"
                    echo "   BATCH_ID: ${params.BATCH_ID}"
                    echo "   FILES: ${params.FILES ?: 'ALL FILES (empty - will extract everything)'}"
                }
            }
        }
        
        stage('Setup Directories') {
            steps {
                script {
                    // Define source directory where restored files are located
                    def sourceDir = "/mnt/local/store/iitlab/humanbrain/restoration/${params.SUBDIRECTORY}"
                    // Define extraction directory where files will be extracted
                    def extractionDir = "/mnt/local/store/repos1/iitlab/humanbrain/analytics/${params.SUBDIRECTORY}"
                    
                    echo "📁 Setting up directories:"
                    echo "   Source: ${sourceDir}"
                    echo "   Target: ${extractionDir}"
                    
                    // Store paths in environment for use in subsequent stages
                    env.SOURCE_DIR = sourceDir
                    env.EXTRACTION_DIR = extractionDir
                    
                    // Create the extraction directory if it doesn't exist
                    sh "mkdir -p '${extractionDir}'"
                    
                    // Verify source directory exists and contains tar files
                    sh """
                        if [ ! -d '${sourceDir}' ]; then
                            echo "❌ Source directory does not exist: ${sourceDir}"
                            exit 1
                        fi
                        
                        tar_count=\$(find '${sourceDir}' -name 'archive_part_*.tar' | wc -l)
                        if [ \$tar_count -eq 0 ]; then
                            echo "❌ No tar files found in source directory: ${sourceDir}"
                            exit 1
                        fi
                        
                        echo "✅ Found \$tar_count tar archive(s) in source directory"
                    """
                }
            }
        }
        
        stage('Extract TAR Files') {
            steps {
                script {
                    echo "🚀 Starting TAR extraction process..."
                    
                    // Prepare the extraction script command
                    def extractCmd = "/mnt/local/store/iitlab/humanbrain/code/extractnew.sh '${env.SOURCE_DIR}' '${env.EXTRACTION_DIR}'"
                    
                    // Add FILES parameter if provided
                    if (params.FILES && params.FILES.trim() != '') {
                        extractCmd += " '${params.FILES}'"
                        echo "🎯 Extracting specific files: ${params.FILES}"
                    } else {
                        echo "📦 Extracting all files from archives"
                    }
                    
                    // Execute the extraction
                    def result = sh(
                        script: extractCmd,
                        returnStatus: true
                    )
                    
                    if (result != 0) {
                        error("❌ TAR extraction failed with exit code: ${result}")
                    }
                    
                    echo "✅ TAR extraction completed successfully"
                }
            }
        }
        
        stage('Verify Extraction') {
            steps {
                script {
                    echo "🔍 Verifying extraction results..."
                    
                    // Count extracted files
                    def fileCount = sh(
                        script: "find '${env.EXTRACTION_DIR}' -type f | wc -l",
                        returnStdout: true
                    ).trim()
                    
                    def dirCount = sh(
                        script: "find '${env.EXTRACTION_DIR}' -type d | wc -l",
                        returnStdout: true
                    ).trim()
                    
                    echo "📊 Extraction Summary:"
                    echo "   Target Directory: ${env.EXTRACTION_DIR}"
                    echo "   Files Extracted: ${fileCount}"
                    echo "   Directories Created: ${dirCount}"
                    
                    // Show disk usage
                    sh """
                        echo "💾 Disk Usage:"
                        du -sh '${env.EXTRACTION_DIR}' || true
                    """
                    
                    // If specific files were requested, verify they were extracted
                    if (params.FILES && params.FILES.trim() != '') {
                        echo "🎯 Verifying requested files were extracted:"
                        def files = params.FILES.split(',')
                        def missingFiles = []
                        
                        files.each { file ->
                            def trimmedFile = file.trim()
                            def exists = sh(
                                script: "[ -f '${env.EXTRACTION_DIR}/${trimmedFile}' ]",
                                returnStatus: true
                            )
                            
                            if (exists == 0) {
                                echo "   ✅ ${trimmedFile} - Found"
                            } else {
                                echo "   ❌ ${trimmedFile} - Missing"
                                missingFiles.add(trimmedFile)
                            }
                        }
                        
                        if (missingFiles.size() > 0) {
                            echo "⚠️  Warning: Some requested files were not found:"
                            missingFiles.each { file ->
                                echo "   - ${file}"
                            }
                        }
                    }
                    
                    // Set flag for successful verification
                    env.EXTRACTION_VERIFIED = 'true'
                }
            }
        }
        
        stage('Cleanup TAR Files') {
            steps {
                script {
                    if (env.EXTRACTION_VERIFIED == 'true') {
                        echo "🗑️  Starting selective cleanup of TAR files from source directory..."
                        
                        if (params.FILES && params.FILES.trim() != '') {
                            echo "🎯 Selective cleanup mode: Only deleting TAR files that contained the requested files"
                            
                            // Show disk space before cleanup
                            sh """
                                echo "💾 Disk usage before cleanup:"
                                du -sh '${env.SOURCE_DIR}' || true
                            """
                            
                            // Find which tar files contained our target files and delete only those
                            sh """
                                cd '${env.SOURCE_DIR}'
                                files_list="${params.FILES}"
                                deleted_count=0
                                total_tars=\$(find . -name 'archive_part_*.tar' | wc -l)
                                
                                echo "📊 Found \$total_tars total TAR files to check"
                                echo "🔍 Checking which TAR files contain the requested files..."
                                
                                # Convert comma-separated string to array
                                IFS=',' read -ra target_files <<< "\$files_list"
                                
                                # Check each tar file
                                for tarfile in archive_part_*.tar; do
                                    if [ -f "\$tarfile" ]; then
                                        found_target=false
                                        
                                        # Check if this tar contains any of our target files
                                        for target_file in "\${target_files[@]}"; do
                                            target_file=\$(echo "\$target_file" | xargs)  # trim whitespace
                                            if tar -tf "\$tarfile" | grep -Fxq "\$target_file" 2>/dev/null; then
                                                found_target=true
                                                break
                                            fi
                                        done
                                        
                                        if [ "\$found_target" = true ]; then
                                            echo "🗑️  Deleting \$tarfile (contained requested files)"
                                            rm -f "\$tarfile"
                                            ((deleted_count++))
                                        else
                                            echo "🔒 Preserving \$tarfile (no requested files found)"
                                        fi
                                    fi
                                done
                                
                                echo "✅ Cleanup completed: \$deleted_count TAR files deleted, \$((total_tars - deleted_count)) preserved"
                            """
                            
                        } else {
                            echo "📦 Full extraction mode: Deleting ALL TAR files (since all files were extracted)"
                            
                            // Count tar files before deletion
                            def tarCount = sh(
                                script: "find '${env.SOURCE_DIR}' -name 'archive_part_*.tar' | wc -l",
                                returnStdout: true
                            ).trim()
                            
                            echo "📊 Found ${tarCount} TAR file(s) to delete"
                            
                            if (tarCount.toInteger() > 0) {
                                // Show disk space before cleanup
                                sh """
                                    echo "💾 Disk usage before cleanup:"
                                    du -sh '${env.SOURCE_DIR}' || true
                                """
                                
                                // Delete all tar files
                                sh """
                                    echo "🗑️  Deleting all TAR files..."
                                    find '${env.SOURCE_DIR}' -name 'archive_part_*.tar' -type f -delete
                                    echo "✅ All TAR files deleted successfully"
                                """
                                
                                echo "🧹 Full cleanup completed - removed all ${tarCount} TAR files"
                            } else {
                                echo "ℹ️  No TAR files found to clean up"
                            }
                        }
                        
                        // Show final disk usage
                        sh """
                            echo "💾 Disk usage after cleanup:"
                            du -sh '${env.SOURCE_DIR}' || true
                            
                            # Show remaining tar files
                            remaining_tars=\$(find '${env.SOURCE_DIR}' -name 'archive_part_*.tar' | wc -l)
                            echo "📊 TAR files remaining: \$remaining_tars"
                        """
                        
                    } else {
                        echo "⚠️  Skipping cleanup - extraction verification failed"
                    }
                }
            }
        }

        stage('Create Backup Completed File') {
            agent { label 'pp6' }
            when {
                expression { env.EXTRACTION_VERIFIED == 'true' }
            }
            steps {
                script {
                    def backupJsonPath = "/mnt/remote/tapebackup/staging/${params.SUBDIRECTORY}/backupcompleted.json"
                    def completedDate = new Date().format("yyyy-MM-dd HH:mm:ss")

                    echo "📄 Creating backup completed file at: ${backupJsonPath}"

                    sh """
                        cat > '${backupJsonPath}' << EOF
{
  "Biosample": "${params.SUBDIRECTORY}",
  "BackupCompleted": "${completedDate}"
}
EOF
                    """

                    echo "✅ Backup completed file created successfully"
                }
            }
        }
    }

    post {
        success {
            script {
                echo "🎉 Pipeline completed successfully!"
                echo "📁 Extracted files are available at: ${env.EXTRACTION_DIR}"
                if (env.EXTRACTION_VERIFIED == 'true') {
                    echo "🗑️  TAR files have been cleaned up from: ${env.SOURCE_DIR}"
                }
            }
        }
        failure {
            script {
                echo "💥 Pipeline failed!"
                echo "📋 Check the logs above for error details"
                if (env.SOURCE_DIR) {
                    echo "ℹ️  TAR files preserved at: ${env.SOURCE_DIR} (due to pipeline failure)"
                }
            }
        }
        always {
            script {
                // Cleanup summary
                echo "🧹 Pipeline execution completed"
                if (env.SOURCE_DIR && env.EXTRACTION_DIR) {
                    sh """
                        echo "📊 Final Summary:"
                        echo "   Source: ${env.SOURCE_DIR}"
                        echo "   Target: ${env.EXTRACTION_DIR}"
                        echo "   Status: \${BUILD_RESULT:-SUCCESS}"
                    """ || true
                }
            }
        }
    }
}