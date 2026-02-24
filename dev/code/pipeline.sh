pipeline {
    agent none

    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory')
        string(name: 'CARTRIDGE_NAMES', defaultValue: '', description: 'Comma-separated cartridge names')
    }

    stages {
        stage('Create Tar Files') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    def target_dir = "/mnt/remote/tapebackup/staging/${params.SUBDIRECTORY}"
                    echo "Creating tar files in directory: ${target_dir}"
                    sh "/mnt/remote/tapebackup/code/tar_creation.sh ${target_dir} ${target_dir}"
                }
            }
        }

        stage('Assign Chunks to Cartridges') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    def target_dir = "/mnt/remote/tapebackup/staging/${params.SUBDIRECTORY}"
                    def cartridgeNames = params.CARTRIDGE_NAMES.split(',').collect { it.trim() }
                    def maxSize = (14L * 1024 * 1024 * 1024 * 1024) as Long // 14TB, no buffer

                    // Calculate tar file sizes
                    def tarFiles = sh(script: "find ${target_dir} -name 'archive_part_*.tar' | sort", returnStdout: true).trim().split('\n')
                    if (tarFiles.size() == 0) {
                        error "No tar files found in ${target_dir}"
                    }
                    def chunkList = []
                    for (tarFile in tarFiles) {
                        def chunkName = tarFile.tokenize('/')[-1]
                        def size = sh(script: "stat -c%s '${tarFile}'", returnStdout: true).trim().toLong()
                        chunkList.add([name: chunkName, size: size])
                    }

                    // Assign chunks to cartridges (first-fit)
                    def cartridges = []
                    def currentCartridge = [chunks: [], size: 0L]
                    for (chunk in chunkList) {
                        if (currentCartridge.size + chunk.size <= maxSize) {
                            currentCartridge.chunks.add(chunk.name)
                            currentCartridge.size += chunk.size
                        } else {
                            if (currentCartridge.chunks) {
                                cartridges.add(currentCartridge)
                            }
                            currentCartridge = [chunks: [chunk.name], size: chunk.size]
                        }
                    }
                    if (currentCartridge.chunks) {
                        cartridges.add(currentCartridge)
                    }

                    // Verify number of cartridges does not exceed CARTRIDGE_NAMES
                    if (cartridges.size() > cartridgeNames.size()) {
                        error "Assigned ${cartridges.size()} cartridges, but only ${cartridgeNames.size()} names provided"
                    }

                    // Create chunk-to-cartridge assignments
                    def chunkAssignments = []
                    for (int i = 0; i < cartridges.size(); i++) {
                        def cartridge = cartridges[i]
                        def cartridgeName = cartridgeNames[i]
                        for (chunkName in cartridge.chunks) {
                            chunkAssignments.add([chunk: chunkName, cartridge: cartridgeName])
                        }
                    }

                    // Write assignments to JSON
                    def assignmentsJson = groovy.json.JsonOutput.toJson(chunkAssignments)
                    writeFile file: "${target_dir}/chunk_assignments.json", text: assignmentsJson
                    echo "Chunk assignments written to ${target_dir}/chunk_assignments.json: ${assignmentsJson}"

                    // Store cartridge assignments for Trigger Copy Jobs stage
                    def cartridgeAssignments = []
                    for (int i = 0; i < cartridges.size(); i++) {
                        cartridgeAssignments.add([
                            cartridge: cartridgeNames[i],
                            files: cartridges[i].chunks
                        ])
                    }
                    env.CARTRIDGE_ASSIGNMENTS = groovy.json.JsonOutput.toJson(cartridgeAssignments)
                }
            }
        }

        stage('Trigger Copy Jobs') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    def cartridgeAssignments = readJSON text: env.CARTRIDGE_ASSIGNMENTS
                    def parallelJobs = [:]

                    cartridgeAssignments.eachWithIndex { assignment, index ->
                        def cartridgeName = assignment.cartridge
                        def filesToCopy = assignment.files.join(',')
                        parallelJobs["copy_${cartridgeName}"] = {
                            build job: '4_CartridgeCopyJob', parameters: [
                                string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY),
                                string(name: 'STAGING_DIR', value: '/mnt/remote/tapebackup/staging'),
                                string(name: 'CARTRIDGE_NAME', value: cartridgeName),
                                string(name: 'FILES_TO_COPY', value: filesToCopy)
                            ], propagate: false
                        }
                    }

                    parallel parallelJobs
                }
            }
        }
    }
}




Copy_job

pipeline {
    agent { label 'PP6_hbp_tapebackup' }

    parameters {
        string(name: 'CARTRIDGE_NAME', defaultValue: '', description: 'Name of cartridge')
        string(name: 'FILES_TO_COPY', defaultValue: '', description: 'Files to copy')
        string(name: 'STAGING_DIR', defaultValue: '', description: 'Staging directory')
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory')
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    if (!params.CARTRIDGE_NAME?.trim()) error("CARTRIDGE_NAME is required!")
                    if (!params.FILES_TO_COPY?.trim()) error("FILES_TO_COPY is required!")
                }
            }
        }

        stage('Copy Files') {
            steps {
                script {
                    def sourceDir = "${params.STAGING_DIR}/${params.SUBDIRECTORY}".toString()
                    def dest = "/mnt/tape/${params.CARTRIDGE_NAME}/${params.SUBDIRECTORY}".toString()
                    
                    // Convert comma-separated list to properly quoted paths
                    def files = params.FILES_TO_COPY.split(',').collect { 
                        "${sourceDir}/${it.trim()}" 
                    }.join(' ')

                    sh """
                        mkdir -p '${dest}'
                        cd '${sourceDir}' || exit 1
                        cp -v ${files} '${dest}/'
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                // Trigger ContentAuditCopyJob with the same parameters
                build job: '5_ContentAuditCopyJob', parameters: [
                    string(name: 'STAGING_DIR', value: params.SUBDIRECTORY),
                    string(name: 'CARTRIDGE_NAMES', value: params.CARTRIDGE_NAME)
                ], wait: false
                
                echo "Triggered ContentAuditCopyJob for ${params.CARTRIDGE_NAME}"
            }
        }
    }
}