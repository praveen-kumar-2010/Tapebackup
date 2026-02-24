pipeline {
    agent none
    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Unique subdirectory containing the tar files')
        string(name: 'STAGING_DIR', defaultValue: '/mnt/remote/tapebackup/staging', description: 'Base staging directory')
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
        
        stage('Calculate Batch Sizes and Assign Batches') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    // Define the path for the batch files
                    def batchDir = "${params.STAGING_DIR}/${params.SUBDIRECTORY}"
                    def files = sh(script: "find ${batchDir} -name 'archive_part_*.tar' | sort", returnStdout: true).trim().split("\n")
                    // Convert the array of files to a list and then use collate
                    def filesList = files.toList()
                    // Define batch size per cartridge (assuming 14 files per batch, adjust as needed)
                    def batchSize = 14
                    def batches = []
                    // Assign files to batches
                    filesList.collate(batchSize).eachWithIndex { batchFiles, index ->
                        batches << [batch: "b${index + 1}", files: batchFiles]
                    }
                    // Write batch assignments to a JSON file
                    def batchAssignmentsFile = "${batchDir}/batch_mappings.json"
                    writeJSON(file: batchAssignmentsFile, json: batches)
                    echo "Batch assignments calculated: ${batches}"
                    // Trigger the 4_CartridgeAssignment job with the batch assignments
                    build job: '4_CartridgeAssignment', parameters: [
                        string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY),
                        string(name: 'BATCH_FILE', value: batchAssignmentsFile),
                        string(name: 'REQUIRED_CARTRIDGES', value: "${batches.size()}") // Pass the number of batches as the required cartridges
                    ]
                }
            }
        }
    }
}